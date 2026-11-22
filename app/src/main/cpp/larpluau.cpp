#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <pthread.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <mutex>
#include <string>
#include <vector>

// Luau e compilado como C++ (simbolos mangled): NAO envolver em extern "C"
#include "lua.h"
#include "lualib.h"
#include "luacode.h"

#define LARP_LUAU_TAG "LarpLuau"
#define LARP_LUAU_EXPORT extern "C" __attribute__((visibility("default")))
#define LARP_LUAU_VERSION "1.0"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LARP_LUAU_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LARP_LUAU_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LARP_LUAU_TAG, __VA_ARGS__)

namespace {

std::mutex g_stateLock;
lua_State* g_state = nullptr;
lua_State* g_thread = nullptr;
std::atomic<bool> g_ready{false};

unsigned long long g_screenW = 1080;
unsigned long long g_screenH = 1920;
float g_screenScale = 1.0f;
std::string g_workspace;
std::string g_scriptsDir;

size_t g_mem = 0;
constexpr size_t kMemLimit = 64 * 1024 * 1024;

void* l_alloc(void* ud, void* ptr, size_t osize, size_t nsize) {
    (void)ud;
    size_t old = ptr ? osize : 0;
    if (nsize == 0) { if (ptr) { g_mem -= old; free(ptr); } return nullptr; }
    if (g_mem - old + nsize > kMemLimit) return nullptr;
    void* p = realloc(ptr, nsize);
    if (p) g_mem = g_mem - old + nsize;
    return p;
}

std::string luaToStdStr(lua_State* L, int idx) {
    if (lua_isnoneornil(L, idx)) return std::string();
    size_t n = 0;
    const char* s = lua_tolstring(L, idx, &n);
    return s ? std::string(s, n) : std::string();
}

void pushStdString(lua_State* L, const std::string& s) {
    lua_pushlstring(L, s.c_str(), s.size());
}

void larp_log(const char* level, const char* msg) {
    int prio = ANDROID_LOG_INFO;
    if (level[0] == 'W') prio = ANDROID_LOG_WARN;
    else if (level[0] == 'E') prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, LARP_LUAU_TAG, "%s", msg);
}

std::string normalizePath(const std::string& p) {
    std::vector<std::string> parts;
    std::string cur;
    for (size_t i = 0; i <= p.size(); ++i) {
        if (i == p.size() || p[i] == '/') {
            if (!cur.empty()) {
                if (cur == "..") { if (!parts.empty()) parts.pop_back(); }
                else if (cur != ".") parts.push_back(cur);
                cur.clear();
            }
        } else cur += p[i];
    }
    std::string out;
    for (auto& s : parts) out += "/" + s;
    if (out.empty()) out = "/";
    return out;
}

std::string resolvePath(const std::string& p) {
    std::string ws = g_workspace.empty() ? "/storage/emulated/0/Larplaucher/Workspace" : g_workspace;
    while (!ws.empty() && ws.back() == '/') ws.pop_back();
    std::string full;
    if (!p.empty() && p[0] == '/') full = p;
    else full = ws + "/" + p;
    std::string normalized = normalizePath(full);
    if (normalized.compare(0, ws.size(), ws) != 0) return "";
    return normalized;
}

std::string resolveScriptPath(const std::string& p) {
    if (!p.empty() && p[0] == '/') return normalizePath(p);
    if (g_scriptsDir.empty()) return normalizePath(p);
    return normalizePath(g_scriptsDir + "/" + p);
}

int l_print(lua_State* L) {
    int n = lua_gettop(L);
    std::string out;
    for (int i = 1; i <= n; i++) {
        size_t len = 0;
        const char* s = luaL_tolstring(L, i, &len);
        if (i > 1) out += "\t";
        if (s) out.append(s, len);
        lua_pop(L, 1);
    }
    larp_log("I", out.c_str());
    return 0;
}

int l_warn(lua_State* L) {
    std::string out = luaToStdStr(L, 1);
    larp_log("W", out.c_str());
    return 0;
}

