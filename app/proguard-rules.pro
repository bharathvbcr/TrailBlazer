# R8 rules for TrailBlazer release builds.
# Room, CameraX, DataStore, Navigation3 and kotlinx.serialization ship their own consumer rules.

# kotlinx.serialization: keep generated serializers of @Serializable classes (Open-Meteo DTOs, nav keys).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class com.example.trailblazer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.example.trailblazer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Readable stack traces from field reports.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
