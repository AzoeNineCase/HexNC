# HexNC ProGuard rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Keep kotlinx.serialization generated serializers
-keepclasseswithmembers class com.hexnc.music.** {
    public static ** Companion;
}
-keepclassmembers class com.hexnc.music.** {
    *** Companion;
}
-keepclasseswithmembers class com.hexnc.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.hexnc.music.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
