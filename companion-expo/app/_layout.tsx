import * as Notifications from "expo-notifications";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { IncomingSharedTextProvider } from "@/lib/incoming-shared-text";
import { CompanionThemeProvider, useCompanionTheme } from "@/lib/theme";
import { screenAnimation } from "@/lib/motion";

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

function RootNavigator() {
  const { isDark, motionReduced } = useCompanionTheme();
  return (
    <>
      <StatusBar style={isDark ? "light" : "dark"} />
      <Stack screenOptions={{ headerShown: false, animation: screenAnimation(motionReduced) }} />
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
