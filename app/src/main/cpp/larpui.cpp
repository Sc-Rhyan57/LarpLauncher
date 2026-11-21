#include <jni.h>
#include <string>

#ifdef __ANDROID__
#define LUI_ENVPTR(e) (&(e))
#else
#define LUI_ENVPTR(e) (reinterpret_cast<void**>(&(e)))
#endif

#define LUI_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

JavaVM* g_vm = nullptr;
jclass g_cls = nullptr;
jmethodID g_send = nullptr;
jmethodID g_draw = nullptr;

typedef void (*EventCallback)(const char*, const char*, const char*, const char*);
EventCallback g_callback = nullptr;

JNIEnv* env() {
    if (!g_vm) return nullptr;
    JNIEnv* e = nullptr;
    int status = g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6);
    if (status == JNI_OK) return e;
    if (status == JNI_EDETACHED && g_vm->AttachCurrentThread(LUI_ENVPTR(e), nullptr) == JNI_OK) return e;
    return nullptr;
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_com_larp_launcher_ui_LarpNative_nativeInit(JNIEnv* e, jclass cls) {
    if (g_cls) e->DeleteGlobalRef(g_cls);
    g_cls = static_cast<jclass>(e->NewGlobalRef(cls));
    g_send = e->GetStaticMethodID(cls, "onSend", "(Ljava/lang/String;)V");
    g_draw = e->GetStaticMethodID(cls, "onDraw", "(Ljava/lang/String;Ljava/lang/String;[ILjava/lang/String;)V");
}

extern "C" JNIEXPORT void JNICALL Java_com_larp_launcher_ui_LarpNative_nativeEmit(
    JNIEnv* e, jclass, jstring layer, jstring node, jstring event, jstring value) {
    EventCallback cb = g_callback;
    if (!cb) return;
    const char* l = layer ? e->GetStringUTFChars(layer, nullptr) : "";
    const char* n = node ? e->GetStringUTFChars(node, nullptr) : "";
    const char* ev = event ? e->GetStringUTFChars(event, nullptr) : "";
    const char* v = value ? e->GetStringUTFChars(value, nullptr) : "";
    cb(l, n, ev, v);
    if (layer) e->ReleaseStringUTFChars(layer, l);
    if (node) e->ReleaseStringUTFChars(node, n);
    if (event) e->ReleaseStringUTFChars(event, ev);
    if (value) e->ReleaseStringUTFChars(value, v);
}

LUI_EXPORT int larpui_ready() {
    return g_cls && g_send && g_draw ? 1 : 0;
}

LUI_EXPORT void larpui_set_callback(EventCallback cb) {
    g_callback = cb;
}

LUI_EXPORT int larpui_send(const char* json) {
    if (!larpui_ready() || !json) return 0;
    JNIEnv* e = env();
    if (!e) return 0;
    jstring s = e->NewStringUTF(json);
    e->CallStaticVoidMethod(g_cls, g_send, s);
    if (e->ExceptionCheck()) {
        e->ExceptionDescribe();
        e->ExceptionClear();
    }
    e->DeleteLocalRef(s);
    return 1;
}

LUI_EXPORT int larpui_draw(const char* layer, const char* node, const int* data, int count, const char* texts) {
    if (!larpui_ready() || !layer || !node || !data || count < 0) return 0;
    JNIEnv* e = env();
    if (!e) return 0;
    jstring l = e->NewStringUTF(layer);
    jstring n = e->NewStringUTF(node);
    jstring t = e->NewStringUTF(texts ? texts : "");
    jintArray arr = e->NewIntArray(count);
    if (count > 0) e->SetIntArrayRegion(arr, 0, count, data);
    e->CallStaticVoidMethod(g_cls, g_draw, l, n, arr, t);
    if (e->ExceptionCheck()) {
        e->ExceptionDescribe();
        e->ExceptionClear();
    }
    e->DeleteLocalRef(arr);
    e->DeleteLocalRef(l);
    e->DeleteLocalRef(n);
    e->DeleteLocalRef(t);
    return 1;
}
