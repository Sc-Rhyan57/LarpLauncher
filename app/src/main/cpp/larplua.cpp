#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <netdb.h>
#include <unistd.h>
#include <pthread.h>
#include <semaphore.h>
#include <dirent.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
}

#define LARP_LUA_TAG "LarpLua"
#define LARP_LUA_EXPORT extern "C" __attribute__((visibility("default")))
#define LARP_LUA_VERSION "1.0"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LARP_LUA_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LARP_LUA_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LARP_LUA_TAG, __VA_ARGS__)

namespace {

using DobbyHookFn = int (*)(void*, void*, void**);

struct Il2Api {
    void* domainGet = nullptr;
    void* domainGetAssemblies = nullptr;
    void* assemblyGetImage = nullptr;
    void* imageGetName = nullptr;
    void* classFromName = nullptr;
    void* classGetName = nullptr;
    void* classGetNamespace = nullptr;
    void* classGetMethods = nullptr;
    void* classGetFields = nullptr;
    void* classGetFieldFromName = nullptr;
    void* methodGetName = nullptr;
    void* methodGetParamCount = nullptr;
    void* methodGetFlags = nullptr;
    void* methodGetParam = nullptr;
    void* methodGetParamName = nullptr;
    void* methodGetReturnType = nullptr;
    void* fieldGetName = nullptr;
    void* fieldGetOffset = nullptr;
    void* fieldStaticGet = nullptr;
    void* fieldStaticSet = nullptr;
    void* stringNew = nullptr;
    void* stringChars = nullptr;
    void* stringLength = nullptr;
    void* threadAttach = nullptr;
    void* threadCurrent = nullptr;
    void* threadDetach = nullptr;
    void* objectNew = nullptr;
    void* runtimeInvoke = nullptr;
    void* typeGetName = nullptr;
    void* free = nullptr;
    void* domain = nullptr;
    uintptr_t moduleBase = 0;
    uintptr_t moduleEnd = 0;
};

Il2Api g_il2;
void* g_il2cppHandle = nullptr;
void* g_dobbyHandle = nullptr;
DobbyHookFn g_dobbyHook = nullptr;

struct HookCtx {
    void* target = nullptr;
    void* original = nullptr;
    lua_State* L = nullptr;
    int ref = LUA_NOREF;
    std::string ret;
    std::vector<std::string> args;
    bool enabled = true;
};

std::mutex g_hooksLock;
std::map<void*, HookCtx*> g_hooks;

std::mutex g_stateLock;
lua_State* g_state = nullptr;
std::atomic<bool> g_ready{false};

pthread_key_t g_ctxKey;
pthread_once_t g_ctxOnce = PTHREAD_ONCE_INIT;

struct FrameCtx {
    HookCtx* hook = nullptr;
    unsigned long long x[8];
    double d[8];
};

void ensureCtxKey() {
    pthread_key_create(&g_ctxKey, nullptr);
}

FrameCtx* frameCtx() {
    pthread_once(&g_ctxOnce, ensureCtxKey);
    auto p = static_cast<FrameCtx*>(pthread_getspecific(g_ctxKey));
    if (!p) {
        p = new FrameCtx();
        memset(p, 0, sizeof(*p));
        pthread_setspecific(g_ctxKey, p);
    }
    return p;
}

std::string readStringFromLua(lua_State* L, int idx) {
    size_t n = 0;
    const char* s = luaL_tolstring(L, idx, &n);
    std::string out(s, n);
    lua_pop(L, 1);
    return out;
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

int larp_lua_atpanic(lua_State* L) {
    const char* msg = lua_tostring(L, -1);
    LOGE("lua panic: %s", msg ? msg : "?");
    return 0;
}

void larp_lua_log(const char* level, const char* msg) {
    int prio = ANDROID_LOG_INFO;
    if (level[0] == 'W') prio = ANDROID_LOG_WARN;
    else if (level[0] == 'E') prio = ANDROID_LOG_ERROR;
    else if (level[0] == 'D') prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, LARP_LUA_TAG, "%s", msg);
    if (g_il2.stringNew && g_il2.domain) {
        typedef void* (*StringNewFn)(const char*);
        auto stringNew = reinterpret_cast<StringNewFn>(g_il2.stringNew);
        std::string tagged = std::string("[lua] ") + msg;
        void* str = stringNew(tagged.c_str());
        if (str) {
            auto classFromName = reinterpret_cast<void* (*)(void*, const char*, const char*)>(g_il2.classFromName);
            if (classFromName) {
                void* debugClass = nullptr;
                typedef void** (*AsmsFn)(void*, size_t*);
                typedef void* (*ImgFn)(void*);
                typedef void* (*ClsFn)(void*, const char*, const char*);
                auto domGet = reinterpret_cast<void* (*)()>(g_il2.domainGet);
                auto asms = reinterpret_cast<AsmsFn>(g_il2.domainGetAssemblies);
                auto img = reinterpret_cast<ImgFn>(g_il2.assemblyGetImage);
                auto cls = reinterpret_cast<ClsFn>(g_il2.classFromName);
                void* dom = g_il2.domain ? g_il2.domain : (domGet ? domGet() : nullptr);
                size_t n = 0;
                if (dom && asms) {
                    void** arr = asms(dom, &n);
                    if (arr) {
                        for (size_t i = 0; i < n; ++i) {
                            void* k = cls(img(arr[i]), "UnityEngine", "Debug");
                            if (k) { debugClass = k; break; }
                        }
                    }
                }
                if (debugClass) {
                    auto mfn = reinterpret_cast<void* (*)(void*, const char*, int)>(dlsym(g_il2cppHandle, "il2cpp_class_get_method_from_name"));
                    void* logMethod = mfn ? mfn(debugClass, "Log", 1) : nullptr;
                    if (logMethod) {
                        auto inv = dlsym(g_il2cppHandle, "il2cpp_runtime_invoke");
                        if (inv) {
                            void* exc = nullptr;
                            reinterpret_cast<void* (*)(void*, void*, void**, void**)>(inv)(logMethod, nullptr, &str, &exc);
                        }
                    }
                }
            }
        }
    }
}

const char* luaTypeText(int t) {
    switch (t) {
        case LUA_TNIL: return "nil";
        case LUA_TBOOLEAN: return "boolean";
        case LUA_TNUMBER: return "number";
        case LUA_TSTRING: return "string";
        case LUA_TTABLE: return "table";
        case LUA_TFUNCTION: return "function";
        case LUA_TUSERDATA: return "userdata";
        case LUA_TTHREAD: return "thread";
        default: return "unknown";
    }
}

void dumpLuaValue(lua_State* L, int idx, std::string& out) {
    idx = lua_absindex(L, idx);
    int t = lua_type(L, idx);
    switch (t) {
        case LUA_TNIL: out += "nil"; break;
        case LUA_TBOOLEAN: out += lua_toboolean(L, idx) ? "true" : "false"; break;
        case LUA_TNUMBER: {
            if (lua_isinteger(L, idx)) out += std::to_string(lua_tointeger(L, idx));
            else {
                char buf[64];
                snprintf(buf, sizeof(buf), "%.14g", lua_tonumber(L, idx));
                out += buf;
            }
            break;
        }
        case LUA_TSTRING: {
            size_t n = 0;
            const char* s = lua_tolstring(L, idx, &n);
            out.append(s, n);
            break;
        }
        default: {
            char buf[64];
            snprintf(buf, sizeof(buf), "%s:%p", luaTypeText(t), lua_topointer(L, idx));
            out += buf;
            break;
        }
    }
}

std::string formatLuaArgs(lua_State* L, int from) {
    std::string out;
    int n = lua_gettop(L);
    for (int i = from; i <= n; ++i) {
        if (i > from) out += "\t";
        dumpLuaValue(L, i, out);
    }
    return out;
}

std::string formatLuaPrintf(lua_State* L) {
    std::string fmt = luaToStdStr(L, 1);
    std::string out;
    int ai = 2;
    int top = lua_gettop(L);
    for (size_t i = 0; i < fmt.size(); ++i) {
        char c = fmt[i];
        if (c != '%') { out += c; continue; }
        ++i;
        if (i >= fmt.size()) break;
        if (fmt[i] == '%') { out += '%'; continue; }
        size_t st = i;
        while (i < fmt.size() && std::string("-+ #0123456789.").find(fmt[i]) != std::string::npos) ++i;
        std::string spec = fmt.substr(st, i - st);
        if (i >= fmt.size()) break;
        char t = fmt[i];
        std::string txt;
        int width = 0;
        bool left = spec.find('-') != std::string::npos;
        bool zero = !spec.empty() && spec[0] == '0';
        for (char ch : spec) if (ch >= '0' && ch <= '9' || ch == '-') { if (ch == '-') continue; width = width * 10 + (ch - '0'); }
        if (t == 's') {
            txt = ai <= top ? luaToStdStr(L, ai++) : "";
        } else if (t == 'd' || t == 'i' || t == 'u') {
            long long v = 0;
            if (ai <= top) {
                if (lua_isinteger(L, ai)) v = lua_tointeger(L, ai);
                else if (lua_isnumber(L, ai)) v = (long long)lua_tonumber(L, ai);
                ++ai;
            }
            txt = std::to_string(v);
        } else if (t == 'x' || t == 'X') {
            unsigned long long v = 0;
            if (ai <= top) {
                if (lua_isinteger(L, ai)) v = (unsigned long long)lua_tointeger(L, ai);
                else if (lua_isnumber(L, ai)) v = (unsigned long long)lua_tonumber(L, ai);
                else if (lua_isstring(L, ai)) v = strtoull(lua_tostring(L, ai), nullptr, 0);
                else if (lua_isuserdata(L, ai)) {
                    void** p = static_cast<void**>(lua_touserdata(L, ai));
                    if (p) v = (unsigned long long)*p;
                }
                ++ai;
            }
            char buf[32];
            snprintf(buf, sizeof(buf), t == 'X' ? "%llX" : "%llx", v);
            txt = buf;
        } else if (t == 'p') {
            unsigned long long v = 0;
            if (ai <= top) {
                if (lua_isnumber(L, ai)) v = (unsigned long long)lua_tonumber(L, ai);
                else if (lua_isstring(L, ai)) v = strtoull(lua_tostring(L, ai), nullptr, 0);
                else if (lua_isuserdata(L, ai)) {
                    void** p = static_cast<void**>(lua_touserdata(L, ai));
                    if (p) v = (unsigned long long)*p;
                }
                ++ai;
            }
            char buf[32];
            snprintf(buf, sizeof(buf), "0x%llx", v);
            txt = buf;
        } else if (t == 'f' || t == 'F' || t == 'g' || t == 'G') {
            double v = 0;
            if (ai <= top) {
                if (lua_isnumber(L, ai)) v = lua_tonumber(L, ai);
                ++ai;
            }
            char buf[64];
            snprintf(buf, sizeof(buf), "%g", v);
            txt = buf;
        } else if (t == 'c') {
            int v = ai <= top && lua_isnumber(L, ai) ? (int)lua_tointeger(L, ai) : 32;
            if (ai <= top) ++ai;
            txt = std::string(1, (char)v);
        } else if (t == 'b') {
            int v = ai <= top && lua_isboolean(L, ai) ? lua_toboolean(L, ai) : 0;
            if (ai <= top) ++ai;
            txt = v ? "true" : "false";
        } else {
            txt = std::string("%") + spec + t;
        }
        if (width > 0 && (int)txt.size() < width) {
            if (left) txt.append(width - txt.size(), ' ');
            else txt = std::string(width - txt.size(), zero ? '0' : ' ') + txt;
        }
        out += txt;
    }
    return out;
}

struct LuaPtr {
    unsigned long long value;
};

struct LuaClass {
    void* handle;
    std::string name;
    std::string ns;
};

struct LuaMethod {
    void* info;
    void* function;
    std::string name;
    int argc;
    bool isStatic;
    std::string ret;
    std::vector<std::string> args;
};

struct LuaHook {
    void* target;
    void* original;
    std::string ret;
    std::vector<std::string> args;
    int ref;
    bool enabled;
};

const char* kPtrMeta = "LarpLua.Ptr";
const char* kClassMeta = "LarpLua.Class";
const char* kMethodMeta = "LarpLua.Method";
const char* kHookMeta = "LarpLua.Hook";

LuaPtr* toPtr(lua_State* L, int idx) {
    return static_cast<LuaPtr*>(luaL_testudata(L, idx, kPtrMeta));
}

LuaPtr* checkPtr(lua_State* L, int idx) {
    return static_cast<LuaPtr*>(luaL_checkudata(L, idx, kPtrMeta));
}

LuaClass* checkClass(lua_State* L, int idx) {
    return static_cast<LuaClass*>(luaL_checkudata(L, idx, kClassMeta));
}

LuaMethod* checkMethod(lua_State* L, int idx) {
    return static_cast<LuaMethod*>(luaL_checkudata(L, idx, kMethodMeta));
}

LuaHook* checkHook(lua_State* L, int idx) {
    return static_cast<LuaHook*>(luaL_checkudata(L, idx, kHookMeta));
}

unsigned long long toAddr(lua_State* L, int idx) {
    idx = lua_absindex(L, idx);
    if (lua_isnumber(L, idx)) return (unsigned long long)lua_tonumber(L, idx);
    if (lua_isstring(L, idx)) return strtoull(lua_tostring(L, idx), nullptr, 0);
    if (lua_isuserdata(L, idx)) {
        if (auto p = toPtr(L, idx)) return p->value;
        if (auto c = static_cast<LuaClass*>(luaL_testudata(L, idx, kClassMeta))) return (unsigned long long)c->handle;
        if (auto m = static_cast<LuaMethod*>(luaL_testudata(L, idx, kMethodMeta))) return (unsigned long long)m->function;
        if (auto h = static_cast<LuaHook*>(luaL_testudata(L, idx, kHookMeta))) return (unsigned long long)h->target;
    }
    return 0;
}

void pushPtr(lua_State* L, unsigned long long v) {
    auto p = static_cast<LuaPtr*>(lua_newuserdatauv(L, sizeof(LuaPtr), 0));
    p->value = v;
    luaL_setmetatable(L, kPtrMeta);
}

int l_ptr_index(lua_State* L) {
    auto p = checkPtr(L, 1);
    std::string key = luaToStdStr(L, 2);
    if (key == "address") { lua_pushnumber(L, (lua_Number)p->value); return 1; }
    if (key == "value") { lua_pushnumber(L, (lua_Number)p->value); return 1; }
    if (key == "is_null") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); lua_pushboolean(L, p->value == 0); return 1; }); return 1; }
    if (key == "eq") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); unsigned long long o = toAddr(L, 2); lua_pushboolean(L, p->value == o); return 1; }); return 1; }
    if (key == "add") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); lua_Number n = luaL_checknumber(L, 2); pushPtr(L, p->value + (unsigned long long)n); return 1; }); return 1; }
    if (key == "sub") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); lua_Number n = luaL_checknumber(L, 2); pushPtr(L, p->value - (unsigned long long)n); return 1; }); return 1; }
    if (key == "read_int") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushinteger(L, *(int*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_uint") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushinteger(L, *(unsigned int*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_long") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushinteger(L, *(long long*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_short") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushinteger(L, *(short*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_byte") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushinteger(L, *(unsigned char*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_bool") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); lua_pushboolean(L, *(unsigned char*)(p->value + off) != 0); return 1; }); return 1; }
    if (key == "read_float") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); int b = *(int*)(p->value + off); float f; memcpy(&f, &b, 4); lua_pushnumber(L, f); return 1; }); return 1; }
    if (key == "read_double") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); long long b = *(long long*)(p->value + off); double f; memcpy(&f, &b, 8); lua_pushnumber(L, f); return 1; }); return 1; }
    if (key == "read_ptr") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); pushPtr(L, *(unsigned long long*)(p->value + off)); return 1; }); return 1; }
    if (key == "read_string") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2);
        unsigned long long addr = *(unsigned long long*)(p->value + off);
        if (!addr || !g_il2.stringChars || !g_il2.stringLength) { lua_pushnil(L); return 1; }
        typedef int (*LenFn)(void*);
        typedef void* (*CharsFn)(void*);
        int n = reinterpret_cast<LenFn>(g_il2.stringLength)((void*)addr);
        if (n <= 0) { lua_pushstring(L, ""); return 1; }
        void* chars = reinterpret_cast<CharsFn>(g_il2.stringChars)((void*)addr);
        if (!chars) { lua_pushnil(L); return 1; }
        std::string s;
        const char16_t* p16 = static_cast<char16_t*>(chars);
        for (int i = 0; i < n; ++i) {
            unsigned int c = p16[i];
            if (c < 0x80) s += (char)c;
            else if (c < 0x800) { s += (char)(0xC0 | (c >> 6)); s += (char)(0x80 | (c & 0x3F)); }
            else { s += (char)(0xE0 | (c >> 12)); s += (char)(0x80 | ((c >> 6) & 0x3F)); s += (char)(0x80 | (c & 0x3F)); }
        }
        pushStdString(L, s); return 1; }); return 1; }
    if (key == "write_int") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); int v = (int)luaL_checkinteger(L, 3); *(int*)(p->value + off) = v; return 0; }); return 1; }
    if (key == "write_long") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); long long v = luaL_checkinteger(L, 3); *(long long*)(p->value + off) = v; return 0; }); return 1; }
    if (key == "write_short") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); short v = (short)luaL_checkinteger(L, 3); *(short*)(p->value + off) = v; return 0; }); return 1; }
    if (key == "write_byte") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); unsigned char v = (unsigned char)luaL_checkinteger(L, 3); *(unsigned char*)(p->value + off) = v; return 0; }); return 1; }
    if (key == "write_bool") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); unsigned char v = lua_toboolean(L, 3) ? 1 : 0; *(unsigned char*)(p->value + off) = v; return 0; }); return 1; }
    if (key == "write_float") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); float v = (float)luaL_checknumber(L, 3); int b; memcpy(&b, &v, 4); *(int*)(p->value + off) = b; return 0; }); return 1; }
    if (key == "write_double") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); double v = luaL_checknumber(L, 3); long long b; memcpy(&b, &v, 8); *(long long*)(p->value + off) = b; return 0; }); return 1; }
    if (key == "write_ptr") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto p = checkPtr(L, 1); long long off = luaL_checkinteger(L, 2); unsigned long long v = toAddr(L, 3); *(unsigned long long*)(p->value + off) = v; return 0; }); return 1; }
    return luaL_error(L, "LarpLua.Ptr: unknown field '%s'", key.c_str());
}

