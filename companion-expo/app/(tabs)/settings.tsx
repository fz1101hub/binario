import { ScreenContainer } from "@/components/screen-container";
import { createNativeDiagnosticReport } from "@/lib/diagnostics";
import { useTranslation } from "@/lib/i18n";
import { type ThemeMode, useCompanionTheme } from "@/lib/theme";
import { clearLocalPlannedRoutes, importSelectedManualPlannedRoutes, loadLocalPlannedRoutes } from "@/lib/local-routes";
import { pickLocalJourneyTransfer, shareLocalJourneyTransfer } from "@/lib/manual-route-transfer";
import { previewManualRouteTransfer, type ManualRouteTransferPreview } from "@/lib/manual-route-transfer-contract";
import { clearLocalJourneyItineraries } from "@/lib/local-itineraries";
import { clearLocalJourneyArchive } from "@/lib/local-journey-privacy";
import { loadLocalJourneyTimingPreferences, localJourneyReminderOptions, saveLocalJourneyTimingPreferences, type LocalJourneyReminderPreference } from "@/lib/local-journey-timing";
import { cancelPlannedRouteReminder, listScheduledRouteIds } from "@/lib/planned-reminder-service";
import Constants from "expo-constants";
import { router } from "expo-router";
import { useEffect, useState } from "react";
import { Alert, Platform, Pressable, ScrollView, Share, StyleSheet, Text, View } from "react-native";

const modes: ThemeMode[] = ["system", "light", "dark"];

