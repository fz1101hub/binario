import type { ExpoConfig } from "expo/config";
import releaseIdentity from "./native/android/release-identity.json";

const config: ExpoConfig = {
  // Keep the phone companion visibly distinct from the primary Binario app.
  name: "Binario Companion",
  slug: "binario-companion-native",
  version: releaseIdentity.versionName,
  orientation: "portrait",
  scheme: "binario-companion",
  userInterfaceStyle: "automatic",
  icon: "./assets/brand/binario-companion-icon.png",
  splash: {
    image: "./assets/brand/binario-companion-splash.png",
    resizeMode: "contain",
    backgroundColor: "#005A52",
  },
  plugins: [
    "expo-router",
    "expo-notifications",
    [
      "expo-localization",
      {
        // iOS takes Apple's localization IDs. The Android list also becomes
        // resourceConfigurations, so it uses resource qualifiers: legacy "in" for
        // Indonesian and "zh", which keeps the region-less values-b+zh+Hans strings.
        supportedLocales: {
          ios: ["it", "en", "de", "fr", "es", "ar", "hi", "id", "ja", "ko", "my", "pt-BR", "ru", "th", "tr", "vi", "zh-Hans"],
          android: ["it", "en", "de", "fr", "es", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh"],
        },
        // Arabic mirrors the layout; React Native follows the device direction once allowed.
        supportsRTL: true,
        allowDynamicLocaleChangesAndroid: true,
      },
    ],
    "@react-native-community/datetimepicker",
    "./plugins/with-binario-wear-manifest.cjs",
    "./plugins/with-binario-android-widget.cjs",
    "./plugins/with-binario-ios-local-surfaces.cjs",
  ],
  extra: {
    binarioDataContract: "companion-snapshot-v1",
  },
  ios: { supportsTablet: true, bundleIdentifier: releaseIdentity.applicationId },
  android: {
    package: releaseIdentity.applicationId,
    versionCode: releaseIdentity.phoneVersionCode,
    permissions: ["ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"],
    blockedPermissions: [
      "android.permission.ACCESS_FINE_LOCATION",
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
      // The Companion only promises approximate local reminders; exact-alarm permissions stay absent.
      "android.permission.SCHEDULE_EXACT_ALARM",
      "android.permission.USE_EXACT_ALARM",
    ],
    adaptiveIcon: {
      foregroundImage: "./assets/brand/binario-companion-adaptive-foreground.png",
      backgroundColor: "#005A52",
    },
    allowBackup: false,
  },
  experiments: { typedRoutes: true },
};

export default config;