int l_ptr_tostring(lua_State* L) {
    auto p = checkPtr(L, 1);
    char buf[32];
    snprintf(buf, sizeof(buf), "0x%llx", p->value);
    lua_pushstring(L, buf);
    return 1;
}

void installPtrMeta(lua_State* L) {
    if (luaL_newmetatable(L, kPtrMeta)) {
        lua_pushcfunction(L, l_ptr_index); lua_setfield(L, -2, "__index");
        lua_pushcfunction(L, l_ptr_tostring); lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto a = checkPtr(L, 1); auto b = toPtr(L, 2);
            lua_pushboolean(L, a->value == (b ? b->value : toAddr(L, 2)));
            return 1; }); lua_setfield(L, -2, "__eq");
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto p = checkPtr(L, 1); lua_pushinteger(L, (lua_Integer)p->value);
            return 1; }); lua_setfield(L, -2, "__len");
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto a = checkPtr(L, 1); lua_Number b = luaL_checknumber(L, 2);
            pushPtr(L, a->value + (unsigned long long)b); return 1; }); lua_setfield(L, -2, "__add");
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto a = checkPtr(L, 1); lua_Number b = luaL_checknumber(L, 2);
            pushPtr(L, a->value - (unsigned long long)b); return 1; }); lua_setfield(L, -2, "__sub");
    }
    lua_pop(L, 1);
}

std::string normType(const std::string& t) {
    std::string s = t;
    for (auto& c : s) c = (char)tolower(c);
    if (s == "pointer" || s == "object" || s == "this" || s == "intptr" || s == "uintptr" || s == "handle" || s == "il2cppstring" || s == "string" || s == "str") return s == "string" || s == "str" || s == "il2cppstring" ? "string" : "ptr";
    if (s == "int32" || s == "i32") return "int";
    if (s == "uint32" || s == "u32") return "uint";
    if (s == "int64" || s == "i64") return "long";
    if (s == "uint64" || s == "u64") return "ulong";
    if (s == "single" || s == "f32") return "float";
    if (s == "f64") return "double";
    if (s == "boolean") return "bool";
    return s;
}

bool isFloatType(const std::string& t) { return t == "float" || t == "double"; }

void parseSig(lua_State* L, int idx, LuaMethod& m) {
    if (idx <= 0 || !lua_istable(L, idx)) {
        m.ret = "void";
        int n = m.argc + (m.isStatic ? 0 : 1);
        m.args.assign(n, "ptr");
        if (n == 0) m.ret = "ptr";
        return;
    }
    lua_getfield(L, idx, "ret");
    m.ret = lua_isstring(L, -1) ? normType(luaToStdStr(L, -1)) : "void";
    lua_pop(L, 1);
    m.args.clear();
    lua_getfield(L, idx, "args");
    if (lua_istable(L, -1)) {
        int n = (int)luaL_len(L, -1);
        for (int i = 1; i <= n; ++i) {
            lua_geti(L, -1, i);
            m.args.push_back(lua_isstring(L, -1) ? normType(luaToStdStr(L, -1)) : "ptr");
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);
}

unsigned long long luaToUll(lua_State* L, int idx, const std::string& type) {
    idx = lua_absindex(L, idx);
    if (type == "bool") return lua_toboolean(L, idx) ? 1ULL : 0ULL;
    if (type == "int" || type == "uint" || type == "short" || type == "ushort" || type == "byte" || type == "sbyte" || type == "long" || type == "ulong") {
        if (lua_isinteger(L, idx)) return (unsigned long long)lua_tointeger(L, idx);
        if (lua_isnumber(L, idx)) return (unsigned long long)lua_tonumber(L, idx);
        return 0;
    }
    return toAddr(L, idx);
}

double luaToDouble(lua_State* L, int idx) {
    if (lua_isnumber(L, idx)) return lua_tonumber(L, idx);
    return 0.0;
}

void pushFromVal(lua_State* L, const std::string& type, unsigned long long x, double d) {
    std::string t = normType(type);
    if (t == "void") { lua_pushnil(L); return; }
    if (t == "bool") { lua_pushboolean(L, (x & 0xFF) != 0); return; }
    if (t == "int") { lua_pushinteger(L, (lua_Integer)(int)(unsigned int)x); return; }
    if (t == "uint") { lua_pushinteger(L, (lua_Integer)(unsigned int)x); return; }
    if (t == "short") { lua_pushinteger(L, (short)(unsigned short)x); return; }
    if (t == "ushort") { lua_pushinteger(L, (unsigned short)x); return; }
    if (t == "byte") { lua_pushinteger(L, (unsigned char)x); return; }
    if (t == "sbyte") { lua_pushinteger(L, (signed char)x); return; }
    if (t == "long") { lua_pushinteger(L, (lua_Integer)(long long)x); return; }
    if (t == "ulong") { lua_pushnumber(L, (lua_Number)x); return; }
    if (t == "float") { int b = (int)(unsigned int)(*((long long*)&d)); float f; memcpy(&f, &b, 4); lua_pushnumber(L, f); return; }
    if (t == "double") { lua_pushnumber(L, d); return; }
    if (t == "string") {
        if (!x || !g_il2.stringLength || !g_il2.stringChars) { lua_pushnil(L); return; }
        typedef int (*LenFn)(void*);
        typedef void* (*CharsFn)(void*);
        int n = reinterpret_cast<LenFn>(g_il2.stringLength)((void*)x);
        if (n <= 0) { lua_pushstring(L, ""); return; }
        void* chars = reinterpret_cast<CharsFn>(g_il2.stringChars)((void*)x);
        if (!chars) { lua_pushnil(L); return; }
        std::string s;
        const char16_t* p16 = static_cast<char16_t*>(chars);
        for (int i = 0; i < n; ++i) {
            unsigned int c = p16[i];
            if (c < 0x80) s += (char)c;
            else if (c < 0x800) { s += (char)(0xC0 | (c >> 6)); s += (char)(0x80 | (c & 0x3F)); }
            else { s += (char)(0xE0 | (c >> 12)); s += (char)(0x80 | ((c >> 6) & 0x3F)); s += (char)(0x80 | (c & 0x3F)); }
        }
        pushStdString(L, s);
        return;
    }
    if (x == 0) lua_pushnil(L);
    else pushPtr(L, x);
}

typedef unsigned long long (*RegThunk)(unsigned long long, unsigned long long, unsigned long long, unsigned long long,
    unsigned long long, unsigned long long, unsigned long long, unsigned long long,
    double, double, double, double, double, double, double, double);
typedef double (*RegThunkD)(unsigned long long, unsigned long long, unsigned long long, unsigned long long,
    unsigned long long, unsigned long long, unsigned long long, unsigned long long,
    double, double, double, double, double, double, double, double);

int l_method_call(lua_State* L) {
    auto m = checkMethod(L, 1);
    int top = lua_gettop(L);
    unsigned long long x[8] = {0,0,0,0,0,0,0,0};
    double d[8] = {0,0,0,0,0,0,0,0};
    int ni = 0, nf = 0;
    int argBase = 2;
    for (size_t i = 0; i < m->args.size() && i < 8; ++i) {
        std::string t = normType(m->args[i]);
        if (isFloatType(t)) { d[nf++] = luaToDouble(L, argBase + (int)i); }
        else { x[ni++] = luaToUll(L, argBase + (int)i, t); }
    }
    if (ni < 8) x[ni++] = (unsigned long long)(unsigned long long)m->info;
    if (isFloatType(m->ret)) {
        auto fn = reinterpret_cast<RegThunkD>(m->function);
        double rd = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
        pushFromVal(L, m->ret, 0, rd);
    } else {
        auto fn = reinterpret_cast<RegThunk>(m->function);
        unsigned long long rx = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
        pushFromVal(L, m->ret, rx, 0);
    }
    return 1;
}

int l_method_cast(lua_State* L) {
    auto m = checkMethod(L, 1);
    auto* copy = static_cast<LuaMethod*>(lua_newuserdatauv(L, sizeof(LuaMethod), 0));
    new (copy) LuaMethod(*m);
    parseSig(L, 2, *copy);
    luaL_setmetatable(L, kMethodMeta);
    return 1;
}

int l_method_index(lua_State* L) {
    auto m = checkMethod(L, 1);
    std::string key = luaToStdStr(L, 2);
    if (key == "Info") { pushPtr(L, (unsigned long long)m->info); return 1; }
    if (key == "Function") { pushPtr(L, (unsigned long long)m->function); return 1; }
    if (key == "Name" || key == "name") { pushStdString(L, m->name); return 1; }
    if (key == "Argc" || key == "argc") { lua_pushinteger(L, m->argc); return 1; }
    if (key == "IsStatic" || key == "is_static") { lua_pushboolean(L, m->isStatic); return 1; }
    if (key == "Call") { lua_pushcfunction(L, l_method_call); return 1; }
    if (key == "cast") { lua_pushcfunction(L, l_method_cast); return 1; }
    if (key == "GetAddress") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto m = checkMethod(L, 1); pushPtr(L, (unsigned long long)m->function); return 1; }); return 1; }
    if (key == "GetName") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto m = checkMethod(L, 1); pushStdString(L, m->name); return 1; }); return 1; }
    if (key == "GetArgCount") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto m = checkMethod(L, 1); lua_pushinteger(L, m->argc); return 1; }); return 1; }
    if (key == "GetIsStatic") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto m = checkMethod(L, 1); lua_pushboolean(L, m->isStatic); return 1; }); return 1; }
    if (key == "hook") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto m = checkMethod(L, 1);
        lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.InstallHook");
        lua_pushvalue(L, 1);
        lua_pushvalue(L, 2);
        lua_pushvalue(L, 3);
        lua_call(L, 3, 1);
        return 1; }); return 1; }
    return luaL_error(L, "LarpLua.Method: unknown field '%s'", key.c_str());
}

