# quickjs-kt 通过 JNI 按名回调 binding,混淆会破坏
-keep class com.dokar.quickjs.** { *; }

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.glass.lisn.**$$serializer { *; }
-keepclassmembers class com.glass.lisn.** {
    *** Companion;
}
-keepclasseswithmembers class com.glass.lisn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# ---- 引擎数据模型(序列化跨 QuickJS/JSON) ----
-keep class com.glass.lisn.model.** { *; }
# ---- lx 宿主回调(JS 按名反射) ----
-keep class com.glass.lisn.engine.lx.** { *; }
