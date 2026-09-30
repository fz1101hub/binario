import { Link } from "expo-router";
import { StyleSheet, Text, View } from "react-native";
import { ScreenContainer } from "@/components/screen-container";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";

export default function NotFoundScreen() {
  const { colors } = useCompanionTheme();
  const { t } = useTranslation();
  return <ScreenContainer><View style={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("app.notFound.title")}</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>{t("app.notFound.body")}</Text>
    <Link href="/" replace accessibilityRole="button" accessibilityLabel={t("app.notFound.back")} style={[styles.action, { color: colors.foreground, borderColor: colors.border }]}>{t("app.notFound.back")}</Link>
  </View></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { flex: 1, padding: 20, gap: 14, justifyContent: "center" }, title: { fontSize: 26, fontWeight: "900" }, copy: { fontSize: 15, lineHeight: 22 }, action: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 14, paddingVertical: 13, textAlign: "center", fontWeight: "800", overflow: "hidden" } });