int l_method_tostring(lua_State* L) {
    auto m = checkMethod(L, 1);
    char buf[128];
    snprintf(buf, sizeof(buf), "Method(%s, argc=%d, %s, 0x%llx)",
        m->name.c_str(), m->argc, m->isStatic ? "static" : "instance",
        (unsigned long long)m->function);
    lua_pushstring(L, buf);
    return 1;
}

int l_method_gc(lua_State* L) {
    auto m = checkMethod(L, 1);
    m->~LuaMethod();
    return 0;
}

void installMethodMeta(lua_State* L) {
    if (luaL_newmetatable(L, kMethodMeta)) {
        lua_pushcfunction(L, l_method_index); lua_setfield(L, -2, "__index");
        lua_pushcfunction(L, l_method_tostring); lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, l_method_call); lua_setfield(L, -2, "__call");
        lua_pushcfunction(L, l_method_gc); lua_setfield(L, -2, "__gc");
    }
    lua_pop(L, 1);
}

void* il2FindClass(const std::string& ns, const std::string& name) {
    if (!g_il2.classFromName || !g_il2.domainGet || !g_il2.domainGetAssemblies || !g_il2.assemblyGetImage) return nullptr;
    if (!g_il2.domain) {
        auto domGet = reinterpret_cast<void* (*)()>(g_il2.domainGet);
        if (domGet) g_il2.domain = domGet();
    }
    if (!g_il2.domain) return nullptr;
    typedef void** (*AsmsFn)(void*, size_t*);
    typedef void* (*ImgFn)(void*);
    typedef void* (*ClsFn)(void*, const char*, const char*);
    auto asms = reinterpret_cast<AsmsFn>(g_il2.domainGetAssemblies);
    auto img = reinterpret_cast<ImgFn>(g_il2.assemblyGetImage);
    auto cls = reinterpret_cast<ClsFn>(g_il2.classFromName);
    size_t n = 0;
    void** arr = asms(g_il2.domain, &n);
    if (!arr) return nullptr;
    for (size_t i = 0; i < n; ++i) {
        void* k = cls(img(arr[i]), ns.c_str(), name.c_str());
        if (k) return k;
    }
    return nullptr;
}

int fillMethodInfo(LuaMethod& m, void* methodInfo) {
    if (!methodInfo) return 0;
    m.info = methodInfo;
    m.function = *(void**)methodInfo;
    typedef const char* (*NameFn)(void*);
    typedef int (*CountFn)(void*);
    typedef int (*FlagsFn)(void*, unsigned int*);
    auto nameFn = reinterpret_cast<NameFn>(g_il2.methodGetName);
    auto countFn = reinterpret_cast<CountFn>(g_il2.methodGetParamCount);
    auto flagsFn = reinterpret_cast<FlagsFn>(g_il2.methodGetFlags);
    if (nameFn) m.name = nameFn(methodInfo); else m.name = "";
    if (countFn) m.argc = countFn(methodInfo); else m.argc = 0;
    if (flagsFn) m.isStatic = (flagsFn(methodInfo, nullptr) & 0x10) != 0; else m.isStatic = false;
    return 1;
}

int l_class_index(lua_State* L) {
    auto c = checkClass(L, 1);
    std::string key = luaToStdStr(L, 2);
    if (key == "Handle" || key == "handle") { pushPtr(L, (unsigned long long)c->handle); return 1; }
    if (key == "Name" || key == "name") { pushStdString(L, c->name); return 1; }
    if (key == "Namespace" || key == "namespace") { pushStdString(L, c->ns); return 1; }
    if (key == "GetName") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto c = checkClass(L, 1); pushStdString(L, c->name); return 1; }); return 1; }
    if (key == "GetMethod") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            std::string name = luaToStdStr(L, 2);
            int argc = -1;
            if (lua_gettop(L) >= 3 && lua_isnumber(L, 3)) argc = (int)lua_tointeger(L, 3);
            void* mi = nullptr;
            if (argc >= 0 && g_il2.classFromName) {
                typedef void* (*Fn)(void*, const char*, int);
                auto fn = reinterpret_cast<Fn>(dlsym(g_il2cppHandle, "il2cpp_class_get_method_from_name"));
                if (fn) mi = fn(c->handle, name.c_str(), argc);
            } else {
                typedef void* (*GetMethodsFn)(void*, void**);
                typedef const char* (*NameFn)(void*);
                typedef int (*CountFn)(void*);
                auto gm = reinterpret_cast<GetMethodsFn>(g_il2.classGetMethods);
                auto gn = reinterpret_cast<NameFn>(g_il2.methodGetName);
                auto gc = reinterpret_cast<CountFn>(g_il2.methodGetParamCount);
                if (gm && gn) {
                    void* iter = nullptr;
                    void* m;
                    while ((m = gm(c->handle, &iter))) {
                        const char* n = gn(m);
                        if (n && name == n) {
                            if (argc < 0 || (gc && gc(m) == argc)) { mi = m; break; }
                            if (!gc) { mi = m; break; }
                        }
                    }
                }
            }
            if (!mi) { lua_pushnil(L); return 1; }
            auto* lm = static_cast<LuaMethod*>(lua_newuserdatauv(L, sizeof(LuaMethod), 0));
            new (lm) LuaMethod();
            fillMethodInfo(*lm, mi);
            lm->ret = "ptr";
            int n = lm->argc + (lm->isStatic ? 0 : 1);
            lm->args.assign(n, "ptr");
            luaL_setmetatable(L, kMethodMeta);
            return 1;
        });
        return 1;
    }
    if (key == "Methods") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            typedef void* (*GetMethodsFn)(void*, void**);
            typedef const char* (*NameFn)(void*);
            typedef int (*CountFn)(void*);
            auto gm = reinterpret_cast<GetMethodsFn>(g_il2.classGetMethods);
            auto gn = reinterpret_cast<NameFn>(g_il2.methodGetName);
            auto gc = reinterpret_cast<CountFn>(g_il2.methodGetParamCount);
            if (!gm || !gn) { lua_newtable(L); return 1; }
            lua_newtable(L);
            int i = 1;
            void* iter = nullptr;
            void* m;
            while ((m = gm(c->handle, &iter))) {
                lua_newtable(L);
                lua_pushstring(L, gn(m) ?: ""); lua_setfield(L, -2, "name");
                lua_pushinteger(L, gc ? gc(m) : 0); lua_setfield(L, -2, "argc");
                lua_seti(L, -2, i++);
            }
            return 1;
        });
        return 1;
    }
    if (key == "GetFieldOffset") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            std::string name = luaToStdStr(L, 2);
            if (!g_il2.classGetFieldFromName || !g_il2.fieldGetOffset) { lua_pushnil(L); return 1; }
            typedef void* (*Fn)(void*, const char*);
            typedef size_t (*OffFn)(void*);
            auto ff = reinterpret_cast<Fn>(g_il2.classGetFieldFromName);
            auto off = reinterpret_cast<OffFn>(g_il2.fieldGetOffset);
            void* f = ff(c->handle, name.c_str());
            if (!f) { lua_pushnil(L); return 1; }
            lua_pushinteger(L, (lua_Integer)off(f));
            return 1;
        });
        return 1;
    }
    if (key == "StaticGet") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            std::string name = luaToStdStr(L, 2);
            std::string type = normType(luaToStdStr(L, 3));
            if (!g_il2.classGetFieldFromName || !g_il2.fieldStaticGet) { lua_pushnil(L); return 1; }
            typedef void* (*FF)(void*, const char*);
            typedef void (*GetFn)(void*, void*);
            auto ff = reinterpret_cast<FF>(g_il2.classGetFieldFromName);
            auto get = reinterpret_cast<GetFn>(g_il2.fieldStaticGet);
            void* f = ff(c->handle, name.c_str());
            if (!f) { lua_pushnil(L); return 1; }
            long long buf[2] = {0, 0};
            get(f, buf);
            unsigned long long x = (unsigned long long)buf[0];
            double d; memcpy(&d, &buf[0], 8);
            pushFromVal(L, type, x, d);
            return 1;
        });
        return 1;
    }
    if (key == "StaticSet") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            std::string name = luaToStdStr(L, 2);
            std::string type = normType(luaToStdStr(L, 3));
            if (!g_il2.classGetFieldFromName || !g_il2.fieldStaticSet) { lua_pushboolean(L, 0); return 1; }
            typedef void* (*FF)(void*, const char*);
            typedef void (*SetFn)(void*, void*);
            auto ff = reinterpret_cast<FF>(g_il2.classGetFieldFromName);
            auto set = reinterpret_cast<SetFn>(g_il2.fieldStaticSet);
            void* f = ff(c->handle, name.c_str());
            if (!f) { lua_pushboolean(L, 0); return 1; }
            long long buf[2] = {0, 0};
            if (isFloatType(type)) {
                double v = luaToDouble(L, 4);
                memcpy(buf, &v, 8);
            } else {
                buf[0] = (long long)luaToUll(L, 4, type);
            }
            set(f, buf);
            lua_pushboolean(L, 1);
            return 1;
        });
        return 1;
    }
    if (key == "Hook" || key == "Replace") {
        lua_pushcfunction(L, [](lua_State* L) -> int {
            auto c = checkClass(L, 1);
            lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.InstallHookByName");
            lua_pushvalue(L, 1);
            lua_pushvalue(L, 2);
            lua_pushvalue(L, 3);
            lua_pushvalue(L, 4);
            lua_pushvalue(L, 5);
            lua_call(L, 5, 1);
            return 1;
        });
        return 1;
    }
    return luaL_error(L, "LarpLua.Class: unknown field '%s'", key.c_str());
}

int l_class_tostring(lua_State* L) {
    auto c = checkClass(L, 1);
    char buf[160];
    snprintf(buf, sizeof(buf), "Class(%s%s%s @ 0x%llx)",
        c->ns.c_str(), c->ns.empty() ? "" : ".", c->name.c_str(),
        (unsigned long long)c->handle);
    lua_pushstring(L, buf);
    return 1;
}

int l_class_gc(lua_State* L) {
    auto c = checkClass(L, 1);
    c->~LuaClass();
    return 0;
}

void installClassMeta(lua_State* L) {
    if (luaL_newmetatable(L, kClassMeta)) {
        lua_pushcfunction(L, l_class_index); lua_setfield(L, -2, "__index");
        lua_pushcfunction(L, l_class_tostring); lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, l_class_gc); lua_setfield(L, -2, "__gc");
    }
    lua_pop(L, 1);
}

unsigned long long g_screenW = 1080;
unsigned long long g_screenH = 1920;
float g_screenScale = 1.0f;

constexpr int kMaxHooks = 64;
HookCtx* g_hookByIndex[kMaxHooks] = {};
std::atomic<int> g_nextHookSlot{0};
thread_local HookCtx* g_currentHook = nullptr;

unsigned long long hookDispatch(unsigned long long a0, unsigned long long a1,
    unsigned long long a2, unsigned long long a3, unsigned long long a4,
    unsigned long long a5, unsigned long long a6, unsigned long long a7,
    double d0, double d1, double d2, double d3,
    double d4, double d5, double d6, double d7);

double hookDispatchD(unsigned long long a0, unsigned long long a1,
    unsigned long long a2, unsigned long long a3, unsigned long long a4,
    unsigned long long a5, unsigned long long a6, unsigned long long a7,
    double d0, double d1, double d2, double d3,
    double d4, double d5, double d6, double d7);

#define HOOK_THUNK(N) \
    unsigned long long hookThunk##N(unsigned long long a0, unsigned long long a1, \
        unsigned long long a2, unsigned long long a3, unsigned long long a4, \
        unsigned long long a5, unsigned long long a6, unsigned long long a7, \
        double d0, double d1, double d2, double d3, double d4, double d5, \
        double d6, double d7) { \
        g_currentHook = g_hookByIndex[N]; \
        return hookDispatch(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7); \
    } \
    double hookThunkD##N(unsigned long long a0, unsigned long long a1, \
        unsigned long long a2, unsigned long long a3, unsigned long long a4, \
        unsigned long long a5, unsigned long long a6, unsigned long long a7, \
        double d0, double d1, double d2, double d3, double d4, double d5, \
        double d6, double d7) { \
        g_currentHook = g_hookByIndex[N]; \
        return hookDispatchD(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7); \
    }

