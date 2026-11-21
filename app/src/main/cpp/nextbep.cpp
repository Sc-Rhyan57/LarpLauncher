#include <dlfcn.h>
#include <cstdint>

#define NEXTBEP_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

void* fusionHandle() {
    static void* handle = nullptr;
    if (!handle) handle = dlopen("libfusion.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) handle = dlopen("libfusion.so", RTLD_NOW);
    return handle;
}

void* resolve(const char* name) {
    void* handle = fusionHandle();
    return handle ? dlsym(handle, name) : nullptr;
}

}

NEXTBEP_EXPORT void set_loader_stage(uint8_t stage) {
    static auto fn = reinterpret_cast<void (*)(uint8_t)>(resolve("set_loader_stage"));
    if (fn) fn(stage);
}

NEXTBEP_EXPORT void set_loader_message(const char* message) {
    static auto fn = reinterpret_cast<void (*)(const char*)>(resolve("set_loader_message"));
    if (fn) fn(message);
}

NEXTBEP_EXPORT void write_log_level(int level, const char* message) {
    static auto fn = reinterpret_cast<void (*)(int, const char*)>(resolve("write_log_level"));
    if (fn) fn(level, message);
}

NEXTBEP_EXPORT void* hook(void* target, void* replacement, bool specialReturnBuffer) {
    static auto fn = reinterpret_cast<void* (*)(void*, void*, bool)>(resolve("hook"));
    return fn ? fn(target, replacement, specialReturnBuffer) : nullptr;
}

NEXTBEP_EXPORT void unhook(void* target) {
    static auto fn = reinterpret_cast<void (*)(void*)>(resolve("unhook"));
    if (fn) fn(target);
}

NEXTBEP_EXPORT uint8_t unhook_checked(void* target) {
    static auto fn = reinterpret_cast<uint8_t (*)(void*)>(resolve("unhook_checked"));
    return fn ? fn(target) : 0;
}

NEXTBEP_EXPORT uint8_t get_low_memory_mode() {
    static auto fn = reinterpret_cast<uint8_t (*)()>(resolve("get_low_memory_mode"));
    return fn ? fn() : 0;
}

NEXTBEP_EXPORT void* get_il2cpp_api(const char* name) {
    static auto fn = reinterpret_cast<void* (*)(const char*)>(resolve("get_il2cpp_api"));
    return fn ? fn(name) : nullptr;
}
