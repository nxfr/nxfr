# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep NXFR FFI bridge
-keep class com.nxfr.android.service.NxfrService$NxfrBridge { *; }
