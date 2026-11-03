import { router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ScreenContainer } from "@/components/screen-container";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";

export default function GuideScreen() {
  const { colors } = useCompanionTheme();
  const { t } = useTranslation();
  const dataStates = [
    { id: "scheduled", label: t("guide.scheduled.label"), body: t("guide.scheduled.body") },
    { id: "realtime", label: t("guide.realtime.label"), body: t("guide.realtime.body") },
    { id: "expired", label: t("guide.expired.label"), body: t("guide.expired.body") },
    { id: "unavailable", label: t("guide.unavailable.label"), body: t("guide.unavailable.body") },
  ];
  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("guide.title")}</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>{t("guide.intro")}</Text>
    {dataStates.map(state => <View key={state.id} accessible accessibilityLabel={`${state.label}. ${state.body}`} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{state.label}</Text><Text style={[styles.copy, { color: colors.muted }]}>{state.body}</Text></View>)}
    <Pressable accessibilityRole="button" accessibilityLabel={t("common.back")} onPress={() => { if (router.canGoBack()) router.back(); else router.replace("/"); }} style={({ pressed }) => [styles.action, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.back")}</Text></Pressable>
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { padding: 20, gap: 14, paddingBottom: 34 }, title: { fontSize: 30, fontWeight: "900" }, card: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 6 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 15, lineHeight: 22 }, action: { minHeight: 48, borderWidth: 1, borderRadius: 12, alignItems: "center", justifyContent: "center" } });
