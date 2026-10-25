# Keep CameraX internals
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }

# Keep data models used in diagnostics and profiles
-keep class com.astracam.dualdevice.device.** { *; }
-keep class com.astracam.dualdevice.camera.** { *; }
-keep class com.astracam.dualdevice.processing.** { *; }