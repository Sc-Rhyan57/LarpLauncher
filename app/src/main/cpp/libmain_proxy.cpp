#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#define TAG "LarpMain"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using PreloadFn = void (*)();
using LoadFn = void (*)(JavaVM*);
using AcceptFn = void (*)(void*);
using UnloadFn = void (*)(JavaVM*);
using OnLoadFn = jint (*)(JavaVM*, void*);

struct Loader {
    std::string name;
    void* handle = nullptr;
};

JavaVM* g_vm = nullptr;
std::vector<Loader> g_loaders;

std::vector<std::string> split(const char* text) {
    std::vector<std::string> out;
    if (!text) return out;
    std::string cur;
    for (const char* p = text; *p; ++p) {
        if (*p == ':') {
            if (!cur.empty()) out.push_back(cur);
            cur.clear();
        } else {
            cur += *p;
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

std::vector<std::string> searchDirs() {
    std::vector<std::string> dirs = split(getenv("LARP_LIB_DIRS"));
    for (const auto& d : split(getenv("LARP_GAME_LIB_DIR"))) dirs.push_back(d);
    return dirs;
}

void* openLib(const std::string& name, int flags) {
    for (const auto& dir : searchDirs()) {
        std::string path = dir + "/" + name;
        if (access(path.c_str(), R_OK) != 0) continue;
        void* handle = dlopen(path.c_str(), flags);
        if (handle) {
            LOGI("aberto: %s", path.c_str());
            return handle;
        }
        LOGW("dlopen %s falhou: %s", path.c_str(), dlerror());
    }
    void* handle = dlopen(name.c_str(), flags);
    if (!handle) LOGW("dlopen %s falhou: %s", name.c_str(), dlerror());
    return handle;
}

template <typename T>
T symbol(void* handle, const char* name) {
    return reinterpret_cast<T>(dlsym(handle, name));
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    LOGI("proxy libmain carregado");

    void* unity = openLib("libunity.so", RTLD_NOW | RTLD_GLOBAL);
    if (!unity) {
        LOGE("libunity.so não encontrada");
        return JNI_VERSION_1_6;
    }

    const char* wanted = getenv("LARP_MODLOADERS");
    if (wanted && wanted[0]) {
        for (const auto& name : split(wanted)) {
            void* handle = openLib(name, RTLD_NOW | RTLD_GLOBAL);
            if (!handle) continue;
            g_loaders.push_back({name, handle});
            if (auto fn = symbol<PreloadFn>(handle, "modloader_preload")) fn();
        }
        for (auto& loader : g_loaders) {
            void* handle = loader.handle;
            auto load = symbol<LoadFn>(handle, "modloader_load");
            auto accept = symbol<AcceptFn>(handle, "modloader_accept_unity_handle");
            if (load || accept) {
                if (load) load(vm);
                if (accept) accept(unity);
                LOGI("modloader %s iniciado", loader.name.c_str());
            } else if (auto onLoad = symbol<OnLoadFn>(handle, "JNI_OnLoad")) {
                jint r = onLoad(vm, reserved);
                LOGI("JNI_OnLoad de %s retornou %d", loader.name.c_str(), r);
            }
        }
    }

    if (auto unityOnLoad = symbol<OnLoadFn>(unity, "JNI_OnLoad")) {
        return unityOnLoad(vm, reserved);
    }

    for (const auto& dir : split(getenv("LARP_GAME_LIB_DIR"))) {
        std::string path = dir + "/libmain.so";
        if (access(path.c_str(), R_OK) != 0) continue;
        void* original = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!original) continue;
        if (auto origOnLoad = symbol<OnLoadFn>(original, "JNI_OnLoad")) return origOnLoad(vm, reserved);
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    for (auto& loader : g_loaders) {
        if (auto fn = symbol<UnloadFn>(loader.handle, "modloader_unload")) fn(vm);
    }
}
