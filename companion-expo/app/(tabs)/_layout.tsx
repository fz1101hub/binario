import { Tabs } from "expo-router";
import { MaterialIcons } from "@expo/vector-icons";
import { useCompanionTheme } from "@/lib/theme";
const tabs = { index: ["Stato", "train"] as const, alerts: ["Avvisi", "notifications-none"] as const, companion: ["Companion", "devices"] as const, settings: ["Impostazioni", "settings"] as const };
export default function TabLayout() { const { colors } = useCompanionTheme(); return <Tabs screenOptions={{ headerShown: false, tabBarActiveTintColor: colors.primary, tabBarInactiveTintColor: colors.muted, tabBarStyle: { backgroundColor: colors.surface, borderTopColor: colors.border } }}>{Object.entries(tabs).map(([name, [title, icon]]) => <Tabs.Screen key={name} name={name} options={{ title, tabBarIcon: ({ color, size }) => <MaterialIcons name={icon} color={color} size={size} /> }} />)}</Tabs>; }
