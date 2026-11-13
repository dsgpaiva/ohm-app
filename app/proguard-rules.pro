# MapLibre ships its own consumer rules; keep JNI-facing classes just in case.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**
