import type { ExpoConfig } from "expo/config";

const { parseBinarioApiUrl } = require("./scripts/parse-binario-api-url.cjs") as {
  parseBinarioApiUrl: (value: unknown) => string | undefined;
};

const binarioApiUrl = parseBinarioApiUrl(process.env.EXPO_PUBLIC_BINARIO_API_URL);

const config: ExpoConfig = {
  // Keep the phone companion visibly distinct from the primary Binario app.
  name: "Binario Companion",
  slug: "binario-companion-native",
  version: "0.1.1",
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
    "@react-native-community/datetimepicker",
    "./plugins/with-binario-wear-manifest.cjs",
    "./plugins/with-binario-android-widget.cjs",
    "./plugins/with-binario-ios-local-surfaces.cjs",
  ],
  extra: {
    binarioApiUrl: binarioApiUrl ?? "",
    binarioDataContract: "companion-snapshot-v1",
  },
  ios: { supportsTablet: true, bundleIdentifier: "im.binario.companion" },
  android: {
    package: "im.binario.companion",
    versionCode: 3,
    permissions: ["ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"],
    blockedPermissions: [
      "android.permission.ACCESS_FINE_LOCATION",
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
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