int l_loadstring(lua_State* L) {
    std::string code = luaToStdStr(L, 1);
    std::string chunk = lua_isstring(L, 2) ? luaToStdStr(L, 2) : std::string("=chunk");
    size_t bclen = 0;
    char* bc = luau_compile(code.c_str(), code.size(), nullptr, &bclen);
    if (!bc) { lua_pushnil(L); pushStdString(L, "compile failed"); return 2; }
    int rc = luau_load(L, chunk.c_str(), bc, bclen, 0);
    free(bc);
    if (rc != 0) { lua_pushnil(L); lua_pushvalue(L, -2); lua_remove(L, -3); return 2; }
    return 1;
}

int l_request(lua_State* L) {
    lua_pushstring(L, "request not available in luau sandbox");
    lua_error(L);
    return 0;
}

int l_writefile(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    std::string content = luaToStdStr(L, 2);
    size_t slash = path.find_last_of('/');
    if (slash != std::string::npos) {
        std::string dir = path.substr(0, slash);
        std::string acc;
        for (size_t i = 0; i < dir.size(); ++i) {
            acc += dir[i];
            if (dir[i] == '/' || i + 1 == dir.size()) mkdir(acc.c_str(), 0775);
        }
    }
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { lua_pushboolean(L, 0); return 1; }
    ssize_t w = 0; size_t total = 0;
    while (total < content.size() && (w = write(fd, content.data() + total, content.size() - total)) > 0) total += w;
    close(fd);
    lua_pushboolean(L, total == content.size());
    return 1;
}

int l_readfile(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushnil(L); return 1; }
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) { lua_pushnil(L); return 1; }
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out.append(buf, n);
    close(fd);
    pushStdString(L, out);
    return 1;
}

int l_isfile(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    struct stat st;
    lua_pushboolean(L, stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode));
    return 1;
}

int l_makefolder(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    std::string acc;
    bool ok = true;
    for (size_t i = 0; i < path.size(); ++i) {
        acc += path[i];
        if (path[i] == '/' || i + 1 == path.size()) {
            if (mkdir(acc.c_str(), 0775) != 0 && errno != EEXIST) ok = false;
        }
    }
    lua_pushboolean(L, ok);
    return 1;
}

int l_listfolder(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushnil(L); return 1; }
    DIR* d = opendir(path.c_str());
    if (!d) { lua_pushnil(L); return 1; }
    lua_newtable(L);
    int i = 1;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        lua_pushstring(L, e->d_name);
        lua_rawseti(L, -2, i++);
    }
    closedir(d);
    return 1;
}

