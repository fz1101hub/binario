import { Tabs } from "expo-router";
import { MaterialIcons } from "@expo/vector-icons";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";
export default function TabLayout() { const { colors } = useCompanionTheme(); const { t } = useTranslation(); const tabs = { index: [t("tabs.status"), "train"] as const, alerts: [t("tabs.alerts"), "notifications-none"] as const, companion: [t("tabs.companion"), "devices"] as const, settings: [t("tabs.settings"), "settings"] as const }; return <Tabs screenOptions={{ headerShown: false, tabBarActiveTintColor: colors.primary, tabBarInactiveTintColor: colors.muted, tabBarStyle: { backgroundColor: colors.surface, borderTopColor: colors.border } }}>{Object.entries(tabs).map(([name, [title, icon]]) => <Tabs.Screen key={name} name={name} options={{ title, tabBarIcon: ({ color, size }) => <MaterialIcons name={icon} color={color} size={size} /> }} />)}</Tabs>; }