HOOK_THUNK(0)  HOOK_THUNK(1)  HOOK_THUNK(2)  HOOK_THUNK(3)
HOOK_THUNK(4)  HOOK_THUNK(5)  HOOK_THUNK(6)  HOOK_THUNK(7)
HOOK_THUNK(8)  HOOK_THUNK(9)  HOOK_THUNK(10) HOOK_THUNK(11)
HOOK_THUNK(12) HOOK_THUNK(13) HOOK_THUNK(14) HOOK_THUNK(15)
HOOK_THUNK(16) HOOK_THUNK(17) HOOK_THUNK(18) HOOK_THUNK(19)
HOOK_THUNK(20) HOOK_THUNK(21) HOOK_THUNK(22) HOOK_THUNK(23)
HOOK_THUNK(24) HOOK_THUNK(25) HOOK_THUNK(26) HOOK_THUNK(27)
HOOK_THUNK(28) HOOK_THUNK(29) HOOK_THUNK(30) HOOK_THUNK(31)
HOOK_THUNK(32) HOOK_THUNK(33) HOOK_THUNK(34) HOOK_THUNK(35)
HOOK_THUNK(36) HOOK_THUNK(37) HOOK_THUNK(38) HOOK_THUNK(39)
HOOK_THUNK(40) HOOK_THUNK(41) HOOK_THUNK(42) HOOK_THUNK(43)
HOOK_THUNK(44) HOOK_THUNK(45) HOOK_THUNK(46) HOOK_THUNK(47)
HOOK_THUNK(48) HOOK_THUNK(49) HOOK_THUNK(50) HOOK_THUNK(51)
HOOK_THUNK(52) HOOK_THUNK(53) HOOK_THUNK(54) HOOK_THUNK(55)
HOOK_THUNK(56) HOOK_THUNK(57) HOOK_THUNK(58) HOOK_THUNK(59)
HOOK_THUNK(60) HOOK_THUNK(61) HOOK_THUNK(62) HOOK_THUNK(63)

void* g_thunks[kMaxHooks] = {
    (void*)hookThunk0,  (void*)hookThunk1,  (void*)hookThunk2,  (void*)hookThunk3,
    (void*)hookThunk4,  (void*)hookThunk5,  (void*)hookThunk6,  (void*)hookThunk7,
    (void*)hookThunk8,  (void*)hookThunk9,  (void*)hookThunk10, (void*)hookThunk11,
    (void*)hookThunk12, (void*)hookThunk13, (void*)hookThunk14, (void*)hookThunk15,
    (void*)hookThunk16, (void*)hookThunk17, (void*)hookThunk18, (void*)hookThunk19,
    (void*)hookThunk20, (void*)hookThunk21, (void*)hookThunk22, (void*)hookThunk23,
    (void*)hookThunk24, (void*)hookThunk25, (void*)hookThunk26, (void*)hookThunk27,
    (void*)hookThunk28, (void*)hookThunk29, (void*)hookThunk30, (void*)hookThunk31,
    (void*)hookThunk32, (void*)hookThunk33, (void*)hookThunk34, (void*)hookThunk35,
    (void*)hookThunk36, (void*)hookThunk37, (void*)hookThunk38, (void*)hookThunk39,
    (void*)hookThunk40, (void*)hookThunk41, (void*)hookThunk42, (void*)hookThunk43,
    (void*)hookThunk44, (void*)hookThunk45, (void*)hookThunk46, (void*)hookThunk47,
    (void*)hookThunk48, (void*)hookThunk49, (void*)hookThunk50, (void*)hookThunk51,
    (void*)hookThunk52, (void*)hookThunk53, (void*)hookThunk54, (void*)hookThunk55,
    (void*)hookThunk56, (void*)hookThunk57, (void*)hookThunk58, (void*)hookThunk59,
    (void*)hookThunk60, (void*)hookThunk61, (void*)hookThunk62, (void*)hookThunk63
};

void* g_thunksD[kMaxHooks] = {
    (void*)hookThunkD0,  (void*)hookThunkD1,  (void*)hookThunkD2,  (void*)hookThunkD3,
    (void*)hookThunkD4,  (void*)hookThunkD5,  (void*)hookThunkD6,  (void*)hookThunkD7,
    (void*)hookThunkD8,  (void*)hookThunkD9,  (void*)hookThunkD10, (void*)hookThunkD11,
    (void*)hookThunkD12, (void*)hookThunkD13, (void*)hookThunkD14, (void*)hookThunkD15,
    (void*)hookThunkD16, (void*)hookThunkD17, (void*)hookThunkD18, (void*)hookThunkD19,
    (void*)hookThunkD20, (void*)hookThunkD21, (void*)hookThunkD22, (void*)hookThunkD23,
    (void*)hookThunkD24, (void*)hookThunkD25, (void*)hookThunkD26, (void*)hookThunkD27,
    (void*)hookThunkD28, (void*)hookThunkD29, (void*)hookThunkD30, (void*)hookThunkD31,
    (void*)hookThunkD32, (void*)hookThunkD33, (void*)hookThunkD34, (void*)hookThunkD35,
    (void*)hookThunkD36, (void*)hookThunkD37, (void*)hookThunkD38, (void*)hookThunkD39,
    (void*)hookThunkD40, (void*)hookThunkD41, (void*)hookThunkD42, (void*)hookThunkD43,
    (void*)hookThunkD44, (void*)hookThunkD45, (void*)hookThunkD46, (void*)hookThunkD47,
    (void*)hookThunkD48, (void*)hookThunkD49, (void*)hookThunkD50, (void*)hookThunkD51,
    (void*)hookThunkD52, (void*)hookThunkD53, (void*)hookThunkD54, (void*)hookThunkD55,
    (void*)hookThunkD56, (void*)hookThunkD57, (void*)hookThunkD58, (void*)hookThunkD59,
    (void*)hookThunkD60, (void*)hookThunkD61, (void*)hookThunkD62, (void*)hookThunkD63
};

void pushHook(lua_State* L, HookCtx* ctx) {
    auto* h = static_cast<LuaHook*>(lua_newuserdatauv(L, sizeof(LuaHook), 0));
    new (h) LuaHook();
    h->target = ctx->target;
    h->original = ctx->original;
    h->ret = ctx->ret;
    h->args = ctx->args;
    h->enabled = ctx->enabled;
    h->ref = LUA_NOREF;
    luaL_setmetatable(L, kHookMeta);
}

int l_hook_index(lua_State* L) {
    auto h = checkHook(L, 1);
    std::string key = luaToStdStr(L, 2);
    if (key == "Target") { pushPtr(L, (unsigned long long)h->target); return 1; }
    if (key == "Original") { pushPtr(L, (unsigned long long)h->original); return 1; }
    if (key == "Enabled" || key == "enabled") { lua_pushboolean(L, h->enabled); return 1; }
    if (key == "disable") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto h = checkHook(L, 1); h->enabled = false;
        std::lock_guard<std::mutex> g(g_hooksLock);
        auto it = g_hooks.find(h->target);
        if (it != g_hooks.end()) it->second->enabled = false;
        return 0; }); return 1; }
    if (key == "enable") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto h = checkHook(L, 1); h->enabled = true;
        std::lock_guard<std::mutex> g(g_hooksLock);
        auto it = g_hooks.find(h->target);
        if (it != g_hooks.end()) it->second->enabled = true;
        return 0; }); return 1; }
    if (key == "is_enabled") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto h = checkHook(L, 1); lua_pushboolean(L, h->enabled); return 1; }); return 1; }
    if (key == "call_original") { lua_pushcfunction(L, [](lua_State* L) -> int {
        auto h = checkHook(L, 1);
        if (!h->original) { lua_pushnil(L); return 1; }
        unsigned long long x[8] = {0,0,0,0,0,0,0,0};
        double d[8] = {0,0,0,0,0,0,0,0};
        int ni = 0, nf = 0;
        int top = lua_gettop(L);
        for (size_t i = 0; i < h->args.size() && i < 8; ++i) {
            std::string t = normType(h->args[i]);
            if (isFloatType(t)) d[nf++] = luaToDouble(L, 2 + (int)i);
            else x[ni++] = luaToUll(L, 2 + (int)i, t);
        }
        (void)top;
        if (isFloatType(h->ret)) {
            auto fn = reinterpret_cast<RegThunkD>(h->original);
            double rd = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
            pushFromVal(L, h->ret, 0, rd);
        } else {
            auto fn = reinterpret_cast<RegThunk>(h->original);
            unsigned long long rx = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
            pushFromVal(L, h->ret, rx, 0);
        }
        return 1; }); return 1; }
    return luaL_error(L, "LarpLua.Hook: unknown field '%s'", key.c_str());
}

int l_hook_tostring(lua_State* L) {
    auto h = checkHook(L, 1);
    char buf[64];
    snprintf(buf, sizeof(buf), "Hook(0x%llx -> 0x%llx)",
        (unsigned long long)h->target, (unsigned long long)h->original);
    lua_pushstring(L, buf);
    return 1;
}

int l_hook_gc(lua_State* L) {
    auto h = checkHook(L, 1);
    h->~LuaHook();
    return 0;
}

void installHookMeta(lua_State* L) {
    if (luaL_newmetatable(L, kHookMeta)) {
        lua_pushcfunction(L, l_hook_index); lua_setfield(L, -2, "__index");
        lua_pushcfunction(L, l_hook_tostring); lua_setfield(L, -2, "__tostring");
        lua_pushcfunction(L, l_hook_gc); lua_setfield(L, -2, "__gc");
    }
    lua_pop(L, 1);
}

unsigned long long hookDispatch(unsigned long long a0, unsigned long long a1,
    unsigned long long a2, unsigned long long a3, unsigned long long a4,
    unsigned long long a5, unsigned long long a6, unsigned long long a7,
    double d0, double d1, double d2, double d3,
    double d4, double d5, double d6, double d7) {
    unsigned long long x[8] = {a0, a1, a2, a3, a4, a5, a6, a7};
    double d[8] = {d0, d1, d2, d3, d4, d5, d6, d7};
    HookCtx* ctx = g_currentHook;
    if (!ctx || !ctx->enabled || !ctx->L) {
        if (ctx && ctx->original) {
            auto fn = reinterpret_cast<RegThunk>(ctx->original);
            return fn(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7);
        }
        return 0;
    }
    FrameCtx* fc = frameCtx();
    FrameCtx prev = *fc;
    fc->hook = ctx;
    memcpy(fc->x, x, sizeof(x));
    memcpy(fc->d, d, sizeof(d));
    lua_State* L = ctx->L;
    unsigned long long rx = 0;
    bool got = false;
    int top = lua_gettop(L);
    {
        std::lock_guard<std::mutex> g(g_stateLock);
        lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->ref);
        int n = (int)ctx->args.size();
        for (int i = 0; i < n && i < 8; ++i) {
            std::string t = normType(ctx->args[i]);
            if (isFloatType(t)) pushFromVal(L, t, 0, d[i]);
            else pushFromVal(L, t, x[i], 0);
        }
        if (lua_pcall(L, n, 1, 0) == LUA_OK) {
            got = true;
            rx = luaToUll(L, -1, normType(ctx->ret));
            lua_pop(L, 1);
        } else {
            const char* err = lua_tostring(L, -1);
            LOGE("hook callback error: %s", err ? err : "?");
            larp_lua_log("E", err ? err : "hook failed");
            lua_pop(L, 1);
        }
        lua_settop(L, top);
    }
    *fc = prev;
    if (!got && ctx->original) {
        auto fn = reinterpret_cast<RegThunk>(ctx->original);
        return fn(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7);
    }
    return rx;
}

double hookDispatchD(unsigned long long a0, unsigned long long a1,
    unsigned long long a2, unsigned long long a3, unsigned long long a4,
    unsigned long long a5, unsigned long long a6, unsigned long long a7,
    double d0, double d1, double d2, double d3,
    double d4, double d5, double d6, double d7) {
    unsigned long long x[8] = {a0, a1, a2, a3, a4, a5, a6, a7};
    double d[8] = {d0, d1, d2, d3, d4, d5, d6, d7};
    HookCtx* ctx = g_currentHook;
    if (!ctx || !ctx->enabled || !ctx->original) return 0;
    if (!ctx->L) {
        auto fn = reinterpret_cast<RegThunkD>(ctx->original);
        return fn(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7);
    }
    lua_State* L = ctx->L;
    double rd = 0;
    int top = lua_gettop(L);
    {
        std::lock_guard<std::mutex> g(g_stateLock);
        lua_rawgeti(L, LUA_REGISTRYINDEX, ctx->ref);
        int n = (int)ctx->args.size();
        for (int i = 0; i < n && i < 8; ++i) {
            std::string t = normType(ctx->args[i]);
            if (isFloatType(t)) pushFromVal(L, t, 0, d[i]);
            else pushFromVal(L, t, x[i], 0);
        }
        if (lua_pcall(L, n, 1, 0) == LUA_OK) {
            rd = lua_isnumber(L, -1) ? lua_tonumber(L, -1) : 0.0;
            lua_pop(L, 1);
        } else {
            const char* err = lua_tostring(L, -1);
            LOGE("hookD error: %s", err ? err : "?");
            larp_lua_log("E", err ? err : "hook failed");
            lua_pop(L, 1);
        }
        lua_settop(L, top);
    }
    return rd;
}