export default function SettingsScreen() {
  const { colors, mode, setMode, highContrast, setHighContrast, reduceMotion, setReduceMotion, simpleMode, setSimpleMode } = useCompanionTheme();
  const { locale, t } = useTranslation();
  const [status, setStatus] = useState("");
  const [manualTransferPreview, setManualTransferPreview] = useState<ManualRouteTransferPreview[]>([]);
  const [selectedManualTransfer, setSelectedManualTransfer] = useState<string[]>([]);
  const [preferredReminderMinutes, setPreferredReminderMinutes] = useState<LocalJourneyReminderPreference>(15);

  useEffect(() => { void loadLocalJourneyTimingPreferences().then(preferences => setPreferredReminderMinutes(preferences.preferredReminderMinutes)); }, []);

  async function setPreferredReminder(minutes: LocalJourneyReminderPreference) {
    await saveLocalJourneyTimingPreferences(minutes);
    setPreferredReminderMinutes(minutes);
    setStatus(t("settings.preferredStatus", { minutes }));
  }

  async function shareDiagnostic() {
    try {
      const diagnosticPlatform = Platform.OS === "android" || Platform.OS === "ios" || Platform.OS === "web" ? Platform.OS : "other";
      await Share.share({ title: t("settings.diagnosticTitle"), message: JSON.stringify(createNativeDiagnosticReport({ platform: diagnosticPlatform, appVersion: Constants.expoConfig?.version ?? null }, locale), null, 2) });
      setStatus(t("settings.diagnosticReady"));
    } catch { setStatus(t("settings.diagnosticError")); }
  }

  async function clearLocalJourneyData() {
    const result = await clearLocalJourneyArchive({ listScheduledRouteIds, cancelReminder: cancelPlannedRouteReminder, clearRoutes: clearLocalPlannedRoutes, clearItineraries: clearLocalJourneyItineraries });
    setStatus(t("settings.cleared", { count: result.clearedReminders }));
  }

  function confirmClearLocalJourneyData() {
    Alert.alert(t("settings.clearTitle"), t("settings.clearBody"), [{ text: t("common.cancel"), style: "cancel" }, { text: t("settings.clearConfirm"), style: "destructive", onPress: () => void clearLocalJourneyData() }]);
  }

  async function exportManualTransfer() {
    const routes = await loadLocalPlannedRoutes();
    const shared = await shareLocalJourneyTransfer(routes, locale);
    setStatus(shared ? t("settings.exportReady") : routes.length === 0 ? t("settings.exportEmpty") : t("settings.exportUnavailable"));
  }

  async function inspectManualTransfer() {
    const archive = await pickLocalJourneyTransfer();
    if (!archive) { setStatus(t("settings.invalidArchive")); return; }
    const current = await loadLocalPlannedRoutes();
    const currentPortable = current.map(route => ({ origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, travelDate: route.travelDate, travelTime: route.travelTime, plannedReminderMinutes: route.plannedReminderMinutes, checklist: route.checklist ?? [] }));
    setManualTransferPreview(previewManualRouteTransfer(currentPortable, archive.routes));
    setSelectedManualTransfer([]);
    setStatus(t("settings.archivePreviewReady"));
  }

  async function importManualTransfer() {
    const selected = manualTransferPreview.filter(candidate => candidate.state === "new" && selectedManualTransfer.includes(candidate.key)).map(candidate => candidate.route);
    const imported = await importSelectedManualPlannedRoutes(selected);
    setManualTransferPreview([]); setSelectedManualTransfer([]);
    setStatus(t("settings.imported", { count: imported }));
  }

  const toggle = (key: string, current: string[], set: (next: string[]) => void) => set(current.includes(key) ? current.filter(item => item !== key) : [...current, key]);
  const themeLabel = (item: ThemeMode) => t(item === "system" ? "settings.system" : item === "light" ? "settings.light" : "settings.dark");

  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("settings.title")}</Text>
    <Text style={[styles.label, { color: colors.muted }]}>{t("settings.theme")}</Text>
    <View style={styles.row}>{modes.map(item => <Pressable key={item} accessibilityRole="button" accessibilityState={{ selected: mode === item }} accessibilityLabel={t("settings.themeA11y", { theme: themeLabel(item) })} onPress={() => setMode(item)} style={({ pressed }) => [styles.choice, { borderColor: mode === item ? colors.primary : colors.border, backgroundColor: mode === item ? colors.primary : colors.surface, opacity: pressed ? 0.75 : 1 }]}><Text style={{ color: mode === item ? "#fff" : colors.foreground, fontWeight: "800" }}>{themeLabel(item)}</Text></Pressable>)}</View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.accessibility")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("settings.accessibilityDescription")}</Text>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: highContrast }} accessibilityLabel={t("settings.highContrast")} onPress={() => setHighContrast(!highContrast)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.highContrast")}</Text><Text style={{ color: colors.muted }}>{t(highContrast ? "common.active" : "common.inactive")}</Text></Pressable>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: reduceMotion }} accessibilityLabel={t("settings.reduceMotion")} onPress={() => setReduceMotion(!reduceMotion)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.reduceMotion")}</Text><Text style={{ color: colors.muted }}>{t(reduceMotion ? "common.active" : "common.inactive")}</Text></Pressable>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: simpleMode }} accessibilityLabel={t("settings.simpleMode")} accessibilityHint={t("settings.simpleHint")} onPress={() => setSimpleMode(!simpleMode)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.simpleMode")}</Text><Text style={{ color: colors.muted }}>{t(simpleMode ? "common.active" : "common.inactive")}</Text></Pressable>
    </View>
    <View accessible accessibilityLabel={t("settings.advanceA11y")} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.advanceTitle")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("settings.advanceDescription")}</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={t("settings.advanceGroupA11y")} style={styles.row}>{localJourneyReminderOptions.map(minutes => <Pressable key={minutes} accessibilityRole="radio" accessibilityState={{ checked: preferredReminderMinutes === minutes }} accessibilityLabel={t("settings.advanceOptionA11y", { minutes })} onPress={() => void setPreferredReminder(minutes)} style={({ pressed }) => [styles.choice, { borderColor: preferredReminderMinutes === minutes ? colors.primary : colors.border, backgroundColor: preferredReminderMinutes === minutes ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: preferredReminderMinutes === minutes ? "#fff" : colors.foreground, fontWeight: "800" }}>{minutes} min</Text></Pressable>)}</View>
    </View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.phoneWatch")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("settings.phoneWatchDescription")}</Text></View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.remoteSync")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("settings.remoteUnavailable")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("settings.remoteDescription")}</Text></View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.privacy")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("settings.privacyDescription")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("settings.privacyDisclosure")}</Text>
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.manualTransfer")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("settings.manualTransferDescription")}</Text>
      <Pressable accessibilityRole="button" accessibilityLabel={t("settings.exportArchiveA11y")} accessibilityHint={t("settings.exportArchiveHint")} onPress={() => void exportManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.exportArchive")}</Text><Text style={{ color: colors.muted }}>{t("common.manual")}</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel={t("settings.importArchiveA11y")} accessibilityHint={t("settings.importArchiveHint")} onPress={() => void inspectManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.importArchive")}</Text><Text style={{ color: colors.muted }}>{t("settings.withPreview")}</Text></Pressable>
      {manualTransferPreview.length ? <View style={[styles.preview, { borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("settings.archivePreview")}</Text>{manualTransferPreview.map(candidate => { const state = t(candidate.state === "new" ? "settings.readyToAdd" : "settings.alreadyPresent"); return <Pressable key={candidate.key} disabled={candidate.state !== "new"} accessibilityRole="checkbox" accessibilityState={{ checked: selectedManualTransfer.includes(candidate.key), disabled: candidate.state !== "new" }} accessibilityLabel={t("settings.archiveChoiceA11y", { origin: candidate.route.origin, destination: candidate.route.destination, state })} onPress={() => toggle(candidate.key, selectedManualTransfer, setSelectedManualTransfer)} style={[styles.choice, { borderColor: colors.border, opacity: candidate.state === "new" ? 1 : 0.65 }]}><Text style={{ color: colors.foreground }}>{selectedManualTransfer.includes(candidate.key) ? "✓ " : "○ "}{candidate.route.origin} → {candidate.route.destination}</Text><Text style={{ color: colors.muted }}>{t(candidate.state === "new" ? "settings.readyToAdd" : "settings.alreadyPresentShort")}</Text></Pressable>; })}<Pressable disabled={selectedManualTransfer.length === 0} accessibilityRole="button" accessibilityState={{ disabled: selectedManualTransfer.length === 0 }} accessibilityLabel={t("settings.addPhoneA11y")} onPress={() => void importManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: selectedManualTransfer.length === 0 ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.addPhone")}</Text><Text style={{ color: colors.muted }}>{t("settings.selectedCount", { count: selectedManualTransfer.length })}</Text></Pressable></View> : null}
      <Pressable accessibilityRole="button" accessibilityLabel={t("settings.guideA11y")} accessibilityHint={t("settings.guideHint")} onPress={() => router.push("/guide")} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("guide.title")}</Text><Text style={{ color: colors.muted }}>{t("settings.guide")}</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel={t("settings.diagnosticA11y")} accessibilityHint={t("settings.diagnosticHint")} onPress={() => void shareDiagnostic()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("settings.diagnostic")}</Text><Text style={{ color: colors.muted }}>{t("settings.onRequest")}</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel={t("settings.clearArchiveA11y")} accessibilityHint={t("settings.clearArchiveHint")} onPress={confirmClearLocalJourneyData} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.danger, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.danger, fontWeight: "800" }}>{t("settings.clearArchive")}</Text><Text style={{ color: colors.muted }}>{t("settings.phoneOnly")}</Text></Pressable>
    </View>
    {status ? <Text accessibilityLiveRegion="polite" style={[styles.status, { color: colors.muted }]}>{status}</Text> : null}
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { padding: 20, gap: 14 }, title: { fontSize: 30, fontWeight: "900" }, label: { fontSize: 14, fontWeight: "700" }, row: { flexDirection: "row", gap: 8, flexWrap: "wrap" }, choice: { borderWidth: 1, borderRadius: 12, paddingVertical: 11, paddingHorizontal: 14, gap: 4 }, card: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 9 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 15, lineHeight: 22 }, preview: { borderTopWidth: 1, gap: 8, paddingTop: 12 }, accessibilityAction: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 13, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }, status: { fontSize: 14, lineHeight: 20 } });
