# ── NXFR ProGuard Rules ─────────────────────────────────────────────

# Keep JNI native methods in NxfrBridge.
-keepclassmembers class com.nxfr.android.service.NxfrService$NxfrBridge {
    native <methods>;
}

# Keep the NxfrState sealed class hierarchy (used by StateFlow reflection).
-keep class com.nxfr.android.service.NxfrState { *; }
-keep class com.nxfr.android.service.NxfrState$* { *; }

# Keep data classes used with JSON parsing.
-keep class com.nxfr.android.discovery.DiscoveredDevice { *; }
-keep class com.nxfr.android.discovery.DeviceUiModel { *; }
-keep class com.nxfr.android.security.IdentityInfo { *; }

# Compose / Kotlin
-dontwarn kotlinx.coroutines.debug.**
-keep class kotlin.Metadata { *; }