int l_install_hook(lua_State* L) {
    auto m = checkMethod(L, 1);
    LuaMethod tmp = *m;
    parseSig(L, 2, tmp);
    luaL_checktype(L, 3, LUA_TFUNCTION);
    void* target = m->function;
    if (!target) return luaL_error(L, "hookfunction: target has no function pointer");
    {
        std::lock_guard<std::mutex> g(g_hooksLock);
        auto it = g_hooks.find(target);
        if (it != g_hooks.end()) {
            lua_unref(L, LUA_REGISTRYINDEX, it->second->ref);
            it->second->ref = luaL_ref(L, LUA_REGISTRYINDEX);
            it->second->ret = tmp.ret;
            it->second->args = tmp.args;
            it->second->enabled = true;
            pushHook(L, it->second);
            return 1;
        }
    }
    if (!g_dobbyHook) {
        g_dobbyHandle = dlopen("libdobby.so", RTLD_NOW);
        if (g_dobbyHandle) g_dobbyHook = reinterpret_cast<DobbyHookFn>(dlsym(g_dobbyHandle, "DobbyHook"));
    }
    if (!g_dobbyHook) return luaL_error(L, "hookfunction: Dobby unavailable");
    int slot = g_nextHookSlot.fetch_add(1);
    if (slot >= kMaxHooks) return luaL_error(L, "hookfunction: too many hooks (max=%d)", kMaxHooks);
    void* orig = nullptr;
    void* thunk = isFloatType(tmp.ret) ? g_thunksD[slot] : g_thunks[slot];
    int rc = g_dobbyHook(target, thunk, &orig);
    if (rc != 0 || !orig) return luaL_error(L, "hookfunction: DobbyHook rc=%d", rc);
    auto* ctx = new HookCtx();
    ctx->target = target;
    ctx->original = orig;
    ctx->L = L;
    ctx->ret = tmp.ret;
    ctx->args = tmp.args;
    ctx->enabled = true;
    lua_pushvalue(L, 3);
    ctx->ref = luaL_ref(L, LUA_REGISTRYINDEX);
    {
        std::lock_guard<std::mutex> g(g_hooksLock);
        g_hooks[target] = ctx;
        g_hookByIndex[slot] = ctx;
    }
    pushHook(L, ctx);
    return 1;
}

int l_install_hook_by_name(lua_State* L) {
    auto c = checkClass(L, 1);
    std::string name = luaToStdStr(L, 2);
    int argc = -1;
    if (lua_isnumber(L, 3)) argc = (int)lua_tointeger(L, 3);
    void* mi = nullptr;
    typedef void* (*Fn)(void*, const char*, int);
    auto fn = reinterpret_cast<Fn>(dlsym(g_il2cppHandle, "il2cpp_class_get_method_from_name"));
    if (fn) mi = fn(c->handle, name.c_str(), argc);
    if (!mi) return luaL_error(L, "method not found: %s.%s", c->name.c_str(), name.c_str());
    auto* lm = static_cast<LuaMethod*>(lua_newuserdatauv(L, sizeof(LuaMethod), 0));
    new (lm) LuaMethod();
    fillMethodInfo(*lm, mi);
    lm->ret = "ptr";
    int n = lm->argc + (lm->isStatic ? 0 : 1);
    lm->args.assign(n, "ptr");
    luaL_setmetatable(L, kMethodMeta);
    lua_replace(L, 1);
    return l_install_hook(L);
}

int l_install_hook_addr(lua_State* L) {
    unsigned long long addr = toAddr(L, 1);
    auto* lm = static_cast<LuaMethod*>(lua_newuserdatauv(L, sizeof(LuaMethod), 0));
    new (lm) LuaMethod();
    lm->info = nullptr;
    lm->function = (void*)addr;
    lm->name = "raw";
    lm->argc = 0;
    lm->isStatic = false;
    lm->ret = "ptr";
    int n = 0;
    lm->args.assign(n, "ptr");
    luaL_setmetatable(L, kMethodMeta);
    lua_replace(L, 1);
    return l_install_hook(L);
}

int l_call_original_current(lua_State* L) {
    FrameCtx* fc = frameCtx();
    if (!fc->hook) return luaL_error(L, "call_original outside of a hook");
    auto h = fc->hook;
    if (!h->original) { lua_pushnil(L); return 1; }
    unsigned long long x[8];
    double d[8];
    memcpy(x, fc->x, sizeof(x));
    memcpy(d, fc->d, sizeof(d));
    int top = lua_gettop(L);
    if (top > 0) {
        memset(x, 0, sizeof(x));
        memset(d, 0, sizeof(d));
        int ni = 0, nf = 0;
        for (size_t i = 0; i < h->args.size() && i < 8; ++i) {
            std::string t = normType(h->args[i]);
            if (isFloatType(t)) d[nf++] = luaToDouble(L, 1 + (int)i);
            else x[ni++] = luaToUll(L, 1 + (int)i, t);
        }
    }
    if (isFloatType(h->ret)) {
        auto fn = reinterpret_cast<RegThunkD>(h->original);
        double rd = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
        pushFromVal(L, h->ret, 0, rd);
    } else {
        auto fn = reinterpret_cast<RegThunk>(h->original);
        unsigned long long rx = fn(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
        pushFromVal(L, h->ret, rx, 0);
    }
    return 1;
}

int l_print(lua_State* L) {
    std::string out = formatLuaArgs(L, 1);
    larp_lua_log("I", out.c_str());
    return 0;
}

int l_warn(lua_State* L) {
    std::string out = formatLuaArgs(L, 1);
    larp_lua_log("W", out.c_str());
    return 0;
}

int l_logd(lua_State* L) { std::string s = formatLuaPrintf(L); larp_lua_log("D", s.c_str()); return 0; }
int l_logi(lua_State* L) { std::string s = formatLuaPrintf(L); larp_lua_log("I", s.c_str()); return 0; }
int l_logw(lua_State* L) { std::string s = formatLuaPrintf(L); larp_lua_log("W", s.c_str()); return 0; }
int l_loge(lua_State* L) { std::string s = formatLuaPrintf(L); larp_lua_log("E", s.c_str()); return 0; }

int l_loadstring(lua_State* L) {
    std::string code = luaToStdStr(L, 1);
    std::string chunk = lua_isstring(L, 2) ? luaToStdStr(L, 2) : std::string("=chunk");
    int rc = luaL_loadbuffer(L, code.c_str(), code.size(), chunk.c_str());
    if (rc != LUA_OK) {
        lua_pushnil(L);
        lua_pushvalue(L, -2);
        lua_remove(L, -3);
        return 2;
    }
    return 1;
}

int l_require(lua_State* L) {
    std::string name = luaToStdStr(L, 1);
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.Modules");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, name.c_str());
        if (!lua_isnil(L, -1)) { lua_remove(L, -2); return 1; }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    std::string rel = name;
    for (auto& c : rel) if (c == '.') c = '/';
    rel += ".lua";
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.ScriptsDir");
    std::string dir = lua_isstring(L, -1) ? luaToStdStr(L, -1) : std::string();
    lua_pop(L, 1);
    std::string path = dir.empty() ? rel : (dir + "/" + rel);
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return luaL_error(L, "module not found: %s", name.c_str());
    std::string code;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) code.append(buf, n);
    close(fd);
    if (luaL_loadbuffer(L, code.c_str(), code.size(), name.c_str()) != LUA_OK)
        return lua_error(L);
    lua_call(L, 0, 1);
    if (lua_isnil(L, -1)) { lua_pop(L, 1); lua_pushboolean(L, 1); }
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.Modules");
    if (lua_istable(L, -1)) {
        lua_pushvalue(L, -2);
        lua_setfield(L, -2, name.c_str());
    }
    lua_pop(L, 1);
    return 1;
}

int l_request(lua_State* L) {
    std::string url, method = "GET", body;
    std::vector<std::pair<std::string, std::string>> headers;
    bool simple = false;
    int top = lua_gettop(L);
    if (top >= 1 && lua_isstring(L, 1)) {
        url = luaToStdStr(L, 1);
        simple = true;
    } else if (top >= 1 && lua_istable(L, 1)) {
        lua_getfield(L, 1, "Url"); if (lua_isnil(L, -1)) lua_getfield(L, 1, "url");
        url = luaToStdStr(L, -1); lua_pop(L, 1);
        lua_getfield(L, 1, "Method"); if (lua_isnil(L, -1)) lua_getfield(L, 1, "method");
        method = luaToStdStr(L, -1); if (method.empty()) method = "GET"; lua_pop(L, 1);
        lua_getfield(L, 1, "Body"); if (lua_isnil(L, -1)) lua_getfield(L, 1, "body");
        body = luaToStdStr(L, -1); lua_pop(L, 1);
        lua_getfield(L, 1, "Headers"); if (lua_isnil(L, -1)) lua_getfield(L, 1, "headers");
        if (lua_istable(L, -1)) {
            lua_pushnil(L);
            while (lua_next(L, -2)) {
                headers.emplace_back(luaToStdStr(L, -2), luaToStdStr(L, -1));
                lua_pop(L, 1);
            }
        }
        lua_pop(L, 1);
    } else {
        return luaL_error(L, "request expects url string or table");
    }
    if (url.empty()) return luaL_error(L, "request: missing url");

    size_t proto = url.find("://");
    if (proto == std::string::npos) return luaL_error(L, "request: invalid url");
    std::string scheme = url.substr(0, proto);
    bool https = (scheme == "https");
    std::string rest = url.substr(proto + 3);
    size_t slash = rest.find('/');
    std::string host_port = slash == std::string::npos ? rest : rest.substr(0, slash);
    std::string path = slash == std::string::npos ? "/" : rest.substr(slash);
    if (path.empty()) path = "/";
    std::string host;
    int port = https ? 443 : 80;
    size_t colon = host_port.find(':');
    if (colon != std::string::npos) {
        host = host_port.substr(0, colon);
        port = atoi(host_port.c_str() + colon + 1);
    } else host = host_port;

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        if (simple) return luaL_error(L, "request: socket failed");
        lua_newtable(L);
        lua_pushboolean(L, 0); lua_setfield(L, -2, "Success");
        lua_pushinteger(L, 0); lua_setfield(L, -2, "StatusCode");
        pushStdString(L, "socket failed"); lua_setfield(L, -2, "StatusMessage");
        lua_pushstring(L, ""); lua_setfield(L, -2, "Body");
        return 1;
    }
    struct hostent* he = gethostbyname(host.c_str());
    if (!he || !he->h_addr_list[0]) {
        close(sock);
        if (simple) return luaL_error(L, "request: dns failed for %s", host.c_str());
        lua_newtable(L);
        lua_pushboolean(L, 0); lua_setfield(L, -2, "Success");
        lua_pushinteger(L, 0); lua_setfield(L, -2, "StatusCode");
        pushStdString(L, "dns failed"); lua_setfield(L, -2, "StatusMessage");
        lua_pushstring(L, ""); lua_setfield(L, -2, "Body");
        return 1;
    }
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    memcpy(&addr.sin_addr, he->h_addr_list[0], he->h_length);
    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(sock);
        if (simple) return luaL_error(L, "request: connect failed");
        lua_newtable(L);
        lua_pushboolean(L, 0); lua_setfield(L, -2, "Success");
        lua_pushinteger(L, 0); lua_setfield(L, -2, "StatusCode");
        pushStdString(L, "connect failed"); lua_setfield(L, -2, "StatusMessage");
        lua_pushstring(L, ""); lua_setfield(L, -2, "Body");
        return 1;
    }

    std::string req = method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nUser-Agent: LarpLua/1.0\r\n";
    for (auto& h : headers) req += h.first + ": " + h.second + "\r\n";
    if (!body.empty()) {
        req += "Content-Length: " + std::to_string(body.size()) + "\r\n\r\n";
        req += body;
    } else req += "\r\n";
    ssize_t w = 0; size_t total = 0;
    while (total < req.size() && (w = write(sock, req.data() + total, req.size() - total)) > 0) total += w;

    std::string resp;
    char buf[4096];
    ssize_t r;
    while ((r = read(sock, buf, sizeof(buf))) > 0) resp.append(buf, r);
    close(sock);

    size_t hdrEnd = resp.find("\r\n\r\n");
    std::string headers_raw = hdrEnd == std::string::npos ? resp : resp.substr(0, hdrEnd);
    std::string respBody = hdrEnd == std::string::npos ? "" : resp.substr(hdrEnd + 4);

    int code = 0;
    std::string status;
    size_t sp1 = headers_raw.find(' ');
    if (sp1 != std::string::npos) {
        size_t sp2 = headers_raw.find(' ', sp1 + 1);
        code = atoi(headers_raw.c_str() + sp1 + 1);
        if (sp2 != std::string::npos) status = headers_raw.substr(sp2 + 1);
        size_t nl = status.find('\r');
        if (nl != std::string::npos) status = status.substr(0, nl);
    }
    bool ok = code >= 200 && code < 300;

    if (simple) {
        if (!ok) return luaL_error(L, "request failed: HTTP %d %s", code, status.c_str());
        pushStdString(L, respBody);
        return 1;
    }
    lua_newtable(L);
    lua_pushboolean(L, ok); lua_setfield(L, -2, "Success");
    lua_pushinteger(L, code); lua_setfield(L, -2, "StatusCode");
    pushStdString(L, status); lua_setfield(L, -2, "StatusMessage");
    pushStdString(L, respBody); lua_setfield(L, -2, "Body");
    lua_newtable(L); lua_setfield(L, -2, "Headers");
    return 1;
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

std::string getWorkspace(lua_State* L) {
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.Workspace");
    std::string ws = lua_isstring(L, -1) ? luaToStdStr(L, -1) : std::string("/storage/emulated/0/Larplaucher/Workspace");
    lua_pop(L, 1);
    while (!ws.empty() && ws.back() == '/') ws.pop_back();
    if (ws.empty()) ws = "/storage/emulated/0/Larplaucher/Workspace";
    return ws;
}

