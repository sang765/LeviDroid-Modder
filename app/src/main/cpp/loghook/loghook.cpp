// In-process log capture for the Logcat overlay's "App-Level Logging" option.
// Xiaomi's logd drops app writes to the main/system buffers, so logcat stays
// empty for the launcher and the game; hooking __android_log_buf_write (where
// android.util.Log and native __android_log_print both funnel into) captures
// every line at the source before it ever reaches logd.
#include <jni.h>
#include <android/log.h>
#include <shadowhook.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <atomic>
#include <mutex>

namespace {

std::mutex g_mutex;
std::atomic<bool> g_active{false};
void *g_stub = nullptr;
JavaVM *g_vm = nullptr;
jclass g_sink = nullptr;
jmethodID g_on_log = nullptr;

JNIEnv *attached_env() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    return g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK ? env : nullptr;
}

// int __android_log_buf_write(int bufID, int prio, const char *tag, const char *msg)
int log_buf_write_proxy(int bufID, int prio, const char *tag, const char *msg) {
    SHADOWHOOK_STACK_SCOPE();
    if (g_active.load(std::memory_order_relaxed)) {
        if (JNIEnv *env = attached_env()) {
            jstring jtag = tag ? env->NewStringUTF(tag) : nullptr;
            jstring jmsg = msg ? env->NewStringUTF(msg) : nullptr;
            if (!env->ExceptionCheck()) {
                env->CallStaticVoidMethod(g_sink, g_on_log, static_cast<jint>(prio),
                                          static_cast<jlong>(syscall(SYS_gettid)), jtag, jmsg);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (jtag) env->DeleteLocalRef(jtag);
            if (jmsg) env->DeleteLocalRef(jmsg);
        }
    }
    return SHADOWHOOK_CALL_PREV(log_buf_write_proxy, bufID, prio, tag, msg);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("org/levimc/launcher/core/logging/AppLevelLogSink");
    if (!cls) return JNI_ERR;
    g_on_log = env->GetStaticMethodID(cls, "onNativeLog", "(IJLjava/lang/String;Ljava/lang/String;)V");
    if (!g_on_log) return JNI_ERR;
    g_sink = static_cast<jclass>(env->NewGlobalRef(cls));
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_levimc_launcher_core_logging_AppLevelLogSink_installNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_active.load(std::memory_order_relaxed)) return 0;
    // Idempotent inside shadowhook; only the first init takes effect.
    if (shadowhook_init(SHADOWHOOK_MODE_SHARED, false) != 0) return -1;
    void *orig = nullptr;
    void *stub = shadowhook_hook_sym_name("liblog.so", "__android_log_buf_write",
                                          reinterpret_cast<void *>(log_buf_write_proxy), &orig);
    if (!stub) return -2;
    g_stub = stub;
    g_active.store(true, std::memory_order_relaxed);
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_levimc_launcher_core_logging_AppLevelLogSink_uninstallNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_active.store(false, std::memory_order_relaxed);
    if (!g_stub) return JNI_TRUE;
    void *stub = g_stub;
    g_stub = nullptr;
    return shadowhook_unhook(stub) == 0 ? JNI_TRUE : JNI_FALSE;
}
