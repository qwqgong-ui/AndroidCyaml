package io.github.qwqgong.androidcyaml;

import android.net.Uri;
import io.github.qwqgong.androidcyaml.IControlCallback;
import io.github.qwqgong.androidcyaml.IOperationCallback;

interface IAppControl {
    void registerCallback(IControlCallback callback);
    void unregisterCallback(IControlCallback callback);
    void ensureCoreStarted();
    void restartCore();
    void importConfig(in Uri source, IOperationCallback callback);
    void setProcessMatchOverride(String mode, IOperationCallback callback);
}
