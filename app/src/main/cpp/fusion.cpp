#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <csignal>
#include <setjmp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define FUSION_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

constexpr const char* kTag = "RegisHost";
constexpr const char* kVersion = "2.0";
constexpr long kMaxLogBytes = 1024 * 1024;
constexpr int kWatchdogTicks = 200;

using DobbyHookFn = int (*)(void*, void*, void**);
using InitFn = int (*)(const char*);
using ParamCountFn = uint32_t (*)(void*);
using RuntimeInvokeFn = void* (*)(void*, void*, void**, void**);
// Assinatura real: NextCoreEntrypoint.Start(AuxPluginFolderList* folders) — o BepInEx le
// folders->Count e folders->Folders[i]; passar nullptr derruba o processo.
struct AuxPluginFolderList {
    int32_t Count;
    const char** Folders;
};
using StartFn = void (*)(AuxPluginFolderList*);
using ClrInitFn = int (*)(const char*, const char*, int, const char**, const char**, void**, unsigned int*);
using ClrDelegateFn = int (*)(void*, unsigned int, const char*, const char*, const char*, void**);
using ClrErrorWriter = void (*)(const char*);
using ClrSetErrorWriterFn = int (*)(ClrErrorWriter);

std::mutex g_lock;
DobbyHookFn g_dobby = nullptr;
void* g_il2cpp = nullptr;
InitFn g_origInit = nullptr;
ParamCountFn g_origParamCount = nullptr;
RuntimeInvokeFn g_origInvoke = nullptr;
std::atomic<bool> g_started{false};
std::atomic<bool> g_hookFired{false};
std::atomic<int> g_stage{0};
std::atomic<const char*> g_phase{"inicio"};
std::atomic<uintptr_t> g_lastHook{0};
std::atomic<int> g_lastHookRc{0};

std::string env(const char* key) {
    const char* value = getenv(key);
    return value ? std::string(value) : std::string();
}

std::string dirPath(const char* name) {
    std::string dir = env("FUSION_LOG_DIR");
    if (dir.empty()) return std::string();
    return dir + "/" + name;
}

void writeAll(int fd, const char* data, size_t size) {
    while (size > 0) {
        ssize_t n = write(fd, data, size);
        if (n <= 0) return;
        data += n;
        size -= static_cast<size_t>(n);
    }
}

void appendLine(const std::string& path, const std::string& line) {
    if (path.empty()) return;
    int fd = open(path.c_str(), O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size < kMaxLogBytes) writeAll(fd, line.data(), line.size());
    close(fd);
}

void replaceFile(const std::string& path, const std::string& text) {
    if (path.empty()) return;
    std::string tmp = path + ".tmp";
    int fd = open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    writeAll(fd, text.data(), text.size());
    close(fd);
    rename(tmp.c_str(), path.c_str());
}

std::string readSmall(const std::string& path) {
    if (path.empty()) return std::string();
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return std::string();
    char buf[64];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return std::string();
    return std::string(buf, static_cast<size_t>(n));
}

void fileLog(int prio, const char* text) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tmv;
    time_t secs = ts.tv_sec;
    localtime_r(&secs, &tmv);
    char head[48];
    char level = prio >= ANDROID_LOG_ERROR ? 'E' : (prio == ANDROID_LOG_WARN ? 'W' : 'I');
    snprintf(head, sizeof(head), "%02d:%02d:%02d.%03d %c ", tmv.tm_hour, tmv.tm_min, tmv.tm_sec,
             static_cast<int>(ts.tv_nsec / 1000000), level);
    std::string line(head);
    line += text;
    line += "\n";
    std::lock_guard<std::mutex> guard(g_lock);
    appendLine(dirPath("fusion.log"), line);
}

void logf(int prio, const char* fmt, ...) {
    char buf[1536];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_write(prio, kTag, buf);
    fileLog(prio, buf);
}

void publishStage(int stage) {
    g_stage.store(stage);
    replaceFile(dirPath("fusion.stage"), std::to_string(stage));
}

void publishMessage(const char* message) {
    replaceFile(dirPath("fusion.msg"), message ? message : "");
}

int currentStage() {
    std::string text = readSmall(dirPath("fusion.stage"));
    if (text.empty()) return g_stage.load();
    return atoi(text.c_str());
}

void failWith(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    logf(ANDROID_LOG_ERROR, "%s", buf);
    std::string message = std::string("Falha: ") + buf;
    publishMessage(message.c_str());
    publishStage(-1);
}