int l_removefile(lua_State* L) {
    std::string path = resolvePath(luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    lua_pushboolean(L, unlink(path.c_str()) == 0);
    return 1;
}

int l_getworkspace(lua_State* L) {
    pushStdString(L, g_workspace.empty() ? "/storage/emulated/0/Larplaucher/Workspace" : g_workspace);
    return 1;
}

int l_screen_size(lua_State* L) {
    lua_pushnumber(L, (lua_Number)g_screenW);
    lua_pushnumber(L, (lua_Number)g_screenH);
    return 2;
}

int l_screen_center(lua_State* L) {
    lua_pushnumber(L, (lua_Number)(g_screenW / 2));
    lua_pushnumber(L, (lua_Number)(g_screenH / 2));
    return 2;
}

int l_typeof(lua_State* L) {
    int t = lua_type(L, 1);
    switch (t) {
        case LUA_TNIL: lua_pushstring(L, "nil"); break;
        case LUA_TBOOLEAN: lua_pushstring(L, "boolean"); break;
        case LUA_TNUMBER: lua_pushstring(L, "number"); break;
        case LUA_TSTRING: lua_pushstring(L, "string"); break;
        case LUA_TTABLE: lua_pushstring(L, "table"); break;
        case LUA_TFUNCTION: lua_pushstring(L, "function"); break;
        case LUA_TUSERDATA: lua_pushstring(L, "userdata"); break;
        case LUA_TTHREAD: lua_pushstring(L, "thread"); break;
        case LUA_TLIGHTUSERDATA: lua_pushstring(L, "userdata"); break;
        default: lua_pushstring(L, "unknown"); break;
    }
    return 1;
}

int l_getgenv(lua_State* L) {
    lua_pushvalue(L, LUA_GLOBALSINDEX);
    return 1;
}

int l_getrenv(lua_State* L) {
    lua_pushvalue(L, LUA_REGISTRYINDEX);
    return 1;
}

int l_identifyexecutor(lua_State* L) {
    lua_pushstring(L, "LarpLauncher");
    lua_pushstring(L, LARP_LUAU_VERSION);
    return 2;
}

int l_iscclosure(lua_State* L) {
    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushboolean(L, lua_iscfunction(L, 1));
    return 1;
}

int l_islclosure(lua_State* L) {
    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushboolean(L, !lua_iscfunction(L, 1));
    return 1;
}

int l_checkcaller(lua_State* L) { lua_pushboolean(L, 1); return 1; }

int l_color(lua_State* L) {
    int r = (int)luaL_optinteger(L, 1, 255);
    int g = (int)luaL_optinteger(L, 2, 255);
    int b = (int)luaL_optinteger(L, 3, 255);
    int a = (int)luaL_optinteger(L, 4, 255);
    unsigned int v = ((unsigned int)a << 24) | ((unsigned int)r << 16) | ((unsigned int)g << 8) | (unsigned int)b;
    lua_pushinteger(L, (lua_Integer)v);
    return 1;
}

int l_notify(lua_State* L) {
    std::string s = luaToStdStr(L, 1);
    larp_log("I", s.c_str());
    return 0;
}

void registerGlobals(lua_State* L) {
    lua_pushcfunction(L, l_print, "print");
    lua_setglobal(L, "print");
    lua_pushcfunction(L, l_warn, "warn");
    lua_setglobal(L, "warn");
    lua_pushcfunction(L, l_loadstring, "loadstring");
    lua_setglobal(L, "loadstring");
    lua_pushcfunction(L, l_typeof, "typeof");
    lua_setglobal(L, "typeof");
    lua_pushcfunction(L, l_getgenv, "getgenv");
    lua_setglobal(L, "getgenv");
    lua_pushcfunction(L, l_getrenv, "getrenv");
    lua_setglobal(L, "getrenv");
    lua_pushcfunction(L, l_iscclosure, "iscclosure");
    lua_setglobal(L, "iscclosure");
    lua_pushcfunction(L, l_islclosure, "islclosure");
    lua_setglobal(L, "islclosure");
    lua_pushcfunction(L, l_checkcaller, "checkcaller");
    lua_setglobal(L, "checkcaller");
    lua_pushcfunction(L, l_identifyexecutor, "identifyexecutor");
    lua_setglobal(L, "identifyexecutor");
    lua_pushcfunction(L, l_color, "Color");
    lua_setglobal(L, "Color");

    lua_newtable(L);
    lua_setglobal(L, "shared");

    lua_newtable(L);
    lua_pushcfunction(L, l_request, "request");
    lua_setfield(L, -2, "request");
    lua_pushcfunction(L, l_writefile, "writefile");
    lua_setfield(L, -2, "writefile");
    lua_pushcfunction(L, l_readfile, "readfile");
    lua_setfield(L, -2, "readfile");
    lua_pushcfunction(L, l_isfile, "isfile");
    lua_setfield(L, -2, "isfile");
    lua_pushcfunction(L, l_makefolder, "makefolder");
    lua_setfield(L, -2, "makefolder");
    lua_pushcfunction(L, l_listfolder, "listfolder");
    lua_setfield(L, -2, "listfolder");
    lua_pushcfunction(L, l_removefile, "removefile");
    lua_setfield(L, -2, "removefile");
    lua_pushcfunction(L, l_getworkspace, "getworkspace");
    lua_setfield(L, -2, "getworkspace");
    lua_pushcfunction(L, l_screen_size, "ScreenSize");
    lua_setfield(L, -2, "ScreenSize");
    lua_pushcfunction(L, l_screen_center, "ScreenCenter");
    lua_setfield(L, -2, "ScreenCenter");
    lua_pushcfunction(L, l_notify, "Notify");
    lua_setfield(L, -2, "Notify");
    lua_pushcfunction(L, l_color, "Color");
    lua_setfield(L, -2, "Color");
    lua_pushstring(L, LARP_LUAU_VERSION); lua_setfield(L, -2, "Version");
    lua_setglobal(L, "msd");

    lua_pushcfunction(L, l_request, "request");
    lua_setglobal(L, "request");
    lua_pushcfunction(L, l_writefile, "writefile");
    lua_setglobal(L, "writefile");
    lua_pushcfunction(L, l_readfile, "readfile");
    lua_setglobal(L, "readfile");
    lua_pushcfunction(L, l_isfile, "isfile");
    lua_setglobal(L, "isfile");
    lua_pushcfunction(L, l_makefolder, "makefolder");
    lua_setglobal(L, "makefolder");
    lua_pushcfunction(L, l_removefile, "removefile");
    lua_setglobal(L, "removefile");
    lua_pushcfunction(L, l_getworkspace, "getworkspace");
    lua_setglobal(L, "getworkspace");
}

void l_interrupt(lua_State* L, int gc) {
    if (gc >= 0) return;
}

}

