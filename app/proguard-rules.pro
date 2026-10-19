# kotlinx.serialization: keep generated serializers for wire-compatible protocol types.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.mesmerize08.nexusirl.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.mesmerize08.nexusirl.** {
    kotlinx.serialization.KSerializer serializer(...);
}
