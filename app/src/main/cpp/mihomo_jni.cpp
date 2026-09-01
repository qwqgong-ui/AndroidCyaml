#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "generated/libmihomo.h"

namespace {
JavaVM* g_vm = nullptr;
std::mutex g_callback_mutex;
jobject g_callback = nullptr;
jmethodID g_protect_method = nullptr;
jmethodID g_resolve_method = nullptr;
jmethodID g_browser_start_method = nullptr;
jmethodID g_browser_headers_method = nullptr;
jmethodID g_browser_read_method = nullptr;
jmethodID g_browser_close_method = nullptr;

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

char* copyJavaString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return nullptr;
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return nullptr;
    }
    const size_t length = std::strlen(chars);
    char* copy = static_cast<char*>(std::malloc(length + 1));
    if (copy != nullptr) {
        std::memcpy(copy, chars, length + 1);
    }
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

void clearCallback(JNIEnv* env) {
    std::lock_guard<std::mutex> guard(g_callback_mutex);
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
    }
    g_callback = nullptr;
    g_protect_method = nullptr;
    g_resolve_method = nullptr;
    g_browser_start_method = nullptr;
    g_browser_headers_method = nullptr;
    g_browser_read_method = nullptr;
    g_browser_close_method = nullptr;
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
    jmethodID browser_start = env->GetMethodID(
            callback_class,
            "startBrowserRequest",
            "(Ljava/lang/String;[B)Ljava/lang/String;"
    );
    jmethodID browser_headers = env->GetMethodID(
            callback_class,
            "awaitBrowserResponse",
            "(J)Ljava/lang/String;"
    );
    jmethodID browser_read = env->GetMethodID(
            callback_class,
            "readBrowserResponse",
            "(J[B)I"
    );
    jmethodID browser_close = env->GetMethodID(
            callback_class,
            "closeBrowserRequest",
            "(J)V"
    );
    env->DeleteLocalRef(callback_class);
    if (protect == nullptr
            || resolve == nullptr
            || browser_start == nullptr
            || browser_headers == nullptr
            || browser_read == nullptr
            || browser_close == nullptr
            || env->ExceptionCheck()) {
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
    g_browser_start_method = browser_start;
    g_browser_headers_method = browser_headers;
    g_browser_read_method = browser_read;
    g_browser_close_method = browser_close;
    return true;
}

jobject localCallback(
        JNIEnv* env,
        jmethodID* protect,
        jmethodID* resolve,
        jmethodID* browser_start,
        jmethodID* browser_headers,
        jmethodID* browser_read,
        jmethodID* browser_close
) {
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
    if (browser_start != nullptr) {
        *browser_start = g_browser_start_method;
    }
    if (browser_headers != nullptr) {
        *browser_headers = g_browser_headers_method;
    }
    if (browser_read != nullptr) {
        *browser_read = g_browser_read_method;
    }
    if (browser_close != nullptr) {
        *browser_close = g_browser_close_method;
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
    jobject callback = localCallback(env, &method, nullptr, nullptr, nullptr, nullptr, nullptr);
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
    jobject callback = localCallback(env, nullptr, &method, nullptr, nullptr, nullptr, nullptr);
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
        if (result != nullptr) {
            env->DeleteLocalRef(result);
        }
        return nullptr;
    }
    char* copy = copyJavaString(env, result);
    if (result != nullptr) {
        env->DeleteLocalRef(result);
    }
    return copy;
}

char* browserStartCallback(
        const char* request_json,
        const void* body,
        int body_length
) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr || body_length < 0) {
        return nullptr;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(env, nullptr, nullptr, &method, nullptr, nullptr, nullptr);
    if (callback == nullptr || method == nullptr) {
        return nullptr;
    }

    jstring metadata = env->NewStringUTF(request_json == nullptr ? "{}" : request_json);
    jbyteArray request_body = env->NewByteArray(static_cast<jsize>(body_length));
    if (request_body == nullptr || metadata == nullptr) {
        clearJavaException(env);
        if (metadata != nullptr) env->DeleteLocalRef(metadata);
        if (request_body != nullptr) env->DeleteLocalRef(request_body);
        env->DeleteLocalRef(callback);
        return nullptr;
    }
    if (body_length > 0 && body != nullptr) {
        env->SetByteArrayRegion(
                request_body,
                0,
                static_cast<jsize>(body_length),
                reinterpret_cast<const jbyte*>(body)
        );
    }
    auto result = static_cast<jstring>(env->CallObjectMethod(
            callback,
            method,
            metadata,
            request_body
    ));
    env->DeleteLocalRef(metadata);
    env->DeleteLocalRef(request_body);
    env->DeleteLocalRef(callback);
    if (env->ExceptionCheck()) {
        clearJavaException(env);
        if (result != nullptr) env->DeleteLocalRef(result);
        return nullptr;
    }
    char* copy = copyJavaString(env, result);
    if (result != nullptr) env->DeleteLocalRef(result);
    return copy;
}

