import { maxGuidedTextImportCharacters, parseGuidedTextLocalRouteImports, type GuidedTextLocalRouteDraft } from "@/lib/local-journey-itineraries";
import { acknowledgeNativeAction } from "@/lib/native-feedback";
import { useCompanionTheme } from "@/lib/theme";
import { useEffect, useMemo, useState } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";

function draftKey(draft: GuidedTextLocalRouteDraft) { return `${draft.origin.toLocaleLowerCase("it")}|${draft.destination.toLocaleLowerCase("it")}|${draft.travelDate}|${draft.travelTime}`; }

export function GuidedTextRouteImportCard({ existingKeys, onConfirmImport, receivedText, onReceivedTextAccepted }: { existingKeys: readonly string[]; onConfirmImport: (drafts: GuidedTextLocalRouteDraft[]) => Promise<number>; receivedText?: string | null; onReceivedTextAccepted?: () => void }) {
  const { colors } = useCompanionTheme();
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
    setMessage("Testo condiviso ricevuto. Crea l’anteprima e rivedi i dati: Binario non importa automaticamente.");
    onReceivedTextAccepted?.();
  }, [onReceivedTextAccepted, receivedText]);

  function createPreview() {
    const candidates = parseGuidedTextLocalRouteImports(rawText).filter(draft => !existing.has(draftKey(draft)));
    if (candidates.length === 0) { setDrafts([]); setSelectedKeys([]); setReviewed(false); setMessage("Nessuna nuova tratta completa trovata. Inserisci origine, destinazione, data e ora: nulla è stato salvato."); return; }
    setDrafts(candidates); setSelectedKeys(candidates.map(draftKey)); setReviewed(false);
    setMessage(`${candidates.length} ${candidates.length === 1 ? "tratta pronta" : "tratte pronte"} per la revisione. Nulla è stato ancora importato.`);
  }

  async function confirm() {
    const selected = drafts.filter(draft => selectedKeys.includes(draftKey(draft)));
    if (!reviewed || selected.length === 0) { setMessage("Seleziona almeno una tratta e conferma di averla verificata. Nessun dato è stato salvato."); return; }
    const imported = await onConfirmImport(selected);
    setRawText(""); setDrafts([]); setSelectedKeys([]); setReviewed(false);
    setMessage(imported ? `${imported} ${imported === 1 ? "tratta importata" : "tratte importate"} solo sul telefono. Puoi modificare ogni dato nell’Agenda.` : "Nessuna tratta importata: potrebbe essere già presente o non più valida.");
    if (imported) acknowledgeNativeAction();
  }

  return <View style={[styles.card, { backgroundColor: colors.background, borderColor: colors.border }]}>
    <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>Importa da testo</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>Incolla una o più tratte oppure usa Condividi testo verso Binario. Il testo viene solo proposto qui: Binario non importa automaticamente e non legge messaggi, appunti o link da solo.</Text>
    <TextInput accessibilityLabel="Testo delle tratte da importare" accessibilityHint="Una riga per tratta. Devi poi controllare l’anteprima prima di importare." value={rawText} onChangeText={value => { setRawText(value.slice(0, maxGuidedTextImportCharacters)); setDrafts([]); setSelectedKeys([]); setReviewed(false); }} placeholder={"Roma Termini → Bologna Centrale · 12/09/2026 alle 08:30\nBologna Centrale → Milano Centrale · 12/09/2026 alle 11:05"} placeholderTextColor={colors.muted} multiline maxLength={maxGuidedTextImportCharacters} textAlignVertical="top" style={[styles.input, { color: colors.foreground, borderColor: colors.border }]} />
    <Text style={[styles.hint, { color: colors.muted }]}>Ogni riga richiede origine, destinazione, data `GG/MM/AAAA` o `AAAA-MM-GG` e ora `HH:MM`. Il testo resta qui finché lo cancelli o completi l’importazione.</Text>
    <Pressable disabled={!rawText.trim()} accessibilityRole="button" accessibilityState={{ disabled: !rawText.trim() }} accessibilityLabel="Crea anteprima delle tratte nel testo" onPress={createPreview} style={({ pressed }) => [styles.action, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: !rawText.trim() ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={styles.actionText}>Crea anteprima</Text></Pressable>
    {drafts.length ? <View style={[styles.preview, { borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.subheading, { color: colors.foreground }]}>Rivedi prima di aggiungere</Text>{drafts.map(draft => { const key = draftKey(draft); const selected = selectedKeys.includes(key); return <Pressable key={key} accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={`${selected ? "Selezionata" : "Non selezionata"}: ${draft.origin} verso ${draft.destination}, ${draft.travelDate}, ${draft.travelTime}`} onPress={() => setSelectedKeys(current => current.includes(key) ? current.filter(item => item !== key) : [...current, key])} style={({ pressed }) => [styles.choice, { borderColor: selected ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{selected ? "✓ " : "○ "}{draft.origin} → {draft.destination}</Text><Text style={{ color: colors.muted }}>{draft.travelDate} · {draft.travelTime}</Text></Pressable>; })}<Pressable accessibilityRole="checkbox" accessibilityState={{ checked: reviewed }} accessibilityLabel="Ho verificato stazioni data e ora; i dati sono programmati, non realtime" onPress={() => setReviewed(value => !value)} style={({ pressed }) => [styles.choice, { borderColor: reviewed ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{reviewed ? "✓ " : "○ "}Ho verificato stazioni, data e ora: sono dati programmati, non realtime.</Text></Pressable><View style={styles.row}><Pressable disabled={!reviewed || selectedKeys.length === 0} accessibilityRole="button" accessibilityState={{ disabled: !reviewed || selectedKeys.length === 0 }} accessibilityLabel="Importa sul telefono le tratte selezionate" onPress={() => void confirm()} style={({ pressed }) => [styles.action, styles.flex, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: !reviewed || selectedKeys.length === 0 ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={styles.actionText}>Importa selezionate</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel="Annulla anteprima testo" onPress={() => { setDrafts([]); setSelectedKeys([]); setReviewed(false); setMessage("Anteprima annullata. Nessun dato è stato modificato."); }} style={({ pressed }) => [styles.cancel, styles.flex, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Annulla</Text></Pressable></View></View> : null}
    {message ? <Text accessibilityLiveRegion="polite" style={[styles.message, { color: colors.muted }]}>{message}</Text> : null}
  </View>;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, padding: 13, gap: 9 }, heading: { fontSize: 16, fontWeight: "800" }, subheading: { fontSize: 15, fontWeight: "800" }, copy: { fontSize: 13, lineHeight: 19 }, hint: { fontSize: 12, lineHeight: 17 }, input: { minHeight: 96, borderWidth: 1, borderRadius: 12, padding: 12, fontSize: 15 }, action: { minHeight: 46, borderWidth: 1, borderRadius: 11, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 }, actionText: { color: "#fff", fontWeight: "800" }, preview: { borderTopWidth: 1, paddingTop: 10, gap: 8 }, choice: { minHeight: 46, borderWidth: 1, borderRadius: 10, paddingHorizontal: 11, justifyContent: "center", gap: 2 }, row: { flexDirection: "row", gap: 8 }, flex: { flex: 1 }, cancel: { minHeight: 46, borderWidth: 1, borderRadius: 11, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 }, message: { fontSize: 13, lineHeight: 19 },
});
