# Keep Room DAOs and Entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Media3 session
-keep class androidx.media3.session.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
