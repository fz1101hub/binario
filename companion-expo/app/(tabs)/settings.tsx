import { ScreenContainer } from "@/components/screen-container";
import { createNativeDiagnosticReport } from "@/lib/diagnostics";
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
  const [status, setStatus] = useState("");
  const [manualTransferPreview, setManualTransferPreview] = useState<ManualRouteTransferPreview[]>([]);
  const [selectedManualTransfer, setSelectedManualTransfer] = useState<string[]>([]);
  const [preferredReminderMinutes, setPreferredReminderMinutes] = useState<LocalJourneyReminderPreference>(15);

  useEffect(() => { void loadLocalJourneyTimingPreferences().then(preferences => setPreferredReminderMinutes(preferences.preferredReminderMinutes)); }, []);

  async function setPreferredReminder(minutes: LocalJourneyReminderPreference) {
    await saveLocalJourneyTimingPreferences(minutes);
    setPreferredReminderMinutes(minutes);
    setStatus(`Margine di anticipo preferito: ${minutes} minuti. Verrà solo proposto nelle nuove tratte e potrai sempre modificarlo o disattivarlo.`);
  }

  async function shareDiagnostic() {
    try {
      const diagnosticPlatform = Platform.OS === "android" || Platform.OS === "ios" || Platform.OS === "web" ? Platform.OS : "other";
      await Share.share({ title: "Diagnostica Binario", message: JSON.stringify(createNativeDiagnosticReport({ platform: diagnosticPlatform, appVersion: Constants.expoConfig?.version ?? null }), null, 2) });
      setStatus("Diagnostica tecnica preparata su tua richiesta. Non include dati personali o ferroviari.");
    } catch { setStatus("Non è stato possibile preparare la diagnostica. Nessun dato è stato inviato automaticamente."); }
  }

  async function clearLocalJourneyData() {
    const result = await clearLocalJourneyArchive({ listScheduledRouteIds, cancelReminder: cancelPlannedRouteReminder, clearRoutes: clearLocalPlannedRoutes, clearItineraries: clearLocalJourneyItineraries });
    setStatus(`Archivio locale cancellato: tratte e itinerari rimossi dal telefono, ${result.clearedReminders} ${result.clearedReminders === 1 ? "promemoria annullato" : "promemoria annullati"}. Le preferenze di accessibilità non sono state modificate.`);
  }

  function confirmClearLocalJourneyData() {
    Alert.alert("Cancellare l’archivio locale?", "Saranno eliminati solo tratte, itinerari e promemoria di questo telefono. Le preferenze di accessibilità restano invariate.", [{ text: "Annulla", style: "cancel" }, { text: "Cancella solo locale", style: "destructive", onPress: () => void clearLocalJourneyData() }]);
  }

  async function exportManualTransfer() {
    const routes = await loadLocalPlannedRoutes();
    const shared = await shareLocalJourneyTransfer(routes);
    setStatus(shared ? "Archivio Binario creato e aperto nella condivisione di sistema. Controllane il contenuto prima di inviarlo." : routes.length === 0 ? "Non ci sono tratte locali da esportare." : "La condivisione file non è disponibile su questo dispositivo.");
  }

  async function inspectManualTransfer() {
    const archive = await pickLocalJourneyTransfer();
    if (!archive) { setStatus("Il file non è un archivio Binario valido oppure non è stato scelto. Nessuna tratta è stata letta."); return; }
    const current = await loadLocalPlannedRoutes();
    const currentPortable = current.map(route => ({ origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, travelDate: route.travelDate, travelTime: route.travelTime, plannedReminderMinutes: route.plannedReminderMinutes, checklist: route.checklist ?? [] }));
    setManualTransferPreview(previewManualRouteTransfer(currentPortable, archive.routes));
    setSelectedManualTransfer([]);
    setStatus("Anteprima archivio pronta. Nessuna tratta è stata importata: seleziona solo le tratte da aggiungere.");
  }

  async function importManualTransfer() {
    const selected = manualTransferPreview.filter(candidate => candidate.state === "new" && selectedManualTransfer.includes(candidate.key)).map(candidate => candidate.route);
    const imported = await importSelectedManualPlannedRoutes(selected);
    setManualTransferPreview([]); setSelectedManualTransfer([]);
    setStatus(`${imported} ${imported === 1 ? "tratta aggiunta" : "tratte aggiunte"} solo sul telefono. Le tratte esistenti non sono state modificate.`);
  }

  const toggle = (key: string, current: string[], set: (next: string[]) => void) => set(current.includes(key) ? current.filter(item => item !== key) : [...current, key]);

  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: colors.foreground }]}>Impostazioni</Text>
    <Text style={[styles.label, { color: colors.muted }]}>Tema</Text>
    <View style={styles.row}>{modes.map(item => <Pressable key={item} accessibilityRole="button" accessibilityState={{ selected: mode === item }} accessibilityLabel={`Tema ${item === "system" ? "sistema" : item === "light" ? "chiaro" : "scuro"}`} onPress={() => setMode(item)} style={({ pressed }) => [styles.choice, { borderColor: mode === item ? colors.primary : colors.border, backgroundColor: mode === item ? colors.primary : colors.surface, opacity: pressed ? 0.75 : 1 }]}><Text style={{ color: mode === item ? "#fff" : colors.foreground, fontWeight: "800" }}>{item === "system" ? "Sistema" : item === "light" ? "Chiaro" : "Scuro"}</Text></Pressable>)}</View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.heading, { color: colors.foreground }]}>Accessibilità</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Queste preferenze restano solo sul dispositivo e non modificano tratte, dati o notifiche.</Text>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: highContrast }} accessibilityLabel="Contrasto elevato" onPress={() => setHighContrast(!highContrast)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Contrasto elevato</Text><Text style={{ color: colors.muted }}>{highContrast ? "Attivo" : "Disattivo"}</Text></Pressable>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: reduceMotion }} accessibilityLabel="Riduci movimento" onPress={() => setReduceMotion(!reduceMotion)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Riduci movimento</Text><Text style={{ color: colors.muted }}>{reduceMotion ? "Attivo" : "Disattivo"}</Text></Pressable>
      <Pressable accessibilityRole="switch" accessibilityState={{ checked: simpleMode }} accessibilityLabel="Modalità semplificata" accessibilityHint="Riduce istruzioni e dettagli secondari senza nascondere la tratta o lo stato dati" onPress={() => setSimpleMode(!simpleMode)} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Modalità semplificata</Text><Text style={{ color: colors.muted }}>{simpleMode ? "Attiva" : "Disattiva"}</Text></Pressable>
    </View>
    <View accessible accessibilityLabel="Margine di anticipo locale" style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.heading, { color: colors.foreground }]}>Margine prima della partenza</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Scegli un anticipo da proporre quando pianifichi una tratta. Non attiva promemoria da solo e puoi sempre scegliere un valore diverso o nessun promemoria.</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel="Margine di anticipo preferito" style={styles.row}>{localJourneyReminderOptions.map(minutes => <Pressable key={minutes} accessibilityRole="radio" accessibilityState={{ checked: preferredReminderMinutes === minutes }} accessibilityLabel={`Proponi ${minutes} minuti di anticipo`} onPress={() => void setPreferredReminder(minutes)} style={({ pressed }) => [styles.choice, { borderColor: preferredReminderMinutes === minutes ? colors.primary : colors.border, backgroundColor: preferredReminderMinutes === minutes ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: preferredReminderMinutes === minutes ? "#fff" : colors.foreground, fontWeight: "800" }}>{minutes} min</Text></Pressable>)}</View>
    </View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.heading, { color: colors.foreground }]}>Telefono e smartwatch</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Binario usa il collegamento di sistema tra questo telefono e lo smartwatch Wear OS associato. Non richiede account, codice, password o token. Il watch riceve solo una sintesi della tratta pianificata e mai credenziali, note o dati ferroviari inventati.</Text>
    </View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Text style={[styles.heading, { color: colors.foreground }]}>Sincronizzazione remota</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Non disponibile in questa build: il servizio remoto non è ancora attivo.</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Nessun account può essere collegato e nessuna tratta può essere inviata o ricevuta. Non sono disponibili controlli push, pull, consenso o revoca remota.</Text>
    </View>
    <View
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <Text style={[styles.heading, { color: colors.foreground }]}>Dati e privacy</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Tratte, itinerari, preferenze e promemoria restano su questo telefono. I promemoria sono notifiche locali e puoi cancellarli con l’archivio. La stazione più vicina usa una posizione in primo piano una sola volta, senza salvarla o inviarla; la dettatura della tastiera è gestita dal sistema e Binario non registra audio.</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Il trasferimento è solo manuale e passa alla destinazione scelta nella condivisione di sistema. Questa build non esegue pairing o sincronizzazione remota: i relativi comandi non trasferiscono tratte. L’informativa completa è PRIVACY.md nel repository; manca ancora un URL pubblico verificato e un recapito privacy, quindi non viene mostrato un link inventato nell’app.</Text>
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>Trasferimento manuale</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Esporta o scegli un archivio Binario solo quando lo desideri. Il file contiene le tratte pianificate e le checklist di viaggio, ma esclude account, token, coordinate, audio, note e stati realtime.</Text>
      <Pressable accessibilityRole="button" accessibilityLabel="Esporta l’archivio locale Binario con la condivisione di sistema" accessibilityHint="Crea un file soltanto su tua richiesta; nessun backup automatico" onPress={() => void exportManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Esporta archivio</Text><Text style={{ color: colors.muted }}>Manuale</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="Scegli un archivio Binario da importare" accessibilityHint="Mostra un’anteprima prima di aggiungere qualunque tratta" onPress={() => void inspectManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Importa archivio</Text><Text style={{ color: colors.muted }}>Con anteprima</Text></Pressable>
      {manualTransferPreview.length ? <View style={[styles.preview, { borderColor: colors.border }]}><Text style={[styles.heading, { color: colors.foreground }]}>Anteprima archivio</Text>{manualTransferPreview.map(candidate => <Pressable key={candidate.key} disabled={candidate.state !== "new"} accessibilityRole="checkbox" accessibilityState={{ checked: selectedManualTransfer.includes(candidate.key), disabled: candidate.state !== "new" }} accessibilityLabel={`${candidate.route.origin} verso ${candidate.route.destination}. ${candidate.state === "new" ? "Pronta da aggiungere" : "Già presente e non duplicata"}`} onPress={() => toggle(candidate.key, selectedManualTransfer, setSelectedManualTransfer)} style={[styles.choice, { borderColor: colors.border, opacity: candidate.state === "new" ? 1 : 0.65 }]}><Text style={{ color: colors.foreground }}>{selectedManualTransfer.includes(candidate.key) ? "✓ " : "○ "}{candidate.route.origin} → {candidate.route.destination}</Text><Text style={{ color: colors.muted }}>{candidate.state === "new" ? "Pronta da aggiungere" : "Già presente: non viene duplicata"}</Text></Pressable>)}<Pressable disabled={selectedManualTransfer.length === 0} accessibilityRole="button" accessibilityState={{ disabled: selectedManualTransfer.length === 0 }} accessibilityLabel="Aggiungi sul telefono le tratte selezionate dall’archivio" onPress={() => void importManualTransfer()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: selectedManualTransfer.length === 0 ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Aggiungi sul telefono</Text><Text style={{ color: colors.muted }}>{selectedManualTransfer.length} selezionate</Text></Pressable></View> : null}
      <Pressable accessibilityRole="button" accessibilityLabel="Apri guida agli stati Binario" accessibilityHint="Spiega il significato di programmato, realtime, scaduto e non disponibile" onPress={() => router.push("/guide")} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Come leggere i dati</Text><Text style={{ color: colors.muted }}>Guida</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="Prepara diagnostica tecnica" accessibilityHint="Apre la condivisione di un report senza dati personali o ferroviari" onPress={() => void shareDiagnostic()} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Prepara diagnostica tecnica</Text><Text style={{ color: colors.muted }}>Solo su richiesta</Text></Pressable>
      <Pressable accessibilityRole="button" accessibilityLabel="Cancella tratte e promemoria locali da questo telefono" accessibilityHint="Chiederà conferma e non modificherà le preferenze di accessibilità" onPress={confirmClearLocalJourneyData} style={({ pressed }) => [styles.accessibilityAction, { borderColor: colors.danger, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.danger, fontWeight: "800" }}>Cancella archivio locale</Text><Text style={{ color: colors.muted }}>Solo telefono</Text></Pressable>
    </View>
    {status ? <Text accessibilityLiveRegion="polite" style={[styles.status, { color: colors.muted }]}>{status}</Text> : null}
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { padding: 20, gap: 14 }, title: { fontSize: 30, fontWeight: "900" }, label: { fontSize: 14, fontWeight: "700" }, row: { flexDirection: "row", gap: 8, flexWrap: "wrap" }, choice: { borderWidth: 1, borderRadius: 12, paddingVertical: 11, paddingHorizontal: 14, gap: 4 }, card: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 9 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 15, lineHeight: 22 }, preview: { borderTopWidth: 1, gap: 8, paddingTop: 12 }, accessibilityAction: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 13, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }, status: { fontSize: 14, lineHeight: 20 } });