LARP_LUAU_EXPORT int larpluau_ready() { return g_ready.load() ? 1 : 0; }

LARP_LUAU_EXPORT int larpluau_init(const char* scriptsDirP, const char* workspaceDirP, int screenW, int screenH, float scale) {
    std::lock_guard<std::mutex> g(g_stateLock);
    if (g_state) { lua_close(g_state); g_state = nullptr; }
    g_state = lua_newstate(l_alloc, nullptr);
    if (!g_state) return -1;
    luaL_openlibs(g_state);
    registerGlobals(g_state);
    lua_callbacks(g_state)->interrupt = l_interrupt;
    luaL_sandbox(g_state);
    g_thread = lua_newthread(g_state);
    lua_ref(g_state, -1);
    lua_pop(g_state, 1);
    luaL_sandboxthread(g_thread);
    g_scriptsDir = scriptsDirP ? scriptsDirP : "";
    g_workspace = workspaceDirP ? workspaceDirP : "/storage/emulated/0/Larplaucher/Workspace";
    if (!g_workspace.empty()) {
        std::string acc;
        for (size_t i = 0; i < g_workspace.size(); ++i) {
            acc += g_workspace[i];
            if (g_workspace[i] == '/' || i + 1 == g_workspace.size()) mkdir(acc.c_str(), 0775);
        }
    }
    g_screenW = screenW > 0 ? screenW : 1080;
    g_screenH = screenH > 0 ? screenH : 1920;
    g_screenScale = scale > 0 ? scale : 1.0f;
    g_ready.store(true);
    LOGI("larpluau initialized (scripts=%s, workspace=%s, %dx%d)",
        g_scriptsDir.c_str(), g_workspace.c_str(), (int)g_screenW, (int)g_screenH);
    return 0;
}

LARP_LUAU_EXPORT int larpluau_run(const char* code, const char* name) {
    if (!g_ready.load() || !g_state) return -1;
    std::lock_guard<std::mutex> g(g_stateLock);
    if (!g_state) return -1;
    std::string src = code ? code : "";
    std::string chunk = name ? name : "=chunk";
    size_t bclen = 0;
    char* bc = luau_compile(src.c_str(), src.size(), nullptr, &bclen);
    if (!bc) { LOGE("luau compile failed"); return -2; }
    lua_State* co = lua_newthread(g_thread);
    int ref = lua_ref(g_thread, -1);
    lua_pop(g_thread, 1);
    int rc = luau_load(co, chunk.c_str(), bc, bclen, 0);
    free(bc);
    if (rc != 0) {
        const char* err = lua_tostring(co, -1);
        LOGE("luau load: %s", err ? err : "?");
        larp_log("E", err ? err : "load failed");
        lua_unref(g_thread, ref);
        return -3;
    }
    int st = lua_resume(co, nullptr, 0);
    if (st != LUA_OK && st != LUA_YIELD) {
        const char* err = lua_tostring(co, -1);
        LOGE("luau run: %s", err ? err : "?");
        larp_log("E", err ? err : "run failed");
    }
    lua_unref(g_thread, ref);
    return 0;
}

