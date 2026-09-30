# ClipForge release hardening
-renamesourcefileattribute ClipForge
-adaptclassstrings
-allowaccessmodification

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
