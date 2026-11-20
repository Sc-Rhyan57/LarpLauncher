#include <jni.h>
#include <link.h>
#include <elf.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <atomic>
#include <csetjmp>
#include <csignal>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LarpDevKit", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "LarpDevKit", __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGW(...) LOGI(__VA_ARGS__)
#endif

namespace {

struct Module {
    uintptr_t bias = 0;
    const ElfW(Phdr)* phdr = nullptr;
    int phnum = 0;
    uintptr_t end = 0;
    bool found = false;
};

int findModuleCallback(struct dl_phdr_info* info, size_t, void* data) {
    auto* module = static_cast<Module*>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    const char* slash = strrchr(info->dlpi_name, '/');
    const char* base = slash ? slash + 1 : info->dlpi_name;
    if (strcmp(base, "libil2cpp.so") != 0) return 0;
    module->bias = info->dlpi_addr;
    module->phdr = info->dlpi_phdr;
    module->phnum = info->dlpi_phnum;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_LOAD) {
            uintptr_t top = info->dlpi_addr + info->dlpi_phdr[i].p_vaddr + info->dlpi_phdr[i].p_memsz;
            if (top > module->end) module->end = top;
        }
    }
    module->found = true;
    return 1;
}

struct SymbolTable {
    uintptr_t bias = 0;
    const ElfW(Sym)* symtab = nullptr;
    const char* strtab = nullptr;
    const uint32_t* gnuHash = nullptr;
    const uint32_t* sysvHash = nullptr;

    uintptr_t fix(uintptr_t value) const {
        return value < bias ? value + bias : value;
    }

    bool load(const Module& module) {
        bias = module.bias;
        const ElfW(Dyn)* dyn = nullptr;
        for (int i = 0; i < module.phnum; ++i) {
            if (module.phdr[i].p_type == PT_DYNAMIC) {
                dyn = reinterpret_cast<const ElfW(Dyn)*>(bias + module.phdr[i].p_vaddr);
                break;
            }
        }
        if (!dyn) return false;
        for (; dyn->d_tag != DT_NULL; ++dyn) {
            switch (dyn->d_tag) {
                case DT_SYMTAB: symtab = reinterpret_cast<const ElfW(Sym)*>(fix(dyn->d_un.d_ptr)); break;
                case DT_STRTAB: strtab = reinterpret_cast<const char*>(fix(dyn->d_un.d_ptr)); break;
                case DT_GNU_HASH: gnuHash = reinterpret_cast<const uint32_t*>(fix(dyn->d_un.d_ptr)); break;
                case DT_HASH: sysvHash = reinterpret_cast<const uint32_t*>(fix(dyn->d_un.d_ptr)); break;
                default: break;
            }
        }
        return symtab && strtab && (gnuHash || sysvHash);
    }

    static uint32_t gnuHashOf(const char* s) {
        uint32_t h = 5381;
        for (; *s; ++s) h = h * 33 + static_cast<uint8_t>(*s);
        return h;
    }

    static uint32_t sysvHashOf(const char* s) {
        uint32_t h = 0;
        for (; *s; ++s) {
            h = (h << 4) + static_cast<uint8_t>(*s);
            uint32_t g = h & 0xf0000000;
            if (g) h ^= g >> 24;
            h &= ~g;
        }
        return h;
    }

    bool matches(uint32_t index, const char* name) const {
        const ElfW(Sym)& sym = symtab[index];
        if (sym.st_shndx == SHN_UNDEF || sym.st_value == 0) return false;
        return strcmp(strtab + sym.st_name, name) == 0;
    }

    void* find(const char* name) const {
        if (gnuHash) {
            uint32_t nbuckets = gnuHash[0];
            uint32_t symoffset = gnuHash[1];
            uint32_t bloomSize = gnuHash[2];
            const uint64_t* bloom = reinterpret_cast<const uint64_t*>(gnuHash + 4);
            const uint32_t* buckets = reinterpret_cast<const uint32_t*>(bloom + bloomSize);
            const uint32_t* chain = buckets + nbuckets;
            uint32_t h = gnuHashOf(name);
            uint32_t index = buckets[h % nbuckets];
            if (index >= symoffset) {
                for (;; ++index) {
                    uint32_t entry = chain[index - symoffset];
                    if (((entry ^ h) >> 1) == 0 && matches(index, name)) {
                        return reinterpret_cast<void*>(bias + symtab[index].st_value);
                    }
                    if (entry & 1) break;
                }
            }
            return nullptr;
        }
        uint32_t nbucket = sysvHash[0];
        const uint32_t* buckets = sysvHash + 2;
        const uint32_t* chain = buckets + nbucket;
        for (uint32_t index = buckets[sysvHashOf(name) % nbucket]; index != 0; index = chain[index]) {
            if (matches(index, name)) {
                return reinterpret_cast<void*>(bias + symtab[index].st_value);
            }
        }
        return nullptr;
    }
};

