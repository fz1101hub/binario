import type { ExpoConfig } from "expo/config";

const { parseBinarioApiUrl } = require("./scripts/parse-binario-api-url.cjs") as {
  parseBinarioApiUrl: (value: unknown) => string | undefined;
};

const binarioApiUrl = parseBinarioApiUrl(process.env.EXPO_PUBLIC_BINARIO_API_URL);

const config: ExpoConfig = {
  name: "Binario",
  slug: "binario-companion-native",
  version: "0.1.1",
  orientation: "portrait",
  scheme: "binario-companion",
  userInterfaceStyle: "automatic",
  plugins: [
    "expo-router",
    "expo-notifications",
    "@react-native-community/datetimepicker",
    ["expo-location", { locationWhenInUsePermission: "Binario usa la posizione solo quando scegli di cercare la stazione più vicina." }],
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
    permissions: ["POST_NOTIFICATIONS"],
    blockedPermissions: [
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.WRITE_EXTERNAL_STORAGE",
    ],
    allowBackup: false,
  },
  experiments: { typedRoutes: true },
};

export default config;