// ===================== DETECTOR DE CRASH =====================
// Grava um relatorio em fusion.log e crash.log (o launcher le e mostra no console).
// Usa so write()/open()/read() e buffers estaticos (seguro dentro de handler de sinal).
constexpr int kSigMax = 65;
constexpr int kMaxFrames = 24;
struct sigaction g_oldActions[kSigMax];
char g_crashPath[512];
char g_fusionPath[512];
char g_crashBuf[12288];
size_t g_crashLen = 0;
char g_mapsBuf[4096];
std::atomic<int> g_crashTid{0};
volatile sig_atomic_t g_walking = 0;
sigjmp_buf g_walkJmp;
bool g_crashInstalled = false;
char g_altStack[65536];

void cPut(const char* t) { while (*t && g_crashLen < sizeof(g_crashBuf) - 1) g_crashBuf[g_crashLen++] = *t++; }
void cHex(uint64_t v) {
    char tmp[20]; int n = 0;
    do { tmp[n++] = "0123456789abcdef"[v & 15]; v >>= 4; } while (v && n < 16);
    cPut("0x");
    while (n) { char c[2] = {tmp[--n], 0}; cPut(c); }
}
void cDec(int64_t v) {
    char tmp[24]; int n = 0;
    if (v < 0) { cPut("-"); v = -v; }
    do { tmp[n++] = static_cast<char>('0' + v % 10); v /= 10; } while (v && n < 20);
    while (n) { char c[2] = {tmp[--n], 0}; cPut(c); }
}

struct Frame { uintptr_t addr; char name[48]; uintptr_t off; bool found; };

uint64_t parseHex(const char*& p) {
    uint64_t v = 0;
    for (;; ++p) {
        char c = *p;
        if (c >= '0' && c <= '9') v = v * 16 + (c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (c - 'a' + 10);
        else break;
    }
    return v;
}
void skipTok(const char*& p) { while (*p && *p != ' ') ++p; while (*p == ' ') ++p; }

void mapsLine(char* line, Frame* fr, int n) {
    const char* p = line;
    uint64_t start = parseHex(p);
    if (*p != '-') return;
    ++p;
    uint64_t end = parseHex(p);
    while (*p == ' ') ++p;
    skipTok(p);
    uint64_t fileOff = parseHex(p);
    while (*p == ' ') ++p;
    skipTok(p); skipTok(p);
    const char* path = p;
    for (int i = 0; i < n; ++i) {
        if (fr[i].found || fr[i].addr < start || fr[i].addr >= end) continue;
        fr[i].found = true;
        fr[i].off = fr[i].addr - start + fileOff;
        const char* base = path;
        for (const char* q = path; *q; ++q) if (*q == '/') base = q + 1;
        if (!*base) base = "[anon]";
        size_t k = 0;
        while (base[k] && k < sizeof(fr[i].name) - 1) { fr[i].name[k] = base[k]; ++k; }
        fr[i].name[k] = 0;
    }
}

void resolveFrames(Frame* fr, int n) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char line[600]; size_t len = 0;
    for (;;) {
        ssize_t r = read(fd, g_mapsBuf, sizeof(g_mapsBuf));
        if (r <= 0) break;
        for (ssize_t i = 0; i < r; ++i) {
            char c = g_mapsBuf[i];
            if (c == '\n') { line[len] = 0; mapsLine(line, fr, n); len = 0; }
            else if (len < sizeof(line) - 1) line[len++] = c;
        }
    }
    close(fd);
}

void cFrame(const Frame& f) {
    if (f.found) { cPut(f.name); cPut("+"); cHex(f.off); }
    else { cHex(f.addr); }
}

const char* sigName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV"; case SIGABRT: return "SIGABRT"; case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL"; case SIGFPE: return "SIGFPE"; default: return "SINAL";
    }
}

void chainPrevious(int sig, siginfo_t* info, void* ctx) {
    struct sigaction old = g_oldActions[sig];
    if ((old.sa_flags & SA_SIGINFO) && old.sa_sigaction) { old.sa_sigaction(sig, info, ctx); return; }
    if (!(old.sa_flags & SA_SIGINFO) && old.sa_handler != SIG_DFL && old.sa_handler != SIG_IGN && old.sa_handler) {
        old.sa_handler(sig);
        return;
    }
    signal(sig, SIG_DFL);
    if (info && info->si_code <= 0) syscall(SYS_tgkill, getpid(), static_cast<pid_t>(syscall(SYS_gettid)), sig);
}