std::string getScriptsDir(lua_State* L) {
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.ScriptsDir");
    std::string sd = lua_isstring(L, -1) ? luaToStdStr(L, -1) : std::string();
    lua_pop(L, 1);
    return sd;
}

std::string resolvePath(lua_State* L, const std::string& p) {
    std::string ws = getWorkspace(L);
    std::string full;
    if (!p.empty() && p[0] == '/') full = p;
    else full = ws + "/" + p;
    std::string normalized = normalizePath(full);
    if (normalized.compare(0, ws.size(), ws) != 0) return "";
    return normalized;
}

std::string resolveScriptPath(lua_State* L, const std::string& p) {
    std::string sd = getScriptsDir(L);
    std::string full;
    if (!p.empty() && p[0] == '/') full = p;
    else if (!sd.empty()) full = sd + "/" + p;
    else full = p;
    return normalizePath(full);
}

int l_writefile(lua_State* L) {
    std::string path = resolvePath(L, luaToStdStr(L, 1));
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
    std::string path = resolvePath(L, luaToStdStr(L, 1));
    if (path.empty()) { lua_pushnil(L); pushStdString(L, "outside workspace"); return 2; }
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) { lua_pushnil(L); pushStdString(L, "open failed"); return 2; }
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out.append(buf, n);
    close(fd);
    pushStdString(L, out);
    return 1;
}

int l_isfile(lua_State* L) {
    std::string path = resolvePath(L, luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    struct stat st;
    if (stat(path.c_str(), &st) != 0) { lua_pushboolean(L, 0); return 1; }
    lua_pushboolean(L, S_ISREG(st.st_mode));
    return 1;
}

int l_editfile(lua_State* L) {
    return l_writefile(L);
}

int l_makefolder(lua_State* L) {
    std::string path = resolvePath(L, luaToStdStr(L, 1));
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
    std::string path = resolvePath(L, luaToStdStr(L, 1));
    if (path.empty()) { lua_pushnil(L); return 1; }
    DIR* d = opendir(path.c_str());
    if (!d) { lua_pushnil(L); return 1; }
    lua_newtable(L);
    int i = 1;
    struct dirent* e;
    while ((e = readdir(d))) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        lua_pushstring(L, e->d_name);
        lua_seti(L, -2, i++);
    }
    closedir(d);
    return 1;
}

int l_removefile(lua_State* L) {
    std::string path = resolvePath(L, luaToStdStr(L, 1));
    if (path.empty()) { lua_pushboolean(L, 0); return 1; }
    lua_pushboolean(L, unlink(path.c_str()) == 0);
    return 1;
}

int l_customasset(lua_State* L) {
    std::string path = resolvePath(L, luaToStdStr(L, 1));
    if (path.empty()) { lua_pushnil(L); return 1; }
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) { lua_pushnil(L); return 1; }
    std::string out;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) out.append(buf, n);
    close(fd);
    static const char* kEnc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string b64;
    int val = 0, valb = -6;
    for (unsigned char c : out) {
        val = (val << 8) + c;
        valb += 8;
        if (valb >= 0) {
            b64 += kEnc[(val >> valb) & 0x3F];
            valb -= 6;
        }
    }
    if (valb > -6) b64 += kEnc[((val << 8) >> (valb + 6)) & 0x3F];
    while (b64.size() % 4) b64 += '=';
    pushStdString(L, b64);
    return 1;
}

int l_class(lua_State* L) {
    std::string ns, name;
    if (lua_gettop(L) >= 2) { ns = luaToStdStr(L, 1); name = luaToStdStr(L, 2); }
    else name = luaToStdStr(L, 1);
    void* h = il2FindClass(ns, name);
    if (!h) return luaL_error(L, "class not found: %s.%s", ns.c_str(), name.c_str());
    auto* c = static_cast<LuaClass*>(lua_newuserdatauv(L, sizeof(LuaClass), 0));
    new (c) LuaClass();
    c->handle = h;
    c->name = name;
    c->ns = ns;
    luaL_setmetatable(L, kClassMeta);
    return 1;
}

int l_get_method_address(lua_State* L) {
    unsigned long long a = toAddr(L, 1);
    pushPtr(L, a);
    return 1;
}

int l_ptr(lua_State* L) {
    unsigned long long v = toAddr(L, 1);
    pushPtr(L, v);
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

int l_screen(lua_State* L) {
    lua_newtable(L);
    lua_pushnumber(L, (lua_Number)g_screenW); lua_setfield(L, -2, "width");
    lua_pushnumber(L, (lua_Number)g_screenH); lua_setfield(L, -2, "height");
    lua_pushnumber(L, (lua_Number)(g_screenW / 2)); lua_setfield(L, -2, "cx");
    lua_pushnumber(L, (lua_Number)(g_screenH / 2)); lua_setfield(L, -2, "cy");
    lua_pushnumber(L, (lua_Number)g_screenScale); lua_setfield(L, -2, "scale");
    return 1;
}

int l_world_to_screen(lua_State* L) {
    lua_pushnil(L);
    lua_pushnil(L);
    lua_pushboolean(L, 0);
    return 3;
}

int l_time(lua_State* L) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    double t = (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
    lua_pushnumber(L, t);
    return 1;
}

int l_fps(lua_State* L) { lua_pushinteger(L, 60); return 1; }
int l_ping(lua_State* L) { lua_pushinteger(L, 0); return 1; }
int l_notify(lua_State* L) { std::string s = luaToStdStr(L, 1); larp_lua_log("I", s.c_str()); return 0; }

int l_color(lua_State* L) {
    int r = (int)luaL_optinteger(L, 1, 255);
    int g = (int)luaL_optinteger(L, 2, 255);
    int b = (int)luaL_optinteger(L, 3, 255);
    int a = (int)luaL_optinteger(L, 4, 255);
    unsigned int v = ((unsigned int)a << 24) | ((unsigned int)r << 16) | ((unsigned int)g << 8) | (unsigned int)b;
    lua_pushinteger(L, (lua_Integer)v);
    return 1;
}

int l_clipboard(lua_State* L) {
    lua_pushboolean(L, 0);
    return 1;
}

int l_game_state(lua_State* L) { lua_pushstring(L, "InGame"); return 1; }
int l_local_name(lua_State* L) { lua_pushstring(L, ""); return 1; }

void jsonWriteStr(std::string& out, const std::string& s) {
    out += '"';
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if ((unsigned char)c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else out += c;
        }
    }
    out += '"';
}

void jsonEncode(lua_State* L, int idx, std::string& out, int depth);

void jsonEncode(lua_State* L, int idx, std::string& out, int depth) {
    idx = lua_absindex(L, idx);
    int t = lua_type(L, idx);
    switch (t) {
        case LUA_TNIL: out += "null"; break;
        case LUA_TBOOLEAN: out += lua_toboolean(L, idx) ? "true" : "false"; break;
        case LUA_TNUMBER:
            if (lua_isinteger(L, idx)) out += std::to_string(lua_tointeger(L, idx));
            else {
                char buf[64];
                snprintf(buf, sizeof(buf), "%.17g", lua_tonumber(L, idx));
                out += buf;
            }
            break;
        case LUA_TSTRING: jsonWriteStr(out, luaToStdStr(L, idx)); break;
        case LUA_TTABLE: {
            if (depth > 100) { out += "null"; break; }
            lua_pushnil(L);
            bool first = true;
            bool isArr = true;
            int maxArr = 0;
            int count = 0;
            while (lua_next(L, idx)) {
                count++;
                if (lua_isinteger(L, -2)) {
                    int k = (int)lua_tointeger(L, -2);
                    if (k > maxArr) maxArr = k;
                } else isArr = false;
                lua_pop(L, 1);
            }
            bool arrayForm = isArr && maxArr == count;
            if (arrayForm) {
                out += '[';
                for (int i = 1; i <= maxArr; ++i) {
                    if (!first) out += ',';
                    first = false;
                    lua_geti(L, idx, i);
                    jsonEncode(L, -1, out, depth + 1);
                    lua_pop(L, 1);
                }
                out += ']';
            } else {
                out += '{';
                lua_pushnil(L);
                while (lua_next(L, idx)) {
                    if (!first) out += ',';
                    first = false;
                    if (lua_type(L, -2) == LUA_TSTRING) jsonWriteStr(out, luaToStdStr(L, -2));
                    else jsonWriteStr(out, std::to_string(lua_tointeger(L, -2)));
                    out += ':';
                    jsonEncode(L, -1, out, depth + 1);
                    lua_pop(L, 1);
                }
                out += '}';
            }
            break;
        }
        default: jsonWriteStr(out, luaToStdStr(L, idx)); break;
    }
}

int l_json_encode(lua_State* L) {
    std::string out;
    jsonEncode(L, 1, out, 0);
    pushStdString(L, out);
    return 1;
}

struct JsonParser {
    const char* p;
    const char* end;
    lua_State* L;
    bool ok = true;
    std::string err;

    void skip() { while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p; }
    bool match(char c) { skip(); if (p < end && *p == c) { ++p; return true; } return false; }
    bool expect(char c) { if (!match(c)) { ok = false; err = std::string("expected '") + c + "'"; return false; } return true; }

    void parseValue() {
        if (!ok) return;
        skip();
        if (p >= end) { ok = false; err = "unexpected end"; return; }
        char c = *p;
        if (c == '"') parseStr();
        else if (c == '{') parseObj();
        else if (c == '[') parseArr();
        else if (c == 't') { if (end - p >= 4 && memcmp(p, "true", 4) == 0) { p += 4; lua_pushboolean(L, 1); } else { ok = false; err = "invalid"; } }
        else if (c == 'f') { if (end - p >= 5 && memcmp(p, "false", 5) == 0) { p += 5; lua_pushboolean(L, 0); } else { ok = false; err = "invalid"; } }
        else if (c == 'n') { if (end - p >= 4 && memcmp(p, "null", 4) == 0) { p += 4; lua_pushnil(L); } else { ok = false; err = "invalid"; } }
        else if (c == '-' || (c >= '0' && c <= '9')) parseNum();
        else { ok = false; err = "unexpected char"; }
    }

    void parseStr() {
        ++p;
        std::string s;
        while (p < end && *p != '"') {
            if (*p == '\\' && p + 1 < end) {
                ++p;
                char e = *p++;
                switch (e) {
                    case '"': s += '"'; break;
                    case '\\': s += '\\'; break;
                    case '/': s += '/'; break;
                    case 'n': s += '\n'; break;
                    case 't': s += '\t'; break;
                    case 'r': s += '\r'; break;
                    case 'b': s += '\b'; break;
                    case 'f': s += '\f'; break;
                    case 'u': {
                        if (end - p >= 4) {
                            char buf[5] = { p[0], p[1], p[2], p[3], 0 };
                            p += 4;
                            unsigned int v = (unsigned int)strtoul(buf, nullptr, 16);
                            if (v < 0x80) s += (char)v;
                            else if (v < 0x800) { s += (char)(0xC0 | (v >> 6)); s += (char)(0x80 | (v & 0x3F)); }
                            else { s += (char)(0xE0 | (v >> 12)); s += (char)(0x80 | ((v >> 6) & 0x3F)); s += (char)(0x80 | (v & 0x3F)); }
                        }
                        break;
                    }
                    default: s += e;
                }
            } else s += *p++;
        }
        if (p < end) ++p;
        pushStdString(L, s);
    }

    void parseNum() {
        const char* st = p;
        while (p < end && (strchr("-+0123456789.eE", *p))) ++p;
        std::string s(st, p - st);
        if (s.find('.') != std::string::npos || s.find('e') != std::string::npos || s.find('E') != std::string::npos)
            lua_pushnumber(L, strtod(s.c_str(), nullptr));
        else lua_pushinteger(L, strtoll(s.c_str(), nullptr, 10));
    }

    void parseObj() {
        ++p;
        lua_newtable(L);
        skip();
        if (p < end && *p == '}') { ++p; return; }
        for (;;) {
            skip();
            if (p >= end || *p != '"') { ok = false; err = "expected string key"; return; }
            parseStr();
            skip();
            if (!expect(':')) { lua_pop(L, 1); return; }
            parseValue();
            if (!ok) return;
            lua_settable(L, -3);
            skip();
            if (match(',')) continue;
            if (match('}')) return;
            ok = false; err = "expected , or }"; return;
        }
    }

    void parseArr() {
        ++p;
        lua_newtable(L);
        skip();
        if (p < end && *p == ']') { ++p; return; }
        int i = 1;
        for (;;) {
            parseValue();
            if (!ok) return;
            lua_seti(L, -2, i++);
            skip();
            if (match(',')) continue;
            if (match(']')) return;
            ok = false; err = "expected , or ]"; return;
        }
    }
};

int l_json_decode(lua_State* L) {
    std::string s = luaToStdStr(L, 1);
    JsonParser ps{ s.c_str(), s.c_str() + s.size(), L };
    ps.parseValue();
    if (!ps.ok) {
        lua_pop(L, 1);
        return luaL_error(L, "json decode: %s", ps.err.c_str());
    }
    return 1;
}

int l_getworkspace(lua_State* L) {
    pushStdString(L, getWorkspace(L));
    return 1;
}

int l_loadfile(lua_State* L) {
    std::string p = luaToStdStr(L, 1);
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.ScriptsDir");
    std::string dir = lua_isstring(L, -1) ? luaToStdStr(L, -1) : std::string();
    lua_pop(L, 1);
    std::string full = (!p.empty() && p[0] == '/') ? p : (dir.empty() ? p : (dir + "/" + p));
    int fd = open(full.c_str(), O_RDONLY);
    if (fd < 0) { lua_pushnil(L); pushStdString(L, "open failed"); return 2; }
    std::string code;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) code.append(buf, n);
    close(fd);
    std::string chunk = lua_isstring(L, 2) ? luaToStdStr(L, 2) : full;
    int rc = luaL_loadbuffer(L, code.c_str(), code.size(), chunk.c_str());
    if (rc != LUA_OK) { lua_pushnil(L); lua_pushvalue(L, -2); lua_remove(L, -3); return 2; }
    return 1;
}