LARP_LUAU_EXPORT int larpluau_load_file(const char* path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    std::string code;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) code.append(buf, n);
    close(fd);
    const char* slash = strrchr(path, '/');
    return larpluau_run(code.c_str(), slash ? slash + 1 : path);
}

LARP_LUAU_EXPORT void larpluau_tick(float dt, int screenW, int screenH) {
    if (!g_ready.load() || !g_state) return;
    if (screenW > 0) g_screenW = screenW;
    if (screenH > 0) g_screenH = screenH;
    std::lock_guard<std::mutex> g(g_stateLock);
    if (!g_state) return;
    lua_getglobal(g_state, "OnUpdate");
    if (lua_isfunction(g_state, -1)) {
        lua_pushnumber(g_state, dt);
        if (lua_pcall(g_state, 1, 0, 0) != LUA_OK) {
            const char* err = lua_tostring(g_state, -1);
            LOGE("OnUpdate: %s", err ? err : "?");
            larp_log("E", err ? err : "OnUpdate failed");
            lua_pop(g_state, 1);
        }
    } else lua_pop(g_state, 1);
    lua_getglobal(g_state, "DrawImGui");
    if (lua_isfunction(g_state, -1)) {
        lua_pushnumber(g_state, (lua_Number)g_screenW);
        lua_pushnumber(g_state, (lua_Number)g_screenH);
        if (lua_pcall(g_state, 2, 0, 0) != LUA_OK) {
            const char* err = lua_tostring(g_state, -1);
            LOGE("DrawImGui: %s", err ? err : "?");
            larp_log("E", err ? err : "DrawImGui failed");
            lua_pop(g_state, 1);
        }
    } else lua_pop(g_state, 1);
}

LARP_LUAU_EXPORT void larpluau_shutdown() {
    std::lock_guard<std::mutex> g(g_stateLock);
    if (g_state) { lua_close(g_state); g_state = nullptr; }
    g_ready.store(false);
    LOGI("larpluau shutdown");
}

LARP_LUAU_EXPORT int larpluau_get_screen(int* w, int* h) {
    if (w) *w = (int)g_screenW;
    if (h) *h = (int)g_screenH;
    return 0;
}

LARP_LUAU_EXPORT const char* larpluau_get_workspace() {
    return g_workspace.empty() ? "/storage/emulated/0/Larplaucher/Workspace" : g_workspace.c_str();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLuauInit(JNIEnv* env, jclass, jstring scripts, jstring workspace, jint w, jint h, jfloat scale) {
    const char* s = env->GetStringUTFChars(scripts, nullptr);
    const char* ws = env->GetStringUTFChars(workspace, nullptr);
    int rc = larpluau_init(s, ws, w, h, scale);
    env->ReleaseStringUTFChars(scripts, s);
    env->ReleaseStringUTFChars(workspace, ws);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLuauRun(JNIEnv* env, jclass, jstring code, jstring name) {
    const char* c = env->GetStringUTFChars(code, nullptr);
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    int rc = larpluau_run(c, n);
    env->ReleaseStringUTFChars(code, c);
    if (n) env->ReleaseStringUTFChars(name, n);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLuauLoadFile(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    int rc = larpluau_load_file(p);
    env->ReleaseStringUTFChars(path, p);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLuauTick(JNIEnv*, jclass, jfloat dt, jint w, jint h) {
    larpluau_tick(dt, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLuauShutdown(JNIEnv*, jclass) {
    larpluau_shutdown();
}