using DomainGet = void* (*)();
using DomainGetAssemblies = void** (*)(void*, size_t*);
using AssemblyGetImage = void* (*)(void*);
using ImageGetName = const char* (*)(void*);
using ImageGetClassCount = size_t (*)(void*);
using ImageGetClass = void* (*)(void*, size_t);
using ClassGetString = const char* (*)(void*);
using ClassGetDeclaringType = void* (*)(void*);
using IteratorGet = void* (*)(void*, void**);
using MethodGetString = const char* (*)(void*);
using MethodGetParamCount = uint32_t (*)(void*);
using MethodGetParam = void* (*)(void*, uint32_t);
using MethodGetReturnType = void* (*)(void*);
using MethodGetParamName = const char* (*)(void*, uint32_t);
using MethodGetFlags = uint32_t (*)(void*, uint32_t*);
using TypeGetName = char* (*)(void*);
using FieldGetName = const char* (*)(void*);
using FieldGetOffset = size_t (*)(void*);
using FieldGetType = void* (*)(void*);
using FieldGetFlags = int (*)(void*);
using ThreadAttach = void* (*)(void*);
using FreeFn = void (*)(void*);

struct Api {
    DomainGet domainGet = nullptr;
    DomainGetAssemblies domainGetAssemblies = nullptr;
    AssemblyGetImage assemblyGetImage = nullptr;
    ImageGetName imageGetName = nullptr;
    ImageGetClassCount imageGetClassCount = nullptr;
    ImageGetClass imageGetClass = nullptr;
    ClassGetString classGetName = nullptr;
    ClassGetString classGetNamespace = nullptr;
    ClassGetDeclaringType classGetDeclaringType = nullptr;
    IteratorGet classGetMethods = nullptr;
    IteratorGet classGetFields = nullptr;
    MethodGetString methodGetName = nullptr;
    MethodGetParamCount methodGetParamCount = nullptr;
    MethodGetParam methodGetParam = nullptr;
    MethodGetReturnType methodGetReturnType = nullptr;
    MethodGetParamName methodGetParamName = nullptr;
    MethodGetFlags methodGetFlags = nullptr;
    TypeGetName typeGetName = nullptr;
    FieldGetName fieldGetName = nullptr;
    FieldGetOffset fieldGetOffset = nullptr;
    FieldGetType fieldGetType = nullptr;
    FieldGetFlags fieldGetFlags = nullptr;
    ThreadAttach threadAttach = nullptr;
    FreeFn free = nullptr;
    uintptr_t moduleBase = 0;
    uintptr_t moduleEnd = 0;
};

template <typename T>
bool bind(const SymbolTable& table, const char* name, T& out, bool required) {
    out = reinterpret_cast<T>(table.find(name));
    if (!out && required) {
        LOGW("símbolo ausente: %s", name);
        return false;
    }
    return true;
}

