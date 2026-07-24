#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#include "generated/libmihomo.h"

namespace {
JavaVM* g_vm = nullptr;
std::mutex g_callback_mutex;
jobject g_callback = nullptr;
jmethodID g_protect_method = nullptr;
jmethodID g_resolve_method = nullptr;

class AttachedEnv {
public:
    AttachedEnv() {
        if (g_vm == nullptr) {
            return;
        }
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) {
            return;
        }
        if (g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            detach_ = true;
        } else {
            env_ = nullptr;
        }
    }

    ~AttachedEnv() {
        if (detach_ && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool detach_ = false;
};

std::string stringFromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring stringFromNative(JNIEnv* env, char* value) {
    if (value == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"native core returned null\"}");
    }
    jstring result = env->NewStringUTF(value);
    AndroidCyamlFree(value);
    return result;
}

void clearJavaException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

void clearCallback(JNIEnv* env) {
    std::lock_guard<std::mutex> guard(g_callback_mutex);
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
    }
    g_callback = nullptr;
    g_protect_method = nullptr;
    g_resolve_method = nullptr;
}

bool installCallback(JNIEnv* env, jobject callback) {
    clearCallback(env);
    if (callback == nullptr) {
        return false;
    }
    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        clearJavaException(env);
        return false;
    }
    jmethodID protect = env->GetMethodID(callback_class, "protectSocket", "(I)Z");
    jmethodID resolve = env->GetMethodID(
            callback_class,
            "resolveProcessOwner",
            "(ILjava/lang/String;ILjava/lang/String;I)Ljava/lang/String;"
    );
    env->DeleteLocalRef(callback_class);
    if (protect == nullptr || resolve == nullptr || env->ExceptionCheck()) {
        clearJavaException(env);
        return false;
    }
    jobject global = env->NewGlobalRef(callback);
    if (global == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> guard(g_callback_mutex);
    g_callback = global;
    g_protect_method = protect;
    g_resolve_method = resolve;
    return true;
}

jobject localCallback(JNIEnv* env, jmethodID* protect, jmethodID* resolve) {
    std::lock_guard<std::mutex> guard(g_callback_mutex);
    if (g_callback == nullptr) {
        return nullptr;
    }
    if (protect != nullptr) {
        *protect = g_protect_method;
    }
    if (resolve != nullptr) {
        *resolve = g_resolve_method;
    }
    return env->NewLocalRef(g_callback);
}

int protectSocketCallback(int fd) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr) {
        return 0;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(env, &method, nullptr);
    if (callback == nullptr || method == nullptr) {
        return 0;
    }
    jboolean protected_socket = env->CallBooleanMethod(callback, method, static_cast<jint>(fd));
    if (env->ExceptionCheck()) {
        clearJavaException(env);
        protected_socket = JNI_FALSE;
    }
    env->DeleteLocalRef(callback);
    return protected_socket == JNI_TRUE ? 1 : 0;
}

char* resolveProcessCallback(
        int protocol,
        const char* source_address,
        int source_port,
        const char* destination_address,
        int destination_port
) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr) {
        return nullptr;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(env, nullptr, &method);
    if (callback == nullptr || method == nullptr) {
        return nullptr;
    }

    jstring source = env->NewStringUTF(source_address == nullptr ? "" : source_address);
    jstring destination = env->NewStringUTF(
            destination_address == nullptr ? "" : destination_address
    );
    auto result = static_cast<jstring>(env->CallObjectMethod(
            callback,
            method,
            static_cast<jint>(protocol),
            source,
            static_cast<jint>(source_port),
            destination,
            static_cast<jint>(destination_port)
    ));
    env->DeleteLocalRef(source);
    env->DeleteLocalRef(destination);
    env->DeleteLocalRef(callback);
    if (env->ExceptionCheck()) {
        clearJavaException(env);
        return nullptr;
    }
    if (result == nullptr) {
        return nullptr;
    }

    const char* chars = env->GetStringUTFChars(result, nullptr);
    if (chars == nullptr || chars[0] == '\0') {
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(result, chars);
        }
        env->DeleteLocalRef(result);
        return nullptr;
    }
    size_t length = std::strlen(chars);
    char* copy = static_cast<char*>(std::malloc(length + 1));
    if (copy != nullptr) {
        std::memcpy(copy, chars, length + 1);
    }
    env->ReleaseStringUTFChars(result, chars);
    env->DeleteLocalRef(result);
    return copy;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    AndroidCyamlInstallCallbacks(
            reinterpret_cast<void*>(&protectSocketCallback),
            reinterpret_cast<void*>(&resolveProcessCallback)
    );
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    AndroidCyamlInstallCallbacks(nullptr, nullptr);
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        clearCallback(env);
    }
    g_vm = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeValidate(
        JNIEnv* env,
        jclass,
        jstring home,
        jstring config_path
) {
    std::string home_value = stringFromJava(env, home);
    std::string config_value = stringFromJava(env, config_path);
    return stringFromNative(env, AndroidCyamlValidate(
            const_cast<char*>(home_value.c_str()),
            const_cast<char*>(config_value.c_str())
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativePrepareTun(
        JNIEnv* env,
        jclass,
        jstring home,
        jstring config_path,
        jstring stack,
        jboolean ipv6_enabled,
        jboolean process_matching
) {
    std::string home_value = stringFromJava(env, home);
    std::string config_value = stringFromJava(env, config_path);
    std::string stack_value = stringFromJava(env, stack);
    return stringFromNative(env, AndroidCyamlPrepareTun(
            const_cast<char*>(home_value.c_str()),
            const_cast<char*>(config_value.c_str()),
            const_cast<char*>(stack_value.c_str()),
            ipv6_enabled == JNI_TRUE ? 1 : 0,
            process_matching == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStart(
        JNIEnv* env,
        jclass,
        jstring home,
        jstring config_path,
        jstring ui_path,
        jstring controller_address,
        jstring secret,
        jstring stack,
        jint tun_file_descriptor,
        jboolean ipv6_enabled,
        jboolean process_matching,
        jobject callbacks
) {
    if (!installCallback(env, callbacks)) {
        return env->NewStringUTF(
                "{\"ok\":false,\"error\":\"unable to install Android JNI callbacks\"}"
        );
    }
    std::string home_value = stringFromJava(env, home);
    std::string config_value = stringFromJava(env, config_path);
    std::string ui_value = stringFromJava(env, ui_path);
    std::string controller_value = stringFromJava(env, controller_address);
    std::string secret_value = stringFromJava(env, secret);
    std::string stack_value = stringFromJava(env, stack);
    return stringFromNative(env, AndroidCyamlStart(
            const_cast<char*>(home_value.c_str()),
            const_cast<char*>(config_value.c_str()),
            const_cast<char*>(ui_value.c_str()),
            const_cast<char*>(controller_value.c_str()),
            const_cast<char*>(secret_value.c_str()),
            const_cast<char*>(stack_value.c_str()),
            static_cast<int>(tun_file_descriptor),
            ipv6_enabled == JNI_TRUE ? 1 : 0,
            process_matching == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStop(
        JNIEnv* env,
        jclass
) {
    jstring result = stringFromNative(env, AndroidCyamlStop());
    clearCallback(env);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeIsRunning(
        JNIEnv*,
        jclass
) {
    return AndroidCyamlIsRunning() != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeTrimMemory(
        JNIEnv*,
        jclass
) {
    return static_cast<jint>(AndroidCyamlTrimMemory());
}