void writeReport(int sig, siginfo_t* info, void* ctx, int tid) {
    uintptr_t pc = 0, sp = 0, fp = 0, lr = 0, fault = info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
#if defined(__aarch64__)
    auto* uc = static_cast<ucontext_t*>(ctx);
    if (uc) { pc = uc->uc_mcontext.pc; sp = uc->uc_mcontext.sp; fp = uc->uc_mcontext.regs[29]; lr = uc->uc_mcontext.regs[30]; }
#else
    (void)ctx;
#endif
    constexpr uintptr_t kMask = 0x0000FFFFFFFFFFFFull;
    Frame fr[kMaxFrames + 2];
    int n = 0;
    memset(fr, 0, sizeof(fr));
    if (pc) fr[n++].addr = pc & kMask;
    if (lr && (lr & kMask) != (pc & kMask)) fr[n++].addr = lr & kMask;
    if (fp && sp) {
        g_walking = 1;
        if (sigsetjmp(g_walkJmp, 1) == 0) {
            uintptr_t cur = fp;
            for (int i = 0; i < kMaxFrames && n < kMaxFrames; ++i) {
                if ((cur & 0xF) || cur < sp || cur - sp > 0x2000000) break;
                uintptr_t next = reinterpret_cast<uintptr_t*>(cur)[0];
                uintptr_t ret = reinterpret_cast<uintptr_t*>(cur)[1] & kMask;
                if (!ret) break;
                fr[n++].addr = ret;
                if (next <= cur) break;
                cur = next;
            }
        }
        g_walking = 0;
    }
    resolveFrames(fr, n);

    char name[20] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(name), 0, 0, 0);
    g_crashLen = 0;
    cPut("==== CRASH NATIVO (Fatal) ====\n");
    cPut("sinal: "); cPut(sigName(sig)); cPut(" ("); cDec(sig); cPut(") code="); cDec(info ? info->si_code : 0);
    cPut(" addr="); cHex(fault); cPut(" epoch="); cDec(static_cast<int64_t>(time(nullptr))); cPut("\n");
    cPut("thread: tid="); cDec(tid); cPut(" nome="); cPut(name); cPut("\n");
    cPut("etapa: "); cPut(g_phase.load()); cPut(" (stage="); cDec(g_stage.load()); cPut(")\n");
    uintptr_t lh = g_lastHook.load();
    if (lh) { cPut("ultimo hook: "); cHex(lh); cPut(" rc="); cDec(g_lastHookRc.load()); cPut("\n"); }
    for (int i = 0; i < n; ++i) { cPut("#"); cDec(i); cPut(" "); cFrame(fr[i]); cPut("\n"); }
    if (sig == SIGABRT) cPut("dica: abort() - normalmente excecao .NET sem tratamento ou assert; veja 'Unhandled exception' acima.\n");
    else if (sig == SIGSEGV && fault < 0x10000) cPut("dica: ponteiro nulo (NULL+offset). Hook/trampolim nulo? veja 'ultimo hook'.\n");
    else if (sig == SIGSEGV) cPut("dica: acesso invalido a memoria. Mod patchando metodo/offset de outra versao do jogo?\n");
    else if (sig == SIGILL) cPut("dica: instrucao invalida - codigo corrompido por hook/patch.\n");
    cPut("==== FIM DO CRASH ====\n");

    if (g_fusionPath[0]) {
        int fd = open(g_fusionPath, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
        if (fd >= 0) { writeAll(fd, g_crashBuf, g_crashLen); close(fd); }
    }
    if (g_crashPath[0]) {
        int fd = open(g_crashPath, O_WRONLY | O_TRUNC | O_CREAT | O_CLOEXEC, 0644);
        if (fd >= 0) { writeAll(fd, g_crashBuf, g_crashLen); close(fd); }
    }
    size_t start = 0;
    for (size_t i = 0; i < g_crashLen; ++i) {
        if (g_crashBuf[i] == '\n') { g_crashBuf[i] = 0; __android_log_write(ANDROID_LOG_FATAL, kTag, g_crashBuf + start); start = i + 1; }
    }
}

