import * as Notifications from "expo-notifications";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { IncomingSharedTextProvider } from "@/lib/incoming-shared-text";
import { CompanionThemeProvider, useCompanionTheme } from "@/lib/theme";

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

function RootNavigator() {
  const { isDark } = useCompanionTheme();
  return (
    <>
      <StatusBar style={isDark ? "light" : "dark"} />
      <Stack screenOptions={{ headerShown: false }} />
    </>
  );
}

export default function RootLayout() {
  return (
    <CompanionThemeProvider>
      <IncomingSharedTextProvider>
        <RootNavigator />
      </IncomingSharedTextProvider>
    </CompanionThemeProvider>
  );
}
