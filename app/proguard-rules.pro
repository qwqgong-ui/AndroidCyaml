# NativePlatformCallbacks is looked up with JNI GetMethodID, so R8 cannot infer
# these entry points from Java call sites. Every method listed here is part of
# the native callback ABI and must retain both its name and descriptor.
-keepclassmembers,allowoptimization class io.github.qwqgong.androidcyaml.NativePlatformCallbacks {
    public boolean protectSocket(int);
    public java.lang.String resolveProcessOwner(int, java.lang.String, int, java.lang.String, int);
    public java.lang.String startBrowserRequest(java.lang.String, byte[]);
    public int readBrowserResponse(long, byte[]);
    public void closeBrowserRequest(long);
}

# The hidden browser page invokes these names through addJavascriptInterface.
# Keep an explicit rule in addition to the Android default annotation rule so
# a future shrinker/default-rule change cannot silently break the bridge.
-keepclassmembers,allowoptimization class io.github.qwqgong.androidcyaml.WebViewXhttpDialer$JavascriptBridge {
    public void onReady();
    public int requestBodyLength(long);
    public java.lang.String requestBodyChunk(long, int, int);
    public void onHeaders(long, int, java.lang.String, java.lang.String);
    public void onChunk(long, java.lang.String);
    public void onComplete(long);
    public void onError(long, java.lang.String);
}