void crashHandler(int sig, siginfo_t* info, void* ctx) {
    if (g_walking) siglongjmp(g_walkJmp, 1);
    int tid = static_cast<int>(syscall(SYS_gettid));
    int expected = 0;
    if (!g_crashTid.compare_exchange_strong(expected, tid)) {
        if (expected != tid) {
            for (int i = 0; i < 30; ++i) usleep(100000);
        }
        chainPrevious(sig, info, ctx);
        return;
    }
    writeReport(sig, info, ctx, tid);
    chainPrevious(sig, info, ctx);
    g_crashTid.store(0);
}

void installCrashHandler() {
    if (g_crashInstalled) return;
    g_crashInstalled = true;
    std::string crash = dirPath("crash.log"), fl = dirPath("fusion.log");
    snprintf(g_crashPath, sizeof(g_crashPath), "%s", crash.c_str());
    snprintf(g_fusionPath, sizeof(g_fusionPath), "%s", fl.c_str());
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_altStack;
    ss.ss_size = sizeof(g_altStack);
    sigaltstack(&ss, nullptr);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
    static const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (int sig : kSignals) sigaction(sig, &sa, &g_oldActions[sig]);
    logf(ANDROID_LOG_INFO, "detector de crash instalado");
}
// =============================================================

void* openLibrary(const char* name) {
    void* handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
    if (handle) return handle;
    const char* firstError = dlerror();
    std::string reason = firstError ? firstError : "?";
    std::string dirs = env("REGIS_LIB_DIRS");
    size_t start = 0;
    while (start <= dirs.size()) {
        size_t end = dirs.find(':', start);
        if (end == std::string::npos) end = dirs.size();
        if (end > start) {
            std::string path = dirs.substr(start, end - start) + "/" + name;
            if (access(path.c_str(), R_OK) == 0) {
                handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
                if (handle) {
                    logf(ANDROID_LOG_INFO, "%s aberta por caminho: %s", name, path.c_str());
                    return handle;
                }
                const char* err = dlerror();
                logf(ANDROID_LOG_WARN, "dlopen %s falhou: %s", path.c_str(), err ? err : "?");
            }
        }
        start = end + 1;
    }
    logf(ANDROID_LOG_WARN, "dlopen %s falhou: %s", name, reason.c_str());
    return nullptr;
}

const char* baseName(const char* path) {
    if (!path) return "";
    const char* slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

uint32_t safeParamCount(void* method) {
    if (!method) return 0;
    return g_origParamCount(method);
}

void* safeRuntimeInvoke(void* method, void* object, void** params, void** exception) {
    if (!method) {
        if (exception) *exception = nullptr;
        return nullptr;
    }
    return g_origInvoke(method, object, params, exception);
}

struct PatchResult {
    int paramCount = 0;
    int runtimeInvoke = 0;
};

int patchCallback(struct dl_phdr_info* info, size_t, void* data) {
    const char* name = info->dlpi_name;
    if (!name || strcmp(baseName(name), "libunity.so") != 0) return 0;
    auto* result = static_cast<PatchResult*>(data);
    const uintptr_t page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_W)) continue;
        uintptr_t begin = (info->dlpi_addr + ph.p_vaddr + 7) & ~static_cast<uintptr_t>(7);
        uintptr_t end = info->dlpi_addr + ph.p_vaddr + ph.p_memsz;
        for (uintptr_t cursor = begin; cursor + 8 <= end; cursor += 8) {
            auto* slot = reinterpret_cast<uintptr_t*>(cursor);
            if (g_origParamCount && *slot == reinterpret_cast<uintptr_t>(g_origParamCount)) {
                mprotect(reinterpret_cast<void*>(cursor & ~(page - 1)), page, PROT_READ | PROT_WRITE);
                *slot = reinterpret_cast<uintptr_t>(&safeParamCount);
                result->paramCount++;
            } else if (g_origInvoke && *slot == reinterpret_cast<uintptr_t>(g_origInvoke)) {
                mprotect(reinterpret_cast<void*>(cursor & ~(page - 1)), page, PROT_READ | PROT_WRITE);
                *slot = reinterpret_cast<uintptr_t>(&safeRuntimeInvoke);
                result->runtimeInvoke++;
            }
        }
    }
    return 1;
}

