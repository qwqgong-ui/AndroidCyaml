# NativePlatformCallbacks is looked up with JNI GetMethodID, so R8 cannot infer
# these entry points from Java call sites.
-keepclassmembers,allowoptimization class io.github.qwqgong.androidcyaml.NativePlatformCallbacks {
    public boolean protectSocket(int);
    public java.lang.String resolveProcessOwner(int, java.lang.String, int, java.lang.String, int);
}