int l_dofile(lua_State* L) {
    std::string p = luaToStdStr(L, 1);
    lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.ScriptsDir");
    std::string dir = lua_isstring(L, -1) ? luaToStdStr(L, -1) : std::string();
    lua_pop(L, 1);
    std::string full = (!p.empty() && p[0] == '/') ? p : (dir.empty() ? p : (dir + "/" + p));
    int fd = open(full.c_str(), O_RDONLY);
    if (fd < 0) return luaL_error(L, "dofile: open failed: %s", full.c_str());
    std::string code;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) code.append(buf, n);
    close(fd);
    if (luaL_loadbuffer(L, code.c_str(), code.size(), full.c_str()) != LUA_OK)
        return lua_error(L);
    lua_call(L, 0, LUA_MULTRET);
    return lua_gettop(L);
}

int l_typeof(lua_State* L) {
    int t = lua_type(L, 1);
    if (t == LUA_TUSERDATA) {
        if (luaL_testudata(L, 1, kPtrMeta)) { lua_pushstring(L, "ptr"); return 1; }
        if (luaL_testudata(L, 1, kClassMeta)) { lua_pushstring(L, "class"); return 1; }
        if (luaL_testudata(L, 1, kMethodMeta)) { lua_pushstring(L, "method"); return 1; }
        if (luaL_testudata(L, 1, kHookMeta)) { lua_pushstring(L, "hook"); return 1; }
    }
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
    lua_pushglobaltable(L);
    return 1;
}

int l_getrenv(lua_State* L) {
    lua_pushvalue(L, LUA_REGISTRYINDEX);
    return 1;
}

int l_getfenv(lua_State* L) {
    lua_pushglobaltable(L);
    return 1;
}

int l_setfenv(lua_State* L) {
    lua_settop(L, 2);
    if (lua_istable(L, 2)) {
        lua_pushvalue(L, 2);
        lua_rawseti(L, LUA_REGISTRYINDEX, 2);
    }
    lua_pushvalue(L, 2);
    return 1;
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

int l_newcclosure(lua_State* L) {
    luaL_checktype(L, 1, LUA_TFUNCTION);
    if (lua_iscfunction(L, 1)) { lua_pushvalue(L, 1); return 1; }
    lua_pushvalue(L, 1);
    lua_pushcclosure(L, [](lua_State* L) -> int {
        lua_getfield(L, LUA_REGISTRYINDEX, "LarpLua.Upvalue");
        lua_insert(L, 1);
        lua_call(L, lua_gettop(L) - 1, LUA_MULTRET);
        return lua_gettop(L);
    }, "wrapped", 1);
    lua_pushvalue(L, -1);
    lua_setfield(L, LUA_REGISTRYINDEX, "LarpLua.Upvalue");
    return 1;
}

int l_clonefunction(lua_State* L) {
    luaL_checktype(L, 1, LUA_TFUNCTION);
    lua_pushvalue(L, 1);
    return 1;
}

int l_checkcaller(lua_State* L) {
    lua_pushboolean(L, 1);
    return 1;
}

int l_identifyexecutor(lua_State* L) {
    lua_pushstring(L, "LarpLauncher");
    lua_pushstring(L, LARP_LUA_VERSION);
    return 2;
}

int l_getrawmetatable(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    if (lua_getmetatable(L, 1)) return 1;
    lua_pushnil(L);
    return 1;
}

int l_setrawmetatable(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    luaL_checktype(L, 2, LUA_TTABLE);
    lua_setmetatable(L, 1);
    lua_pushvalue(L, 1);
    return 1;
}

int l_hookmetamethod(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    std::string name = luaToStdStr(L, 2);
    luaL_checktype(L, 3, LUA_TFUNCTION);
    lua_getfield(L, 1, name.c_str());
    lua_pushvalue(L, 3);
    lua_setfield(L, 1, name.c_str());
    return 1;
}

int l_drawing_new(lua_State* L) {
    std::string kind = luaToStdStr(L, 1);
    lua_newtable(L);
    lua_pushstring(L, kind.c_str()); lua_setfield(L, -2, "Type");
    lua_pushnumber(L, 0); lua_setfield(L, -2, "Visible");
    lua_pushnumber(L, 0); lua_setfield(L, -2, "X");
    lua_pushnumber(L, 0); lua_setfield(L, -2, "Y");
    if (kind == "Line") {
        lua_pushnumber(L, 0); lua_setfield(L, -2, "X2");
        lua_pushnumber(L, 0); lua_setfield(L, -2, "Y2");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "R");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "G");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "B");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "A");
        lua_pushnumber(L, 1); lua_setfield(L, -2, "Thickness");
    } else if (kind == "Circle") {
        lua_pushnumber(L, 0); lua_setfield(L, -2, "Radius");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "R");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "G");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "B");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "A");
        lua_pushnumber(L, 1); lua_setfield(L, -2, "Thickness");
        lua_pushnumber(L, 0); lua_setfield(L, -2, "Filled");
    } else if (kind == "Text") {
        lua_pushstring(L, ""); lua_setfield(L, -2, "Text");
        lua_pushnumber(L, 14); lua_setfield(L, -2, "Size");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "R");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "G");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "B");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "A");
        lua_pushstring(L, "Sans"); lua_setfield(L, -2, "Font");
    } else if (kind == "Image") {
        lua_pushstring(L, ""); lua_setfield(L, -2, "Data");
        lua_pushnumber(L, 100); lua_setfield(L, -2, "Width");
        lua_pushnumber(L, 100); lua_setfield(L, -2, "Height");
    } else if (kind == "Square" || kind == "Rect") {
        lua_pushnumber(L, 100); lua_setfield(L, -2, "Width");
        lua_pushnumber(L, 100); lua_setfield(L, -2, "Height");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "R");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "G");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "B");
        lua_pushnumber(L, 255); lua_setfield(L, -2, "A");
        lua_pushnumber(L, 1); lua_setfield(L, -2, "Thickness");
        lua_pushnumber(L, 0); lua_setfield(L, -2, "Filled");
    }
    lua_pushcfunction(L, [](lua_State* L) -> int { lua_pushboolean(L, 1); return 1; });
    lua_setfield(L, -2, "Remove");
    lua_pushcfunction(L, [](lua_State* L) -> int { return 0; });
    lua_setfield(L, -2, "Update");
    return 1;
}

void registerMsd(lua_State* L) {
    lua_newtable(L);
    int t = lua_gettop(L);

    lua_pushstring(L, LARP_LUA_VERSION); lua_setfield(L, t, "Version");

    lua_pushcfunction(L, l_class); lua_setfield(L, t, "Class");
    lua_pushcfunction(L, l_get_method_address); lua_setfield(L, t, "GetMethodAddress");
    lua_pushcfunction(L, l_install_hook); lua_setfield(L, t, "hookfunction");
    lua_pushcfunction(L, l_install_hook_addr); lua_setfield(L, t, "hookfunction_addr");
    lua_pushcfunction(L, l_call_original_current); lua_setfield(L, t, "call_original");
    lua_pushcfunction(L, l_ptr); lua_setfield(L, t, "ptr");

    lua_pushcfunction(L, l_writefile); lua_setfield(L, t, "writefile");
    lua_pushcfunction(L, l_readfile); lua_setfield(L, t, "readfile");
    lua_pushcfunction(L, l_isfile); lua_setfield(L, t, "isfile");
    lua_pushcfunction(L, l_editfile); lua_setfield(L, t, "editfile");
    lua_pushcfunction(L, l_makefolder); lua_setfield(L, t, "makefolder");
    lua_pushcfunction(L, l_listfolder); lua_setfield(L, t, "listfolder");
    lua_pushcfunction(L, l_removefile); lua_setfield(L, t, "removefile");
    lua_pushcfunction(L, l_customasset); lua_setfield(L, t, "getcustomasset");

    lua_pushcfunction(L, l_screen_size); lua_setfield(L, t, "ScreenSize");
    lua_pushcfunction(L, l_screen_center); lua_setfield(L, t, "ScreenCenter");
    lua_pushcfunction(L, l_screen); lua_setfield(L, t, "Screen");
    lua_pushcfunction(L, l_world_to_screen); lua_setfield(L, t, "WorldToScreen");

    lua_pushcfunction(L, l_time); lua_setfield(L, t, "Time");
    lua_pushcfunction(L, l_fps); lua_setfield(L, t, "FPS");
    lua_pushcfunction(L, l_ping); lua_setfield(L, t, "Ping");
    lua_pushcfunction(L, l_notify); lua_setfield(L, t, "Notify");
    lua_pushcfunction(L, l_clipboard); lua_setfield(L, t, "SetClipboard");
    lua_pushcfunction(L, l_color); lua_setfield(L, t, "Color");
    lua_pushcfunction(L, l_game_state); lua_setfield(L, t, "GameState");
    lua_pushcfunction(L, l_local_name); lua_setfield(L, t, "LocalName");
    lua_pushcfunction(L, l_json_encode); lua_setfield(L, t, "JSONEncode");
    lua_pushcfunction(L, l_json_decode); lua_setfield(L, t, "JSONDecode");

    lua_pushcfunction(L, l_request); lua_setfield(L, t, "request");
    lua_pushcfunction(L, l_loadstring); lua_setfield(L, t, "loadstring");
    lua_pushcfunction(L, l_loadfile); lua_setfield(L, t, "loadfile");
    lua_pushcfunction(L, l_dofile); lua_setfield(L, t, "dofile");
    lua_pushcfunction(L, l_typeof); lua_setfield(L, t, "typeof");
    lua_pushcfunction(L, l_getgenv); lua_setfield(L, t, "getgenv");
    lua_pushcfunction(L, l_getrenv); lua_setfield(L, t, "getrenv");
    lua_pushcfunction(L, l_getfenv); lua_setfield(L, t, "getfenv");
    lua_pushcfunction(L, l_setfenv); lua_setfield(L, t, "setfenv");
    lua_pushcfunction(L, l_iscclosure); lua_setfield(L, t, "iscclosure");
    lua_pushcfunction(L, l_islclosure); lua_setfield(L, t, "islclosure");
    lua_pushcfunction(L, l_newcclosure); lua_setfield(L, t, "newcclosure");
    lua_pushcfunction(L, l_clonefunction); lua_setfield(L, t, "clonefunction");
    lua_pushcfunction(L, l_checkcaller); lua_setfield(L, t, "checkcaller");
    lua_pushcfunction(L, l_identifyexecutor); lua_setfield(L, t, "identifyexecutor");
    lua_pushcfunction(L, l_getrawmetatable); lua_setfield(L, t, "getrawmetatable");
    lua_pushcfunction(L, l_setrawmetatable); lua_setfield(L, t, "setrawmetatable");
    lua_pushcfunction(L, l_hookmetamethod); lua_setfield(L, t, "hookmetamethod");
    lua_pushcfunction(L, l_getworkspace); lua_setfield(L, t, "getworkspace");

    lua_setglobal(L, "msd");

    lua_pushcfunction(L, l_request); lua_setglobal(L, "request");
    lua_pushcfunction(L, l_loadstring); lua_setglobal(L, "loadstring");
    lua_pushcfunction(L, l_loadfile); lua_setglobal(L, "loadfile");
    lua_pushcfunction(L, l_dofile); lua_setglobal(L, "dofile");
    lua_pushcfunction(L, l_require); lua_setglobal(L, "require");
    lua_pushcfunction(L, l_typeof); lua_setglobal(L, "typeof");
    lua_pushcfunction(L, l_getgenv); lua_setglobal(L, "getgenv");
    lua_pushcfunction(L, l_getrenv); lua_setglobal(L, "getrenv");
    lua_pushcfunction(L, l_getfenv); lua_setglobal(L, "getfenv");
    lua_pushcfunction(L, l_setfenv); lua_setglobal(L, "setfenv");
    lua_pushcfunction(L, l_iscclosure); lua_setglobal(L, "iscclosure");
    lua_pushcfunction(L, l_islclosure); lua_setglobal(L, "islclosure");
    lua_pushcfunction(L, l_newcclosure); lua_setglobal(L, "newcclosure");
    lua_pushcfunction(L, l_clonefunction); lua_setglobal(L, "clonefunction");
    lua_pushcfunction(L, l_checkcaller); lua_setglobal(L, "checkcaller");
    lua_pushcfunction(L, l_identifyexecutor); lua_setglobal(L, "identifyexecutor");
    lua_pushcfunction(L, l_getrawmetatable); lua_setglobal(L, "getrawmetatable");
    lua_pushcfunction(L, l_setrawmetatable); lua_setglobal(L, "setrawmetatable");
    lua_pushcfunction(L, l_hookmetamethod); lua_setglobal(L, "hookmetamethod");
    lua_pushcfunction(L, l_getworkspace); lua_setglobal(L, "getworkspace");

    lua_pushcfunction(L, l_print); lua_setglobal(L, "print");
    lua_pushcfunction(L, l_warn); lua_setglobal(L, "warn");
    lua_pushcfunction(L, l_logd); lua_setglobal(L, "LOGD");
    lua_pushcfunction(L, l_logi); lua_setglobal(L, "LOGI");
    lua_pushcfunction(L, l_logw); lua_setglobal(L, "LOGW");
    lua_pushcfunction(L, l_loge); lua_setglobal(L, "LOGE");

    lua_pushcfunction(L, l_color); lua_setglobal(L, "Color");

    lua_newtable(L);
    lua_pushcfunction(L, l_json_encode); lua_setfield(L, -2, "encode");
    lua_pushcfunction(L, l_json_decode); lua_setfield(L, -2, "decode");
    lua_setglobal(L, "json");

    lua_newtable(L);
    lua_setglobal(L, "shared");

    lua_newtable(L);
    lua_pushcfunction(L, l_drawing_new); lua_setfield(L, -2, "new");
    lua_setglobal(L, "Drawing");

    lua_newtable(L);
    lua_setglobal(L, "syn");
    lua_pushvalue(L, -1);
    lua_setfield(L, LUA_REGISTRYINDEX, "LarpLua.Syn");

    lua_getglobal(L, "msd");
    lua_pushcfunction(L, l_drawing_new); lua_setfield(L, -2, "Drawing");
    lua_pop(L, 1);

    lua_newtable(L);
    lua_setfield(L, LUA_REGISTRYINDEX, "LarpLua.Modules");

    lua_pushcfunction(L, l_install_hook);
    lua_setfield(L, LUA_REGISTRYINDEX, "LarpLua.InstallHook");
    lua_pushcfunction(L, l_install_hook_by_name);
    lua_setfield(L, LUA_REGISTRYINDEX, "LarpLua.InstallHookByName");
}

}