void protectUnity() {
    g_origParamCount = reinterpret_cast<ParamCountFn>(dlsym(g_il2cpp, "il2cpp_method_get_param_count"));
    g_origInvoke = reinterpret_cast<RuntimeInvokeFn>(dlsym(g_il2cpp, "il2cpp_runtime_invoke"));
    PatchResult result;
    dl_iterate_phdr(patchCallback, &result);
    logf(ANDROID_LOG_INFO, "protecao de nulos na libunity: param_count=%d runtime_invoke=%d", result.paramCount,
         result.runtimeInvoke);
}

void clrError(const char* message) {
    logf(ANDROID_LOG_ERROR, "coreclr: %s", message ? message : "");
}

std::string buildTrustedAssemblies(const std::string& dotnetDir) {
    std::string list;
    DIR* dir = opendir(dotnetDir.c_str());
    if (!dir) {
        logf(ANDROID_LOG_ERROR, "nao abri %s", dotnetDir.c_str());
        return list;
    }
    while (dirent* entry = readdir(dir)) {
        size_t len = strlen(entry->d_name);
        if (len < 4 || strcmp(entry->d_name + len - 4, ".dll") != 0) continue;
        if (!list.empty()) list += ":";
        list += dotnetDir + "/" + entry->d_name;
    }
    closedir(dir);
    return list;
}

