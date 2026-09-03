-keep class com.byd.aeri.projectCore.bluetooth.btkey.codec.BtJniUtils { *; }
-keep class com.byd.aeri.projectCore.bluetooth.btkey.codec.Utils { *; }
-keep class com.byd.aeri.projectCore.bluetooth.bean.** { *; }
-keep class com.sign.overseas.SignCheck { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.conscrypt.**

# Lifecycle 2.8 looks up Compose 1.6's lifecycle owner getter by reflection.
# Keep it unconditionally: with AGP 8.3.2 the library's conditional consumer
# rule still allowed this getter to be removed in the release startup check.
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static androidx.compose.runtime.ProvidableCompositionLocal getLocalLifecycleOwner();
}
