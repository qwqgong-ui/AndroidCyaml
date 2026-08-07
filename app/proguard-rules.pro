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