LARP_LUA_EXPORT int larplua_ready() { return g_ready.load() ? 1 : 0; }

LARP_LUA_EXPORT int larplua_init(const char* rootDir, const char* scriptsDir, const char* workspaceDir, int screenW, int screenH, float scale) {
    std::lock_guard<std::mutex> g(g_stateLock);
    if (g_state) {
        lua_close(g_state);
        g_state = nullptr;
    }
    g_state = luaL_newstate();
    if (!g_state) return -1;
    lua_atpanic(g_state, larp_lua_atpanic);
    luaL_openlibs(g_state);
    installPtrMeta(g_state);
    installClassMeta(g_state);
    installMethodMeta(g_state);
    installHookMeta(g_state);
    registerMsd(g_state);
    if (rootDir) { pushStdString(g_state, rootDir); lua_setfield(g_state, LUA_REGISTRYINDEX, "LarpLua.RootDir"); }
    if (scriptsDir) { pushStdString(g_state, scriptsDir); lua_setfield(g_state, LUA_REGISTRYINDEX, "LarpLua.ScriptsDir"); }
    std::string ws = workspaceDir ? std::string(workspaceDir) : std::string("/storage/emulated/0/Larplaucher/Workspace");
    pushStdString(g_state, ws); lua_setfield(g_state, LUA_REGISTRYINDEX, "LarpLua.Workspace");
    if (!ws.empty()) {
        std::string acc;
        for (size_t i = 0; i < ws.size(); ++i) {
            acc += ws[i];
            if (ws[i] == '/' || i + 1 == ws.size()) mkdir(acc.c_str(), 0775);
        }
    }
    g_screenW = screenW > 0 ? screenW : 1080;
    g_screenH = screenH > 0 ? screenH : 1920;
    g_screenScale = scale > 0 ? scale : 1.0f;
    if (!g_il2cppHandle) g_il2cppHandle = dlopen("libil2cpp.so", RTLD_NOW);
    if (g_il2cppHandle) {
        g_il2.domainGet = dlsym(g_il2cppHandle, "il2cpp_domain_get");
        g_il2.domainGetAssemblies = dlsym(g_il2cppHandle, "il2cpp_domain_get_assemblies");
        g_il2.assemblyGetImage = dlsym(g_il2cppHandle, "il2cpp_assembly_get_image");
        g_il2.imageGetName = dlsym(g_il2cppHandle, "il2cpp_image_get_name");
        g_il2.classFromName = dlsym(g_il2cppHandle, "il2cpp_class_from_name");
        g_il2.classGetName = dlsym(g_il2cppHandle, "il2cpp_class_get_name");
        g_il2.classGetNamespace = dlsym(g_il2cppHandle, "il2cpp_class_get_namespace");
        g_il2.classGetMethods = dlsym(g_il2cppHandle, "il2cpp_class_get_methods");
        g_il2.classGetFields = dlsym(g_il2cppHandle, "il2cpp_class_get_fields");
        g_il2.classGetFieldFromName = dlsym(g_il2cppHandle, "il2cpp_class_get_field_from_name");
        g_il2.methodGetName = dlsym(g_il2cppHandle, "il2cpp_method_get_name");
        g_il2.methodGetParamCount = dlsym(g_il2cppHandle, "il2cpp_method_get_param_count");
        g_il2.methodGetFlags = dlsym(g_il2cppHandle, "il2cpp_method_get_flags");
        g_il2.methodGetParam = dlsym(g_il2cppHandle, "il2cpp_method_get_param");
        g_il2.methodGetParamName = dlsym(g_il2cppHandle, "il2cpp_method_get_param_name");
        g_il2.methodGetReturnType = dlsym(g_il2cppHandle, "il2cpp_method_get_return_type");
        g_il2.fieldGetName = dlsym(g_il2cppHandle, "il2cpp_field_get_name");
        g_il2.fieldGetOffset = dlsym(g_il2cppHandle, "il2cpp_field_get_offset");
        g_il2.fieldStaticGet = dlsym(g_il2cppHandle, "il2cpp_field_static_get_value");
        g_il2.fieldStaticSet = dlsym(g_il2cppHandle, "il2cpp_field_static_set_value");
        g_il2.stringNew = dlsym(g_il2cppHandle, "il2cpp_string_new");
        g_il2.stringChars = dlsym(g_il2cppHandle, "il2cpp_string_chars");
        g_il2.stringLength = dlsym(g_il2cppHandle, "il2cpp_string_length");
        g_il2.threadAttach = dlsym(g_il2cppHandle, "il2cpp_thread_attach");
        g_il2.threadCurrent = dlsym(g_il2cppHandle, "il2cpp_thread_current");
        g_il2.threadDetach = dlsym(g_il2cppHandle, "il2cpp_thread_detach");
        g_il2.objectNew = dlsym(g_il2cppHandle, "il2cpp_object_new");
        g_il2.runtimeInvoke = dlsym(g_il2cppHandle, "il2cpp_runtime_invoke");
        g_il2.typeGetName = dlsym(g_il2cppHandle, "il2cpp_type_get_name");
        g_il2.free = dlsym(g_il2cppHandle, "il2cpp_free");
        if (g_il2.threadAttach && g_il2.domainGet) {
            auto domGet = reinterpret_cast<void* (*)()>(g_il2.domainGet);
            if (!g_il2.domain) g_il2.domain = domGet();
            if (g_il2.domain) reinterpret_cast<void* (*)(void*)>(g_il2.threadAttach)(g_il2.domain);
        }
    }
    g_ready.store(true);
    LOGI("larplua initialized (root=%s, scripts=%s, %dx%d)",
        rootDir ? rootDir : "?", scriptsDir ? scriptsDir : "?", screenW, screenH);
    return 0;
}

LARP_LUA_EXPORT int larplua_run(const char* code, const char* name) {
    if (!g_ready.load() || !g_state) return -1;
    std::lock_guard<std::mutex> g(g_stateLock);
    if (!g_state) return -1;
    std::string chunk = name ? name : "=chunk";
    if (luaL_loadbuffer(g_state, code, strlen(code), chunk.c_str()) != LUA_OK) {
        const char* err = lua_tostring(g_state, -1);
        LOGE("load failed: %s", err ? err : "?");
        larp_lua_log("E", err ? err : "load failed");
        lua_pop(g_state, 1);
        return -2;
    }
    if (lua_pcall(g_state, 0, 1, 0) != LUA_OK) {
        const char* err = lua_tostring(g_state, -1);
        LOGE("run failed: %s", err ? err : "?");
        larp_lua_log("E", err ? err : "run failed");
        lua_pop(g_state, 1);
        return -3;
    }
    if (lua_isstring(g_state, -1)) {
        const char* res = lua_tostring(g_state, -1);
        if (res) larp_lua_log("I", res);
    }
    lua_pop(g_state, 1);
    return 0;
}

LARP_LUA_EXPORT int larplua_load_file(const char* path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    std::string code;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) code.append(buf, n);
    close(fd);
    const char* slash = strrchr(path, '/');
    const char* name = slash ? slash + 1 : path;
    return larplua_run(code.c_str(), name);
}

LARP_LUA_EXPORT void larplua_tick(float dt, int screenW, int screenH) {
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
            larp_lua_log("E", err ? err : "OnUpdate failed");
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
            larp_lua_log("E", err ? err : "DrawImGui failed");
            lua_pop(g_state, 1);
        }
    } else lua_pop(g_state, 1);
}

LARP_LUA_EXPORT void larplua_shutdown() {
    std::lock_guard<std::mutex> g(g_stateLock);
    if (g_state) {
        lua_close(g_state);
        g_state = nullptr;
    }
    g_ready.store(false);
    {
        std::lock_guard<std::mutex> hg(g_hooksLock);
        for (auto& kv : g_hooks) {
            if (kv.second->ref != LUA_NOREF && g_state) lua_unref(g_state, LUA_REGISTRYINDEX, kv.second->ref);
            delete kv.second;
        }
        g_hooks.clear();
    }
    LOGI("larplua shutdown");
}

LARP_LUA_EXPORT int larplua_register_larpui(void* sendFn, void* drawFn) {
    if (!g_ready.load() || !g_state) return -1;
    lua_pushlightuserdata(g_state, sendFn);
    lua_setfield(g_state, LUA_REGISTRYINDEX, "LarpLua.SendFn");
    lua_pushlightuserdata(g_state, drawFn);
    lua_setfield(g_state, LUA_REGISTRYINDEX, "LarpLua.DrawFn");
    return 0;
}

LARP_LUA_EXPORT int larplua_get_screen(int* w, int* h) {
    if (w) *w = (int)g_screenW;
    if (h) *h = (int)g_screenH;
    return 0;
}

LARP_LUA_EXPORT const char* larplua_get_workspace() {
    if (!g_state) return "/storage/emulated/0/Larplaucher/Workspace";
    lua_getfield(g_state, LUA_REGISTRYINDEX, "LarpLua.Workspace");
    const char* r = lua_tostring(g_state, -1);
    if (!r) r = "/storage/emulated/0/Larplaucher/Workspace";
    lua_pop(g_state, 1);
    return r;
}

LARP_LUA_EXPORT int larplua_get_shared(const char* key, char* out, int outLen) {
    if (!g_state || !key || !out || outLen <= 0) return -1;
    std::lock_guard<std::mutex> g(g_stateLock);
    lua_getglobal(g_state, "shared");
    if (!lua_istable(g_state, -1)) { lua_pop(g_state, 1); return -2; }
    lua_getfield(g_state, -1, key);
    size_t n = 0;
    const char* s = lua_tolstring(g_state, -1, &n);
    if (!s) { lua_pop(g_state, 2); return -3; }
    int copy = (int)(n < (size_t)(outLen - 1) ? n : outLen - 1);
    memcpy(out, s, copy);
    out[copy] = 0;
    lua_pop(g_state, 2);
    return copy;
}

LARP_LUA_EXPORT int larplua_set_shared(const char* key, const char* value) {
    if (!g_state || !key) return -1;
    std::lock_guard<std::mutex> g(g_stateLock);
    lua_getglobal(g_state, "shared");
    if (!lua_istable(g_state, -1)) { lua_pop(g_state, 1); return -2; }
    pushStdString(g_state, value ? value : "");
    lua_setfield(g_state, -2, key);
    lua_pop(g_state, 1);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeInit(JNIEnv* env, jclass, jstring root, jstring scripts, jstring workspace, jint w, jint h, jfloat scale) {
    const char* r = env->GetStringUTFChars(root, nullptr);
    const char* s = env->GetStringUTFChars(scripts, nullptr);
    const char* ws = env->GetStringUTFChars(workspace, nullptr);
    int rc = larplua_init(r, s, ws, w, h, scale);
    env->ReleaseStringUTFChars(root, r);
    env->ReleaseStringUTFChars(scripts, s);
    env->ReleaseStringUTFChars(workspace, ws);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeRun(JNIEnv* env, jclass, jstring code, jstring name) {
    const char* c = env->GetStringUTFChars(code, nullptr);
    const char* n = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    int rc = larplua_run(c, n);
    env->ReleaseStringUTFChars(code, c);
    if (n) env->ReleaseStringUTFChars(name, n);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeLoadFile(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    int rc = larplua_load_file(p);
    env->ReleaseStringUTFChars(path, p);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeTick(JNIEnv*, jclass, jfloat dt, jint w, jint h) {
    larplua_tick(dt, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeShutdown(JNIEnv*, jclass) {
    larplua_shutdown();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeGetScreen(JNIEnv* env, jclass) {
    jintArray arr = env->NewIntArray(2);
    if (!arr) return nullptr;
    jint data[2] = { (jint)g_screenW, (jint)g_screenH };
    env->SetIntArrayRegion(arr, 0, 2, data);
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_larp_launcher_lua_LarpLuaNative_nativeGetWorkspace(JNIEnv* env, jclass) {
    const char* ws = "/storage/emulated/0/Larplaucher/Workspace";
    if (g_state) {
        lua_getfield(g_state, LUA_REGISTRYINDEX, "LarpLua.Workspace");
        if (lua_isstring(g_state, -1)) ws = lua_tostring(g_state, -1);
        lua_pop(g_state, 1);
    }
    return env->NewStringUTF(ws);
}
