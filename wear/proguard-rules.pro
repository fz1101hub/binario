# Release rules for the Wear OS data-layer boundary.
# Play services invokes listener/callback classes across a process boundary, so keep
# the Wear API and the application's listener implementations intact.
-keep class com.google.android.gms.wearable.** { *; }
-keep class it.binario.wear.** { *; }
