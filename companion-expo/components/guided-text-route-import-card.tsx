import { maxGuidedTextImportCharacters, parseGuidedTextLocalRouteImports, type GuidedTextLocalRouteDraft } from "@/lib/local-journey-itineraries";
import { acknowledgeNativeAction } from "@/lib/native-feedback";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";
import { useEffect, useMemo, useState } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

function draftKey(draft: GuidedTextLocalRouteDraft) { return `${draft.origin.toLocaleLowerCase("it")}|${draft.destination.toLocaleLowerCase("it")}|${draft.travelDate}|${draft.travelTime}`; }

export function GuidedTextRouteImportCard({ existingKeys, onConfirmImport, receivedText, onReceivedTextAccepted }: { existingKeys: readonly string[]; onConfirmImport: (drafts: GuidedTextLocalRouteDraft[]) => Promise<number>; receivedText?: string | null; onReceivedTextAccepted?: () => void }) {
  const { colors } = useCompanionTheme();
  const { t } = useTranslation();
  const [rawText, setRawText] = useState("");
  const [drafts, setDrafts] = useState<GuidedTextLocalRouteDraft[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [reviewed, setReviewed] = useState(false);
  const [message, setMessage] = useState("");
  const existing = useMemo(() => new Set(existingKeys), [existingKeys]);
  useEffect(() => {
    if (!receivedText?.trim()) return;
    setRawText(receivedText.slice(0, maxGuidedTextImportCharacters));
    setDrafts([]); setSelectedKeys([]); setReviewed(false);
    setMessage(t("import.sharedReceived"));
    onReceivedTextAccepted?.();
  }, [onReceivedTextAccepted, receivedText, t]);

  function createPreview() {
    const candidates = parseGuidedTextLocalRouteImports(rawText).filter(draft => !existing.has(draftKey(draft)));
    if (candidates.length === 0) { setDrafts([]); setSelectedKeys([]); setReviewed(false); setMessage(t("import.noneFound")); return; }
    setDrafts(candidates); setSelectedKeys(candidates.map(draftKey)); setReviewed(false);
    setMessage(t("import.previewReady", { count: candidates.length }));
  }

  async function confirm() {
    const selected = drafts.filter(draft => selectedKeys.includes(draftKey(draft)));
    if (!reviewed || selected.length === 0) { setMessage(t("import.confirmNeeded")); return; }
    const imported = await onConfirmImport(selected);
    setRawText(""); setDrafts([]); setSelectedKeys([]); setReviewed(false);
    setMessage(imported ? t("import.imported", { count: imported }) : t("import.noneImported"));
    if (imported) acknowledgeNativeAction();
  }

  return <View style={[styles.card, { backgroundColor: colors.background, borderColor: colors.border }]}>
    <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("import.title")}</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>{t("import.description")}</Text>
    <TextInput accessibilityLabel={t("import.inputA11y")} accessibilityHint={t("import.inputHint")} value={rawText} onChangeText={value => { setRawText(value.slice(0, maxGuidedTextImportCharacters)); setDrafts([]); setSelectedKeys([]); setReviewed(false); }} placeholder={t("import.example")} placeholderTextColor={colors.muted} multiline maxLength={maxGuidedTextImportCharacters} textAlignVertical="top" style={[styles.input, { color: colors.foreground, borderColor: colors.border }]} />
    <Text style={[styles.hint, { color: colors.muted }]}>{t("import.formatHint")}</Text>
    <Pressable disabled={!rawText.trim()} accessibilityRole="button" accessibilityState={{ disabled: !rawText.trim() }} accessibilityLabel={t("import.createPreviewA11y")} onPress={createPreview} style={({ pressed }) => [styles.action, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: !rawText.trim() ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={styles.actionText}>{t("import.createPreview")}</Text></Pressable>
    {drafts.length ? <View style={[styles.preview, { borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.subheading, { color: colors.foreground }]}>{t("import.review")}</Text>{drafts.map(draft => { const key = draftKey(draft); const selected = selectedKeys.includes(key); return <Pressable key={key} accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={t("import.choiceA11y", { selection: selected ? t("common.selected") : t("common.notSelectedFemale"), origin: draft.origin, destination: draft.destination, date: draft.travelDate, time: draft.travelTime })} onPress={() => setSelectedKeys(current => current.includes(key) ? current.filter(item => item !== key) : [...current, key])} style={({ pressed }) => [styles.choice, { borderColor: selected ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{selected ? "✓ " : "○ "}{draft.origin} → {draft.destination}</Text><Text style={{ color: colors.muted }}>{draft.travelDate} · {draft.travelTime}</Text></Pressable>; })}<Pressable accessibilityRole="checkbox" accessibilityState={{ checked: reviewed }} accessibilityLabel={t("import.reviewedA11y")} onPress={() => setReviewed(value => !value)} style={({ pressed }) => [styles.choice, { borderColor: reviewed ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{reviewed ? "✓ " : "○ "}{t("import.reviewed")}</Text></Pressable><View style={styles.row}><Pressable disabled={!reviewed || selectedKeys.length === 0} accessibilityRole="button" accessibilityState={{ disabled: !reviewed || selectedKeys.length === 0 }} accessibilityLabel={t("import.selectedA11y")} onPress={() => void confirm()} style={({ pressed }) => [styles.action, styles.flex, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: !reviewed || selectedKeys.length === 0 ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={styles.actionText}>{t("import.selected")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("import.cancelA11y")} onPress={() => { setDrafts([]); setSelectedKeys([]); setReviewed(false); setMessage(t("import.cancelled")); }} style={({ pressed }) => [styles.cancel, styles.flex, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.cancel")}</Text></Pressable></View></View> : null}
    {message ? <Text accessibilityLiveRegion="polite" style={[styles.message, { color: colors.muted }]}>{message}</Text> : null}
  </View>;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, padding: 13, gap: 9 }, heading: { fontSize: 16, fontWeight: "800" }, subheading: { fontSize: 15, fontWeight: "800" }, copy: { fontSize: 13, lineHeight: 19 }, hint: { fontSize: 12, lineHeight: 17 }, input: { minHeight: 96, borderWidth: 1, borderRadius: 12, padding: 12, fontSize: 15 }, action: { minHeight: 46, borderWidth: 1, borderRadius: 11, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 }, actionText: { color: "#fff", fontWeight: "800" }, preview: { borderTopWidth: 1, paddingTop: 10, gap: 8 }, choice: { minHeight: 46, borderWidth: 1, borderRadius: 10, paddingHorizontal: 11, justifyContent: "center", gap: 2 }, row: { flexDirection: "row", gap: 8 }, flex: { flex: 1 }, cancel: { minHeight: 46, borderWidth: 1, borderRadius: 11, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 }, message: { fontSize: 13, lineHeight: 19 },
});