void runBepInEx() {
    std::string dotnetDir = env("REGIS_DOTNET_DIR");
    std::string libDirs = env("REGIS_LIB_DIRS");
    std::string bepinexDir = env("FUSION_BEPINEX_PATH");
    std::string gameBinary = env("FUSION_GAME_BINARY");
    if (dotnetDir.empty() || libDirs.empty() || bepinexDir.empty() || gameBinary.empty()) {
        failWith("variaveis de ambiente incompletas (dotnet=%d libs=%d bepinex=%d binario=%d)",
                 !dotnetDir.empty(), !libDirs.empty(), !bepinexDir.empty(), !gameBinary.empty());
        return;
    }
    g_phase = "coreclr_initialize";
    publishMessage("Iniciando CoreCLR");
    void* clr = openLibrary("libcoreclr.so");
    if (!clr) {
        failWith("libcoreclr.so nao pode ser aberta (veja fusion.log)");
        return;
    }
    auto clrInit = reinterpret_cast<ClrInitFn>(dlsym(clr, "coreclr_initialize"));
    auto clrDelegate = reinterpret_cast<ClrDelegateFn>(dlsym(clr, "coreclr_create_delegate"));
    auto clrWriter = reinterpret_cast<ClrSetErrorWriterFn>(dlsym(clr, "coreclr_set_error_writer"));
    if (!clrInit || !clrDelegate) {
        failWith("libcoreclr.so sem coreclr_initialize/coreclr_create_delegate");
        return;
    }
    if (clrWriter) clrWriter(&clrError);

    std::string appPaths = bepinexDir + "/core";
    std::string trusted = buildTrustedAssemblies(dotnetDir);
    const char* keys[] = {"TRUSTED_PLATFORM_ASSEMBLIES", "APP_PATHS", "NATIVE_DLL_SEARCH_DIRECTORIES",
                          "RUNTIME_IDENTIFIER", "System.Globalization.Invariant"};
    const char* values[] = {trusted.c_str(), appPaths.c_str(), libDirs.c_str(), "android-arm64", "true"};
    void* host = nullptr;
    unsigned int domain = 0;
    int rc = clrInit(gameBinary.c_str(), "RegisLauncher", 5, keys, values, &host, &domain);
    if (rc < 0) {
        failWith("coreclr_initialize falhou: 0x%08x", static_cast<unsigned int>(rc));
        return;
    }
    logf(ANDROID_LOG_INFO, "CoreCLR no ar");
    publishMessage("CoreCLR no ar");

    // O nome do tipo de entrada muda conforme o build do BepInEx: o do Regis usa
    // FusionCoreEntrypoint, o BepInEx.Android.Launcher usa NextCoreEntrypoint.
    // 0x80131522 = COR_E_TYPELOAD (tipo nao existe no assembly).
    static const char* kEntryTypes[] = {
        "BepInEx.Unity.IL2CPP.NextCoreEntrypoint",
        "BepInEx.Unity.IL2CPP.FusionCoreEntrypoint",
    };
    g_phase = "coreclr_create_delegate";
    void* entry = nullptr;
    rc = -1;
    for (const char* type : kEntryTypes) {
        entry = nullptr;
        rc = clrDelegate(host, domain, "BepInEx.Unity.IL2CPP", type, "Start", &entry);
        logf(ANDROID_LOG_INFO, "create_delegate %s.Start: 0x%08x", type, static_cast<unsigned int>(rc));
        if (rc >= 0 && entry) break;
    }
    if (rc < 0 || !entry) {
        failWith("nao achei NextCoreEntrypoint/FusionCoreEntrypoint.Start em BepInEx.Unity.IL2CPP: 0x%08x",
                 static_cast<unsigned int>(rc));
        return;
    }
    // stdout/stderr de apps Android vao para /dev/null. O .NET escreve "Unhandled exception. ..."
    // no stderr antes de abortar, entao redirecionamos para o fusion.log (sobrevive ao crash).
    std::string logPath = dirPath("fusion.log");
    if (!logPath.empty()) {
        int fd = open(logPath.c_str(), O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
        if (fd >= 0) {
            dup2(fd, 1);
            dup2(fd, 2);
            close(fd);
        }
    }
    // Onde o NextCoreEntrypoint grava a excecao quando o BepInEx falha ao iniciar.
    setenv("BEPINEX_PRELOADER_LOG", (bepinexDir + "/preloader.log").c_str(), 0);
    static const char* kEnvNames[] = {"FUSION_BEPINEX_PATH", "FUSION_GAME_BINARY", "BEPINEX_GAME_ASSEMBLY_PATH",
                                      "FUSION_GAME_DATA_DIR", "FUSION_APP_DATA_DIR", "FUSION_UNITY_VERSION",
                                      "BEPINEX_PRELOADER_LOG", "HOME", "TMPDIR"};
    for (const char* name : kEnvNames) logf(ANDROID_LOG_INFO, "env %s=%s", name, env(name).c_str());
    // O Preloader.Core (EnvVars.LoadVars) le NEXT_*, nao FUSION_*.
    static const char* kMap[][2] = {
        {"NEXT_BEPINEX_PATH", "FUSION_BEPINEX_PATH"},
        {"NEXT_GAME_BINARY", "FUSION_GAME_BINARY"},
        {"NEXT_GAME_DATA_DIR", "FUSION_GAME_DATA_DIR"},
        {"NEXT_APP_DATA_DIR", "FUSION_APP_DATA_DIR"},
        {"NEXT_UNITY_VERSION", "FUSION_UNITY_VERSION"},
    };
    for (auto& pair : kMap) {
        std::string value = env(pair[1]);
        if (value.empty()) logf(ANDROID_LOG_WARN, "%s vazio; %s nao sera definido", pair[1], pair[0]);
        else setenv(pair[0], value.c_str(), 1);
        logf(ANDROID_LOG_INFO, "env %s=%s", pair[0], env(pair[0]).c_str());
    }
    std::string appData = env("NEXT_APP_DATA_DIR");
    if (!appData.empty()) mkdir(appData.c_str(), 0755);
    static AuxPluginFolderList noAux = {0, nullptr};
    g_phase = "BepInEx.Start (carregando plugins/mods)";
    logf(ANDROID_LOG_INFO, "chamando o BepInEx");
    reinterpret_cast<StartFn>(entry)(&noAux);
    g_phase = "jogo rodando (BepInEx ja devolveu o controle)";
    logf(ANDROID_LOG_INFO, "BepInEx devolveu o controle");
    if (currentStage() >= 0 && currentStage() < 2) publishStage(2);
}

int hookedInit(const char* domainName) {
    g_phase = "il2cpp_init";
    int result = g_origInit(domainName);
    g_hookFired.store(true);
    if (g_started.exchange(true)) return result;
    logf(ANDROID_LOG_INFO, "il2cpp_init retornou %d", result);
    protectUnity();
    runBepInEx();
    return result;
}

int listModules(struct dl_phdr_info* info, size_t, void*) {
    const char* base = baseName(info->dlpi_name);
    if (strcmp(base, "libil2cpp.so") == 0 || strcmp(base, "libunity.so") == 0 ||
        strcmp(base, "libfusion.so") == 0 || strcmp(base, "libcoreclr.so") == 0 ||
        strcmp(base, "libmain.so") == 0) {
        logf(ANDROID_LOG_WARN, "modulo %s base=%p caminho=%s", base, reinterpret_cast<void*>(info->dlpi_addr),
             info->dlpi_name);
    }
    return 0;
}

void* watchdog(void*) {
    for (int i = 0; i < kWatchdogTicks && !g_hookFired.load(); ++i) usleep(100000);
    if (g_hookFired.load()) return nullptr;
    void* init = g_il2cpp ? dlsym(g_il2cpp, "il2cpp_init") : nullptr;
    logf(ANDROID_LOG_WARN, "il2cpp_init nao foi chamado em 20s; gancho em %p", init);
    dl_iterate_phdr(listModules, nullptr);
    publishMessage("il2cpp_init nao foi chamado em 20s (veja fusion.log)");
    return nullptr;
}

void startWatchdog() {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, watchdog, nullptr) == 0) pthread_detach(thread);
}