bool bindApi(const SymbolTable& t, Api& a) {
    bool ok = true;
    ok &= bind(t, "il2cpp_domain_get", a.domainGet, true);
    ok &= bind(t, "il2cpp_domain_get_assemblies", a.domainGetAssemblies, true);
    ok &= bind(t, "il2cpp_assembly_get_image", a.assemblyGetImage, true);
    ok &= bind(t, "il2cpp_image_get_name", a.imageGetName, true);
    ok &= bind(t, "il2cpp_image_get_class_count", a.imageGetClassCount, true);
    ok &= bind(t, "il2cpp_image_get_class", a.imageGetClass, true);
    ok &= bind(t, "il2cpp_class_get_name", a.classGetName, true);
    ok &= bind(t, "il2cpp_class_get_namespace", a.classGetNamespace, true);
    ok &= bind(t, "il2cpp_class_get_methods", a.classGetMethods, true);
    ok &= bind(t, "il2cpp_class_get_fields", a.classGetFields, true);
    ok &= bind(t, "il2cpp_method_get_name", a.methodGetName, true);
    ok &= bind(t, "il2cpp_field_get_name", a.fieldGetName, true);
    ok &= bind(t, "il2cpp_field_get_offset", a.fieldGetOffset, true);
    bind(t, "il2cpp_class_get_declaring_type", a.classGetDeclaringType, false);
    bind(t, "il2cpp_method_get_param_count", a.methodGetParamCount, false);
    bind(t, "il2cpp_method_get_param", a.methodGetParam, false);
    bind(t, "il2cpp_method_get_return_type", a.methodGetReturnType, false);
    bind(t, "il2cpp_method_get_param_name", a.methodGetParamName, false);
    bind(t, "il2cpp_method_get_flags", a.methodGetFlags, false);
    bind(t, "il2cpp_type_get_name", a.typeGetName, false);
    bind(t, "il2cpp_field_get_type", a.fieldGetType, false);
    bind(t, "il2cpp_field_get_flags", a.fieldGetFlags, false);
    bind(t, "il2cpp_thread_attach", a.threadAttach, false);
    bind(t, "il2cpp_free", a.free, false);
    a.moduleBase = t.bias;
    return ok;
}

std::string clean(const char* text) {
    std::string out = text ? text : "";
    for (char& c : out) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return out;
}

std::string typeName(const Api& api, void* type) {
    if (!type || !api.typeGetName) return "?";
    char* raw = api.typeGetName(type);
    if (!raw) return "?";
    std::string name = clean(raw);
    if (api.free) api.free(raw);
    return name;
}

std::string ownerName(const Api& api, void* klass) {
    std::string name = clean(api.classGetName(klass));
    void* current = klass;
    int depth = 0;
    while (api.classGetDeclaringType && depth++ < 8) {
        void* outer = api.classGetDeclaringType(current);
        if (!outer || outer == current) break;
        name = clean(api.classGetName(outer)) + "." + name;
        current = outer;
    }
    const char* ns = api.classGetNamespace(current);
    if (ns && ns[0]) name = clean(ns) + "." + name;
    return name;
}

std::string methodSignature(const Api& api, void* method) {
    std::string out;
    if (api.methodGetReturnType) out += typeName(api, api.methodGetReturnType(method));
    out += " (";
    if (api.methodGetParamCount && api.methodGetParam) {
        uint32_t count = api.methodGetParamCount(method);
        for (uint32_t i = 0; i < count && i < 32; ++i) {
            if (i) out += ", ";
            out += typeName(api, api.methodGetParam(method, i));
            if (api.methodGetParamName) {
                const char* pn = api.methodGetParamName(method, i);
                if (pn && pn[0]) out += std::string(" ") + clean(pn);
            }
        }
    }
    out += ")";
    return out;
}

std::atomic<bool> running{false};