char* browserHeadersCallback(int64_t request_id) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr || request_id <= 0) {
        return nullptr;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(
            env,
            nullptr,
            nullptr,
            nullptr,
            &method,
            nullptr,
            nullptr
    );
    if (callback == nullptr || method == nullptr) {
        return nullptr;
    }
    auto result = static_cast<jstring>(env->CallObjectMethod(
            callback,
            method,
            static_cast<jlong>(request_id)
    ));
    env->DeleteLocalRef(callback);
    if (env->ExceptionCheck()) {
        clearJavaException(env);
        if (result != nullptr) env->DeleteLocalRef(result);
        return nullptr;
    }
    char* copy = copyJavaString(env, result);
    if (result != nullptr) env->DeleteLocalRef(result);
    return copy;
}

int browserReadCallback(int64_t request_id, void* buffer, int capacity) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr || buffer == nullptr || capacity <= 0) {
        return -1;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(env, nullptr, nullptr, nullptr, nullptr, &method, nullptr);
    if (callback == nullptr || method == nullptr) {
        return -1;
    }
    jbyteArray destination = env->NewByteArray(static_cast<jsize>(capacity));
    if (destination == nullptr) {
        clearJavaException(env);
        env->DeleteLocalRef(callback);
        return -1;
    }
    jint count = env->CallIntMethod(
            callback,
            method,
            static_cast<jlong>(request_id),
            destination
    );
    if (!env->ExceptionCheck() && count > 0 && count <= capacity) {
        env->GetByteArrayRegion(
                destination,
                0,
                count,
                reinterpret_cast<jbyte*>(buffer)
        );
    }
    if (env->ExceptionCheck() || count > capacity) {
        clearJavaException(env);
        count = -1;
    }
    env->DeleteLocalRef(destination);
    env->DeleteLocalRef(callback);
    return static_cast<int>(count);
}