bool resolveDobby() {
    void* handle = openLibrary("libdobby.so");
    if (!handle) return false;
    g_dobby = reinterpret_cast<DobbyHookFn>(dlsym(handle, "DobbyHook"));
    return g_dobby != nullptr;
}

int levelToPriority(int level) {
    if (level & 3) return ANDROID_LOG_ERROR;
    if (level & 4) return ANDROID_LOG_WARN;
    if (level & 32) return ANDROID_LOG_DEBUG;
    return ANDROID_LOG_INFO;
}

}

FUSION_EXPORT void set_loader_stage(uint8_t stage) {
    logf(ANDROID_LOG_INFO, "etapa do carregador: %d", stage);
    publishStage(stage);
}

FUSION_EXPORT void set_loader_message(const char* message) {
    const char* text = message ? message : "";
    logf(ANDROID_LOG_INFO, "%s", text);
    publishMessage(text);
}

FUSION_EXPORT void write_log_level(int level, const char* message) {
    const char* text = message ? message : "";
    int prio = levelToPriority(level);
    __android_log_write(prio, "BepInEx", text);
    std::string line = std::string("[BepInEx] ") + text;
    fileLog(prio, line.c_str());
}

FUSION_EXPORT void* hook(void* target, void* replacement, bool specialReturnBuffer) {
    if (specialReturnBuffer) logf(ANDROID_LOG_INFO, "hook %p pede specialReturnBuffer (ainda sem tratamento)", target);
    if (!g_dobby && !resolveDobby()) {
        logf(ANDROID_LOG_ERROR, "DobbyHook indisponivel");
        return nullptr;
    }
    void* original = nullptr;
    int rc = g_dobby(target, replacement, &original);
    g_lastHook = reinterpret_cast<uintptr_t>(target);
    g_lastHookRc = rc;
    if (rc != 0) {
        logf(ANDROID_LOG_ERROR, "DobbyHook(%p) falhou: %d", target, rc);
        return nullptr;
    }
    return original;
}

FUSION_EXPORT void unhook() {}

FUSION_EXPORT int JNI_OnLoad(void*, void*) {
    constexpr int kJniVersion = 0x00010006;
    std::string logPath = dirPath("fusion.log");
    if (!logPath.empty()) unlink(logPath.c_str());
    g_phase = "JNI_OnLoad";
    installCrashHandler();
    publishStage(1);
    publishMessage("Fusion carregado");
    logf(ANDROID_LOG_INFO, "fusion %s carregado (pid=%d)", kVersion, getpid());

    std::string gameBinary = env("FUSION_GAME_BINARY");
    if (gameBinary.empty()) {
        failWith("FUSION_GAME_BINARY nao definido");
        return kJniVersion;
    }
    if (!resolveDobby()) {
        failWith("libdobby.so/DobbyHook indisponivel");
        return kJniVersion;
    }
    g_il2cpp = dlopen(gameBinary.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g_il2cpp) {
        const char* err = dlerror();
        failWith("dlopen %s: %s", gameBinary.c_str(), err ? err : "?");
        return kJniVersion;
    }
    void* init = dlsym(g_il2cpp, "il2cpp_init");
    if (!init) {
        failWith("il2cpp_init nao exportado");
        return kJniVersion;
    }
    int rc = g_dobby(init, reinterpret_cast<void*>(&hookedInit), reinterpret_cast<void**>(&g_origInit));
    logf(ANDROID_LOG_INFO, "gancho em il2cpp_init %p: %d (libil2cpp=%p)", init, rc, g_il2cpp);
    if (rc != 0 || !g_origInit) {
        failWith("DobbyHook em il2cpp_init falhou: %d", rc);
        return kJniVersion;
    }
    publishMessage("Aguardando il2cpp_init");
    startWatchdog();
    return kJniVersion;
}
