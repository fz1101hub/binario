# Release rules for Binario's runtime-parsed transport data and Wear data layer.
# These keep rules intentionally favor release reliability over byte-level savings.
# Tighten only after device tests demonstrate that each library is safe to shrink.

# GTFS-Realtime generated protobuf messages and their protobuf runtime are accessed
# while parsing wire payloads. Keep their generated members intact.
-keep class com.google.transit.realtime.** { *; }
-keep class com.google.protobuf.** { *; }

# jsoup builds and traverses DOM/selectors at runtime; preserve its implementation.
-keep class org.jsoup.** { *; }

# Google Play services Wear data-layer callbacks cross a Play-services boundary.
-keep class com.google.android.gms.wearable.** { *; }

# Keep project-specific Wear listener entry points and their inherited callbacks.
-keep class it.binario.app.wear.** { *; }
