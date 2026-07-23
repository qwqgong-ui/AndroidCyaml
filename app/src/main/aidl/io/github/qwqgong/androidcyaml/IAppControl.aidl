package io.github.qwqgong.androidcyaml;

import android.net.Uri;
import io.github.qwqgong.androidcyaml.IControlCallback;
import io.github.qwqgong.androidcyaml.IOperationCallback;

interface IAppControl {
    void registerCallback(IControlCallback callback);
    void unregisterCallback(IControlCallback callback);
    void restartRuntime(IOperationCallback callback);
    void importConfig(in Uri source, IOperationCallback callback);
    void setTunStackOverride(String override, IOperationCallback callback);
}