int dumpAll(const char* path, bool force) {
    Module module;
    dl_iterate_phdr(findModuleCallback, &module);
    if (!module.found) return -1;

    SymbolTable table;
    if (!table.load(module)) return -2;
    Api api;
    if (!bindApi(table, api)) return -2;
    api.moduleEnd = module.end;

    void* domain = api.domainGet();
    if (!domain) return -3;
    if (api.threadAttach) api.threadAttach(domain);

    size_t assemblyCount = 0;
    void** assemblies = api.domainGetAssemblies(domain, &assemblyCount);
    if (!assemblies || assemblyCount == 0) return -3;

    bool hasGame = false;
    for (size_t i = 0; i < assemblyCount; ++i) {
        void* image = api.assemblyGetImage(assemblies[i]);
        const char* name = image ? api.imageGetName(image) : nullptr;
        if (name && strncmp(name, "Assembly-CSharp", 15) == 0) hasGame = true;
    }
    if (!hasGame && !force) return -4;

    std::string partPath = std::string(path) + ".part";
    FILE* file = fopen(partPath.c_str(), "w");
    if (!file) return -5;

    int lines = 0;
    for (size_t i = 0; i < assemblyCount; ++i) {
        void* image = api.assemblyGetImage(assemblies[i]);
        if (!image) continue;
        std::string imageName = clean(api.imageGetName(image));
        size_t classCount = api.imageGetClassCount(image);
        for (size_t c = 0; c < classCount; ++c) {
            void* klass = api.imageGetClass(image, c);
            if (!klass) continue;
            std::string owner = ownerName(api, klass);
            fprintf(file, "C\t%s\t%s\t%s\t\t0x%llx\t\t\n", imageName.c_str(), owner.c_str(),
                    clean(api.classGetName(klass)).c_str(),
                    static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(klass)));
            ++lines;

            void* iter = nullptr;
            while (void* method = api.classGetMethods(klass, &iter)) {
                uintptr_t pointer = *reinterpret_cast<uintptr_t*>(method);
                bool isStatic = false;
                if (api.methodGetFlags) {
                    uint32_t implFlags = 0;
                    isStatic = (api.methodGetFlags(method, &implFlags) & 0x10) != 0;
                }
                std::string signature = methodSignature(api, method);
                if (pointer >= api.moduleBase && pointer < api.moduleEnd) {
                    fprintf(file, "M\t%s\t%s\t%s\t%s\t0x%llx\t\t%s\n", imageName.c_str(), owner.c_str(),
                            clean(api.methodGetName(method)).c_str(), signature.c_str(),
                            static_cast<unsigned long long>(pointer - api.moduleBase), isStatic ? "S" : "");
                } else {
                    fprintf(file, "M\t%s\t%s\t%s\t%s\t\t\t%s\n", imageName.c_str(), owner.c_str(),
                            clean(api.methodGetName(method)).c_str(), signature.c_str(), isStatic ? "S" : "");
                }
                ++lines;
            }

            iter = nullptr;
            while (void* field = api.classGetFields(klass, &iter)) {
                bool isStatic = api.fieldGetFlags && (api.fieldGetFlags(field) & 0x10) != 0;
                std::string type = api.fieldGetType ? typeName(api, api.fieldGetType(field)) : "?";
                fprintf(file, "F\t%s\t%s\t%s\t%s\t\t0x%zx\t%s\n", imageName.c_str(), owner.c_str(),
                        clean(api.fieldGetName(field)).c_str(), type.c_str(),
                        api.fieldGetOffset(field), isStatic ? "S" : "");
                ++lines;
            }
        }
    }
    fclose(file);
    if (rename(partPath.c_str(), path) != 0) return -5;
    LOGI("catálogo extraído: %d entradas", lines);
    return lines;
}

thread_local sigjmp_buf* g_jump = nullptr;
struct sigaction g_old[3];
const int kSignals[3] = {SIGSEGV, SIGBUS, SIGABRT};

int slotOf(int sig) {
    for (int i = 0; i < 3; ++i) if (kSignals[i] == sig) return i;
    return -1;
}

void faultHandler(int sig, siginfo_t* info, void* ctx) {
    if (g_jump) siglongjmp(*g_jump, 1);
    int slot = slotOf(sig);
    if (slot >= 0) {
        const struct sigaction& old = g_old[slot];
        if (old.sa_flags & SA_SIGINFO) {
            if (old.sa_sigaction) { old.sa_sigaction(sig, info, ctx); return; }
        } else if (old.sa_handler != SIG_DFL && old.sa_handler != SIG_IGN && old.sa_handler) {
            old.sa_handler(sig);
            return;
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

int guardedDump(const char* path, bool force) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = faultHandler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigemptyset(&sa.sa_mask);
    for (int i = 0; i < 3; ++i) sigaction(kSignals[i], &sa, &g_old[i]);

    sigjmp_buf jump;
    int result = -7;
    if (sigsetjmp(jump, 1) == 0) {
        g_jump = &jump;
        try {
            result = dumpAll(path, force);
        } catch (...) {
            LOGW("exceção do IL2CPP durante o dump");
            result = -7;
        }
    } else {
        LOGW("dump abortado por falha nativa; ignorando");
        result = -7;
    }
    g_jump = nullptr;
    for (int i = 0; i < 3; ++i) sigaction(kSignals[i], &g_old[i], nullptr);
    return result;
}

}

extern "C" int larp_dump(const char* path, int force) {
    bool expected = false;
    if (!running.compare_exchange_strong(expected, true)) return -6;
    int result = guardedDump(path, force != 0);
    running.store(false);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_larp_launcher_feature_devkit_DevKitNative_nativeDump(JNIEnv* env, jclass, jstring path, jboolean force) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return -5;
    int result = larp_dump(chars, force ? 1 : 0);
    env->ReleaseStringUTFChars(path, chars);
    return result;
}