void browserCloseCallback(int64_t request_id) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr) {
        return;
    }
    jmethodID method = nullptr;
    jobject callback = localCallback(env, nullptr, nullptr, nullptr, nullptr, nullptr, &method);
    if (callback == nullptr || method == nullptr) {
        return;
    }
    env->CallVoidMethod(callback, method, static_cast<jlong>(request_id));
    clearJavaException(env);
    env->DeleteLocalRef(callback);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    AndroidCyamlInstallCallbacks(
            reinterpret_cast<void*>(&protectSocketCallback),
            reinterpret_cast<void*>(&resolveProcessCallback)
    );
    AndroidCyamlInstallBrowserCallbacks(
            reinterpret_cast<void*>(&browserStartCallback),
            reinterpret_cast<void*>(&browserHeadersCallback),
            reinterpret_cast<void*>(&browserReadCallback),
            reinterpret_cast<void*>(&browserCloseCallback)
    );
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    AndroidCyamlInstallBrowserCallbacks(nullptr, nullptr, nullptr, nullptr);
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
        jboolean ipv6_enabled,
        jstring process_matching_mode
) {
    std::string home_value = stringFromJava(env, home);
    std::string config_value = stringFromJava(env, config_path);
    std::string process_matching_mode_value = stringFromJava(env, process_matching_mode);
    return stringFromNative(env, AndroidCyamlPrepareTun(
            const_cast<char*>(home_value.c_str()),
            const_cast<char*>(config_value.c_str()),
            const_cast<char*>(process_matching_mode_value.c_str()),
            ipv6_enabled == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeSetWebViewXhttpEnabled(
        JNIEnv* env,
        jclass,
        jboolean enabled,
        jboolean request_streams_supported
) {
    return stringFromNative(env, AndroidCyamlSetBrowserDialerEnabled(
            enabled == JNI_TRUE ? 1 : 0,
            request_streams_supported == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeReadBrowserRequestBody(
        JNIEnv* env,
        jclass,
        jlong body_id,
        jbyteArray destination
) {
    if (destination == nullptr || body_id <= 0) {
        return -1;
    }
    const jsize capacity = env->GetArrayLength(destination);
    if (capacity <= 0 || env->ExceptionCheck()) {
        clearJavaException(env);
        return -1;
    }
    std::vector<jbyte> buffer(static_cast<size_t>(capacity));
    int count = AndroidCyamlReadBrowserRequestBody(
            static_cast<int64_t>(body_id),
            buffer.data(),
            static_cast<int>(capacity)
    );
    if (count > 0 && count <= capacity) {
        env->SetByteArrayRegion(destination, 0, count, buffer.data());
    }
    if (env->ExceptionCheck() || count > capacity) {
        clearJavaException(env);
        return -1;
    }
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeCloseBrowserRequestBody(
        JNIEnv*,
        jclass,
        jlong body_id
) {
    if (body_id > 0) {
        AndroidCyamlCloseBrowserRequestBody(static_cast<int64_t>(body_id));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativePushBrowserResponseChunk(
        JNIEnv* env,
        jclass,
        jlong request_id,
        jbyteArray chunk
) {
    if (request_id <= 0 || chunk == nullptr) {
        return JNI_FALSE;
    }
    const jsize length = env->GetArrayLength(chunk);
    if (length <= 0 || env->ExceptionCheck()) {
        clearJavaException(env);
        return JNI_FALSE;
    }
    jbyte* bytes = env->GetByteArrayElements(chunk, nullptr);
    if (bytes == nullptr || env->ExceptionCheck()) {
        clearJavaException(env);
        return JNI_FALSE;
    }
    int accepted = AndroidCyamlPushBrowserResponseChunk(
            static_cast<int64_t>(request_id),
            bytes,
            static_cast<int>(length)
    );
    env->ReleaseByteArrayElements(chunk, bytes, JNI_ABORT);
    return accepted != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeFinishBrowserResponse(
        JNIEnv* env,
        jclass,
        jlong request_id,
        jstring error
) {
    if (request_id <= 0) {
        return JNI_FALSE;
    }
    const char* error_value = error == nullptr
            ? nullptr
            : env->GetStringUTFChars(error, nullptr);
    if (error != nullptr && (error_value == nullptr || env->ExceptionCheck())) {
        clearJavaException(env);
        return JNI_FALSE;
    }
    int accepted = AndroidCyamlFinishBrowserResponse(
            static_cast<int64_t>(request_id),
            const_cast<char*>(error_value)
    );
    if (error_value != nullptr) {
        env->ReleaseStringUTFChars(error, error_value);
    }
    return accepted != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStart(
        JNIEnv* env,
        jclass,
        jstring home,
        jstring config_path,
        jstring ui_path,
        jstring controller_address,
        jstring log_level,
        jint tun_file_descriptor,
        jboolean ipv6_enabled,
        jstring process_matching_mode,
        jboolean lan_web_ui_public,
        jobject callbacks,
        jstring network_environment
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
    std::string log_level_value = stringFromJava(env, log_level);
    std::string process_matching_mode_value = stringFromJava(env, process_matching_mode);
    std::string network_environment_value = stringFromJava(env, network_environment);
    return stringFromNative(env, AndroidCyamlStart(
            const_cast<char*>(home_value.c_str()),
            const_cast<char*>(config_value.c_str()),
            const_cast<char*>(ui_value.c_str()),
            const_cast<char*>(controller_value.c_str()),
            const_cast<char*>(log_level_value.c_str()),
            const_cast<char*>(process_matching_mode_value.c_str()),
            const_cast<char*>(network_environment_value.c_str()),
            static_cast<int>(tun_file_descriptor),
            ipv6_enabled == JNI_TRUE ? 1 : 0,
            lan_web_ui_public == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeSetTcpConcurrent(
        JNIEnv* env,
        jclass,
        jboolean enabled
) {
    return stringFromNative(env, AndroidCyamlSetTcpConcurrent(
            enabled == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStop(
        JNIEnv* env,
        jclass
) {
    jstring result = stringFromNative(env, AndroidCyamlStop());
    char* disable_result = AndroidCyamlSetBrowserDialerEnabled(0, 0);
    AndroidCyamlFree(disable_result);
    clearCallback(env);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeNotifyNetworkChanged(
        JNIEnv* env,
        jclass,
        jboolean close_connections
) {
    return stringFromNative(env, AndroidCyamlNotifyNetworkChanged(
            close_connections == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeUpdateNetworkEnvironment(
        JNIEnv* env,
        jclass,
        jstring network_environment
) {
    std::string network_environment_value = stringFromJava(env, network_environment);
    return stringFromNative(env, AndroidCyamlUpdateNetworkEnvironment(
            const_cast<char*>(network_environment_value.c_str())
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeRetireNetworkScope(
        JNIEnv* env,
        jclass,
        jstring network_identity
) {
    std::string network_identity_value = stringFromJava(env, network_identity);
    return stringFromNative(env, AndroidCyamlRetireNetworkScope(
            const_cast<char*>(network_identity_value.c_str())
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeUpdateSystemDns(
        JNIEnv* env,
        jclass,
        jstring servers_json
) {
    std::string servers_value = stringFromJava(env, servers_json);
    return stringFromNative(env, AndroidCyamlUpdateSystemDNS(
            const_cast<char*>(servers_value.c_str())
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeUpdateIpv6Availability(
        JNIEnv* env,
        jclass,
        jboolean available
) {
    return stringFromNative(env, AndroidCyamlUpdateIPv6Availability(
            available == JNI_TRUE ? 1 : 0
    ));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeIsRunning(
        JNIEnv*,
        jclass
) {
    return AndroidCyamlIsRunning() != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeLog(
        JNIEnv* env,
        jclass,
        jboolean warning,
        jstring message
) {
    std::string message_value = stringFromJava(env, message);
    return stringFromNative(env, AndroidCyamlLog(
            warning == JNI_TRUE ? 1 : 0,
            const_cast<char*>(message_value.c_str())
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeSetDiagnostics(
        JNIEnv* env,
        jclass,
        jboolean enabled
) {
    return stringFromNative(env, AndroidCyamlSetDiagnostics(enabled == JNI_TRUE ? 1 : 0));
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeRuntimeMetrics(
        JNIEnv* env,
        jclass
) {
    return stringFromNative(env, AndroidCyamlRuntimeMetrics());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeTrimMemory(
        JNIEnv*,
        jclass
) {
    return static_cast<jint>(AndroidCyamlTrimMemory());
}
