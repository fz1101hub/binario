import { addLocalPlannedRoute } from "@/lib/local-routes";
import { applyLocalJourneyChecklistPreset, localJourneyChecklistPresets, type ControlledLocalRouteDraft, type LocalJourneyChecklistItem } from "@/lib/local-route-contract";
import { parseLocalDictation } from "@/lib/local-dictation";
import { requestNearestOfflineStation } from "@/lib/nearby-offline-stations";
import { createLocalJourneyTemplate, loadLocalJourneyTemplates, removeLocalJourneyTemplate, saveLocalJourneyTemplate, type LocalJourneyTemplate } from "@/lib/local-journey-templates";
import { acknowledgeNativeAction, confirmNativeAction } from "@/lib/native-feedback";
import { loadLocalFavoriteStations, saveLocalFavoriteStations, toggleLocalFavoriteStation, type LocalFavoriteStation } from "@/lib/local-favorite-stations";
import { loadLocalJourneyTimingPreferences } from "@/lib/local-journey-timing";
import { useCompanionTheme } from "@/lib/theme";
import { StationAutocompleteInput } from "@/components/station-autocomplete-input";
import { formatLocalJourneyDate, formatLocalJourneyTime, fromLocalDateAndTime, toLocalIsoDate, toLocalTime } from "@/lib/local-date-time";
import { useEffect, useRef, useState } from "react";
import DateTimePicker, { type DateTimePickerEvent } from "@react-native-community/datetimepicker";
import { AccessibilityInfo, Platform, Pressable, StyleSheet, Text, TextInput, View } from "react-native";

function todayIso() { return toLocalIsoDate(new Date()); }
const reminderOptions: ReadonlyArray<number | null> = [null, 0, 10, 15, 30, 60];

function reminderLabel(minutes: number | null) {
  if (minutes === null) return "Nessuno";
  if (minutes === 0) return "All’ora";
  return `${minutes} min`;
}

type LocalPlanCardPlanningDraft = { kind: "return" | "replan"; draft: ControlledLocalRouteDraft };

export function LocalPlanCard({ onSaved, planningDraft, onPlanningDraftApplied }: { onSaved: () => void; planningDraft?: LocalPlanCardPlanningDraft | null; onPlanningDraftApplied?: () => void }) {
  const { colors } = useCompanionTheme();
  const [origin, setOrigin] = useState("");
  const [originStationCode, setOriginStationCode] = useState<string | null>(null);
  const [destination, setDestination] = useState("");
  const [destinationStationCode, setDestinationStationCode] = useState<string | null>(null);
  const [travelDate, setTravelDate] = useState(todayIso);
  const [travelTime, setTravelTime] = useState("");
  const [plannedReminderMinutes, setPlannedReminderMinutes] = useState<number | null>(15);
  const [picker, setPicker] = useState<"date" | "time" | null>(null);
  const [message, setMessage] = useState("");
  const [saving, setSaving] = useState(false);
  const [dictationText, setDictationText] = useState("");
  const [fieldError, setFieldError] = useState("");
  const [locatingNearby, setLocatingNearby] = useState(false);
  const [nearbyStatus, setNearbyStatus] = useState("");
  const [templates, setTemplates] = useState<LocalJourneyTemplate[]>([]);
  const [templateLabel, setTemplateLabel] = useState("");
  const [favoriteStations, setFavoriteStations] = useState<LocalFavoriteStation[]>([]);
  const [checklist, setChecklist] = useState<LocalJourneyChecklistItem[]>([]);
  const [scheduleConfirmation, setScheduleConfirmation] = useState({ date: false, time: false });
  const destinationRef = useRef<TextInput>(null);

  useEffect(() => { if (message && Platform.OS !== "web") AccessibilityInfo.announceForAccessibility(message); }, [message]);
  useEffect(() => { void loadLocalJourneyTemplates().then(setTemplates); }, []);
  useEffect(() => { void loadLocalFavoriteStations().then(setFavoriteStations); }, []);
  useEffect(() => { void loadLocalJourneyTimingPreferences().then(preferences => setPlannedReminderMinutes(preferences.preferredReminderMinutes)); }, []);
  useEffect(() => {
    if (!planningDraft) return;
    const { draft, kind } = planningDraft;
    setOrigin(draft.origin);
    setOriginStationCode(draft.originStationCode ?? null);
    setDestination(draft.destination);
    setDestinationStationCode(draft.destinationStationCode ?? null);
    setTravelDate(todayIso());
    setTravelTime("");
    setPlannedReminderMinutes(draft.plannedReminderMinutes);
    setChecklist(draft.checklist);
    setScheduleConfirmation({ date: true, time: true });
    setFieldError("");
    setMessage(kind === "return" ? "Ritorno preparato. Scegli una nuova data e ora prima di salvare." : "Tratta ripianificata. Scegli una nuova data e ora prima di salvare.");
    onPlanningDraftApplied?.();
  }, [onPlanningDraftApplied, planningDraft]);

  function applyDictation() {
    const draft = parseLocalDictation(dictationText);
    if (!draft.origin && !draft.destination && !draft.travelDate && !draft.travelTime) {
      setMessage("Non ho riconosciuto una tratta completa. Modifica il testo o compila i campi manualmente.");
      return;
    }
    if (draft.origin) { setOrigin(draft.origin); setOriginStationCode(null); }
    if (draft.destination) { setDestination(draft.destination); setDestinationStationCode(null); }
    if (draft.travelDate) setTravelDate(draft.travelDate);
    if (draft.travelTime) setTravelTime(draft.travelTime);
    setMessage("Bozza applicata. Controlla e modifica ogni campo prima di salvare la tratta.");
  }

  async function useNearestOrigin() {
    setLocatingNearby(true);
    setNearbyStatus("Cerco la stazione più vicina sul dispositivo…");
    try {
      const result = await requestNearestOfflineStation();
      if (result.state === "ready") {
        setOrigin(result.station.station.name);
        setOriginStationCode(result.station.station.code);
        setFieldError("");
        setNearbyStatus(`Proposta ${result.station.station.name}, a ${result.station.distanceKm.toFixed(1)} km. Puoi modificarla manualmente.`);
        acknowledgeNativeAction();
        return;
      }
      setNearbyStatus(result.state === "denied" ? "Permesso posizione non disponibile. Puoi cercare manualmente." : result.state === "empty" ? "Il catalogo offline non ha coordinate utilizzabili vicino a te." : "Non è possibile calcolare la stazione vicina ora. Puoi cercare manualmente.");
    } finally { setLocatingNearby(false); }
  }

  async function saveTemplate() {
    const template = createLocalJourneyTemplate({ id: `template-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, label: templateLabel || `${origin} → ${destination}`, origin, originStationCode: originStationCode ?? "", destination, destinationStationCode: destinationStationCode ?? "", travelTime, plannedReminderMinutes, checklist: checklist.map(item => ({ ...item, completed: false })) });
    if (!template) { setMessage("Completa due stazioni selezionate e un orario prima di salvare un modello."); return; }
    const next = await saveLocalJourneyTemplate(template);
    setTemplates(next); setTemplateLabel("");
    setMessage("Modello salvato solo su questo dispositivo. Scegli sempre la data prima di salvare una tratta.");
    confirmNativeAction();
  }

  function applyTemplate(template: LocalJourneyTemplate) {
    setOrigin(template.origin); setOriginStationCode(template.originStationCode);
    setDestination(template.destination); setDestinationStationCode(template.destinationStationCode);
    setTravelTime(template.travelTime); setPlannedReminderMinutes(template.plannedReminderMinutes); setChecklist(template.checklist);
    setFieldError(""); setScheduleConfirmation({ date: false, time: false }); setMessage(`Modello “${template.label}” applicato. Scegli la data e conferma la tratta.`);
  }

  async function deleteTemplate(id: string) { const next = await removeLocalJourneyTemplate(id); setTemplates(next); setMessage("Modello locale eliminato."); }

  async function toggleOriginFavorite() {
    if (!originStationCode || !origin) return;
    const next = toggleLocalFavoriteStation(favoriteStations, { code: originStationCode, name: origin });
    await saveLocalFavoriteStations(next); setFavoriteStations(next);
    const exists = next.some(item => item.code === originStationCode);
    setMessage(exists ? `${origin} aggiunta alle stazioni preferite locali.` : `${origin} rimossa dalle stazioni preferite locali.`);
    acknowledgeNativeAction();
  }

  async function save() {
    setSaving(true);
    setMessage("");
    try {
      if (scheduleConfirmation.date || scheduleConfirmation.time) {
        setFieldError("Scegli una nuova data e una nuova ora prima di salvare il ritorno o la tratta ripianificata.");
        setMessage("Completa nuova data e nuova ora: nessun viaggio è stato salvato.");
        return;
      }
      if (!originStationCode || !destinationStationCode) {
        setFieldError("Seleziona partenza e arrivo dai suggerimenti del catalogo offline prima di salvare.");
        setMessage("Correggi i campi stazione prima di salvare.");
        destinationRef.current?.focus();
        return;
      }
      const route = await addLocalPlannedRoute({ origin, originStationCode, destination, destinationStationCode, travelDate, travelTime, plannedReminderMinutes, checklist });
      if (!route) {
        setFieldError("Controlla partenza, arrivo, data e ora. Le stazioni devono essere diverse.");
        setMessage("Correggi i campi della tratta prima di salvare.");
        destinationRef.current?.focus();
        return;
      }
      setOrigin("");
      setOriginStationCode(null);
      setDestination("");
      setDestinationStationCode(null);
      setTravelTime("");
      setScheduleConfirmation({ date: false, time: false });
      setFieldError("");
      setMessage("Tratta salvata solo sul dispositivo. È pianificata, non realtime.");
      confirmNativeAction();
      onSaved();
    } finally { setSaving(false); }
  }

  function onDateTimeChange(event: DateTimePickerEvent, value?: Date) {
    const currentPicker = picker;
    if (Platform.OS !== "ios") setPicker(null);
    if (event.type === "dismissed" || !value || !currentPicker) return;
    if (currentPicker === "date") setTravelDate(toLocalIsoDate(value));
    else setTravelTime(toLocalTime(value));
    setScheduleConfirmation(current => ({ ...current, [currentPicker]: false }));
  }

  return <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
    <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>Pianifica sul telefono</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>Inizia senza account. Inserisci una tratta personale e programma, se vuoi, un promemoria locale.</Text>
    <View accessible accessibilityLabel="Dittatura locale con tastiera di sistema" style={[styles.dictation, { borderColor: colors.border, backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[styles.dictationHeading, { color: colors.foreground }]}>Detta una tratta</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>Se la tastiera offre la dettatura, tocca il suo microfono e pronuncia ad esempio: “Da Roma Termini a Firenze Santa Maria Novella domani alle 09:35”. Binario non apre il microfono, non registra e non invia audio.</Text>
      <TextInput accessibilityLabel="Testo della dettatura da rivedere" accessibilityHint="Usa il microfono della tastiera di sistema oppure scrivi. Il testo resta modificabile e non viene salvato come audio." value={dictationText} onChangeText={setDictationText} placeholder="Scrivi o detta una tratta completa" placeholderTextColor={colors.muted} multiline maxLength={240} style={[styles.input, styles.dictationInput, { color: colors.foreground, borderColor: colors.border }]} />
      <Pressable accessibilityRole="button" accessibilityLabel="Interpreta il testo della dettatura" accessibilityHint="Copia soltanto i campi riconosciuti nella tratta; controllali prima del salvataggio" onPress={applyDictation} style={({ pressed }) => [styles.secondaryButton, { borderColor: colors.primary, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.secondaryButtonText, { color: colors.primary }]}>Interpreta il testo</Text></Pressable>
    </View>
    {favoriteStations.length ? <View accessible accessibilityLabel="Stazioni preferite locali" style={[favoriteStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}><Text style={[favoriteStyles.heading, { color: colors.foreground }]}>Stazioni preferite</Text><Text style={[favoriteStyles.copy, { color: colors.muted }]}>Tocca una stazione per proporla come partenza. Puoi modificarla.</Text><View style={favoriteStyles.list}>{favoriteStations.map(station => <Pressable key={station.code} accessibilityRole="button" accessibilityLabel={`Usa ${station.name} come partenza`} onPress={() => { setOrigin(station.name); setOriginStationCode(station.code); setFieldError(""); acknowledgeNativeAction(); }} style={({ pressed }) => [favoriteStyles.chip, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{station.name}</Text></Pressable>)}</View></View> : null}
    <StationAutocompleteInput label="Partenza" value={origin} stationCode={originStationCode} description="Scrivi almeno due lettere, scegli un suggerimento oppure usa la posizione una sola volta." error={fieldError} onChange={text => { setOrigin(text); setOriginStationCode(null); setFieldError(""); }} onSelect={station => { setOrigin(station.name); setOriginStationCode(station.code); setFieldError(""); }} onUseNearestStation={() => void useNearestOrigin()} locatingNearby={locatingNearby} nearbyStatus={nearbyStatus} />
    {originStationCode ? <Pressable accessibilityRole="button" accessibilityLabel={favoriteStations.some(item => item.code === originStationCode) ? "Rimuovi la partenza dalle stazioni preferite" : "Aggiungi la partenza alle stazioni preferite"} onPress={() => void toggleOriginFavorite()} style={({ pressed }) => [favoriteStyles.toggle, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{favoriteStations.some(item => item.code === originStationCode) ? "Rimuovi dai preferiti" : "Aggiungi ai preferiti"}</Text></Pressable> : null}
    <StationAutocompleteInput inputRef={destinationRef} label="Arrivo" value={destination} stationCode={destinationStationCode} description="Scegli una stazione diversa dalla partenza." error={fieldError} onChange={text => { setDestination(text); setDestinationStationCode(null); setFieldError(""); }} onSelect={station => { setDestination(station.name); setDestinationStationCode(station.code); setFieldError(""); }} />
    <View style={styles.row}>
      {Platform.OS === "web" ? <TextInput accessibilityLabel="Data del viaggio nel formato anno mese giorno" value={travelDate} onChangeText={setTravelDate} placeholder="AAAA-MM-GG" placeholderTextColor={colors.muted} style={[styles.input, styles.half, { color: colors.foreground, borderColor: colors.border }]} /> : <Pressable accessibilityRole="button" accessibilityLabel={`Data del viaggio: ${formatLocalJourneyDate(travelDate)}`} accessibilityHint="Apre il selettore data di sistema" onPress={() => setPicker("date")} style={({ pressed }) => [styles.pickerButton, styles.half, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.pickerLabel, { color: colors.muted }]}>Data</Text><Text style={[styles.pickerValue, { color: colors.foreground }]}>{formatLocalJourneyDate(travelDate)}</Text></Pressable>}
      {Platform.OS === "web" ? <TextInput accessibilityLabel="Ora del viaggio nel formato ore minuti" value={travelTime} onChangeText={setTravelTime} placeholder="HH:MM" placeholderTextColor={colors.muted} keyboardType="numbers-and-punctuation" returnKeyType="done" style={[styles.input, styles.half, { color: colors.foreground, borderColor: colors.border }]} /> : <Pressable accessibilityRole="button" accessibilityLabel={`Ora del viaggio: ${travelTime ? formatLocalJourneyTime(travelTime) : "non selezionata"}`} accessibilityHint="Apre il selettore orario di sistema" onPress={() => setPicker("time")} style={({ pressed }) => [styles.pickerButton, styles.half, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.pickerLabel, { color: colors.muted }]}>Ora</Text><Text style={[styles.pickerValue, { color: colors.foreground }]}>{travelTime ? formatLocalJourneyTime(travelTime) : "Seleziona"}</Text></Pressable>}
    </View>
    {picker ? <DateTimePicker value={fromLocalDateAndTime(travelDate, travelTime)} mode={picker} is24Hour display={Platform.OS === "ios" ? "spinner" : "default"} onChange={onDateTimeChange} /> : null}
    <View style={[templateStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}> 
      <Text accessibilityRole="header" style={[templateStyles.heading, { color: colors.foreground }]}>Modelli di viaggio</Text>
      <Text style={[templateStyles.copy, { color: colors.muted }]}>Salva percorso, orario e promemoria per riutilizzarli. La data resta sempre da scegliere.</Text>
      <View style={templateStyles.row}><TextInput accessibilityLabel="Nome del modello di viaggio" value={templateLabel} onChangeText={setTemplateLabel} placeholder="Es. Casa lavoro" placeholderTextColor={colors.muted} maxLength={80} style={[styles.input, templateStyles.input, { color: colors.foreground, borderColor: colors.border }]} /><Pressable accessibilityRole="button" accessibilityLabel="Salva il modello viaggio corrente solo sul dispositivo" onPress={() => void saveTemplate()} style={({ pressed }) => [templateStyles.saveButton, { borderColor: colors.primary, opacity: pressed ? 0.7 : 1 }]}><Text style={[templateStyles.saveButtonText, { color: colors.primary }]}>Salva</Text></Pressable></View>
      {templates.length === 0 ? <Text style={[templateStyles.copy, { color: colors.muted }]}>Completa il planner e salva il primo modello per riutilizzare percorso e orario.</Text> : templates.map(template => <View key={template.id} style={[templateStyles.item, { borderColor: colors.border }]}><View><Text style={[templateStyles.name, { color: colors.foreground }]}>{template.label}</Text><Text style={[templateStyles.copy, { color: colors.muted }]}>{template.origin} → {template.destination} · {template.travelTime}</Text></View><View style={templateStyles.actions}><Pressable accessibilityRole="button" accessibilityLabel={`Applica modello ${template.label}`} onPress={() => applyTemplate(template)} style={({ pressed }) => [templateStyles.action, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Applica</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={`Elimina modello ${template.label}`} onPress={() => void deleteTemplate(template.id)} style={({ pressed }) => [templateStyles.action, { borderColor: colors.danger, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.danger, fontWeight: "800" }}>Elimina</Text></Pressable></View></View>)}
    </View>
    <View accessible accessibilityLabel="Preparazione ferroviaria e di viaggio" style={[checklistStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[checklistStyles.heading, { color: colors.foreground }]}>Preparazione del viaggio</Text>
      <Text style={[checklistStyles.copy, { color: colors.muted }]}>Scegli solo ciò che serve a questa tratta. Le voci restano modificabili nell’Agenda e non vengono inviate a widget, watch o sincronizzazione.</Text>
      <View style={checklistStyles.actions}>{localJourneyChecklistPresets.map(preset => <Pressable key={preset.id} accessibilityRole="button" accessibilityLabel={`Applica preparazione ${preset.label} alla tratta`} onPress={() => { setChecklist(applyLocalJourneyChecklistPreset({ checklist }, preset.id)); setMessage(`Preparazione “${preset.label}” applicata alla tratta. Puoi controllarla nell’Agenda.`); acknowledgeNativeAction(); }} style={({ pressed }) => [checklistStyles.action, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{preset.label}</Text></Pressable>)}</View>
    </View>
    <View accessible accessibilityRole="radiogroup" accessibilityLabel="Promemoria locale" style={[styles.reminder, { borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.reminderHeading, { color: colors.foreground }]}>Promemoria locale</Text>
      <Text style={[styles.reminderCopy, { color: colors.muted }]}>Facoltativo e programmato sul telefono. La scelta iniziale usa il margine locale impostato; puoi cambiarla o disattivarla per questa tratta. Non controlla ritardi, binari o cancellazioni.</Text>
      <View style={styles.reminderOptions}>{reminderOptions.map(option => <Pressable key={String(option)} accessibilityRole="radio" accessibilityState={{ checked: plannedReminderMinutes === option }} accessibilityLabel={`Promemoria ${reminderLabel(option)}`} onPress={() => setPlannedReminderMinutes(option)} style={({ pressed }) => [styles.reminderOption, { borderColor: plannedReminderMinutes === option ? colors.primary : colors.border, backgroundColor: plannedReminderMinutes === option ? colors.background : colors.surface, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.reminderOptionText, { color: plannedReminderMinutes === option ? colors.primary : colors.foreground }]}>{reminderLabel(option)}</Text></Pressable>)}</View>
    </View>
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: saving, busy: saving }} accessibilityLabel="Salva tratta pianificata sul dispositivo" accessibilityHint="Non richiede account e non crea dati realtime" disabled={saving} onPress={() => void save()} style={({ pressed }) => [styles.button, { backgroundColor: colors.primary, opacity: pressed || saving ? 0.7 : 1 }]}><Text style={styles.buttonText}>{saving ? "Salvataggio…" : "Salva tratta"}</Text></Pressable>
    {message ? <Text accessibilityRole={fieldError ? "alert" : "text"} accessibilityLiveRegion={fieldError ? "assertive" : "polite"} style={[styles.message, { color: fieldError ? colors.danger : colors.muted }]}>{message}</Text> : null}
  </View>;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 18, padding: 16, gap: 10 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 14, lineHeight: 20 }, input: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, fontSize: 15 }, row: { flexDirection: "row", flexWrap: "wrap", gap: 8 }, half: { flexGrow: 1, flexBasis: 150, minWidth: 140 }, pickerButton: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, justifyContent: "center" }, pickerLabel: { fontSize: 12, fontWeight: "700" }, pickerValue: { fontSize: 15, fontWeight: "800", marginTop: 2 }, button: { minHeight: 48, borderRadius: 12, alignItems: "center", justifyContent: "center" }, buttonText: { color: "#fff", fontWeight: "800" }, message: { fontSize: 14, lineHeight: 20 }, dictation: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 8 }, dictationHeading: { fontSize: 16, fontWeight: "800" }, dictationInput: { minHeight: 76, paddingTop: 10, textAlignVertical: "top" }, secondaryButton: { minHeight: 42, borderWidth: 1, borderRadius: 10, alignItems: "center", justifyContent: "center" }, secondaryButtonText: { fontWeight: "800" }, reminder: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 7 }, reminderHeading: { fontSize: 15, fontWeight: "800" }, reminderCopy: { fontSize: 13, lineHeight: 18 }, reminderOptions: { flexDirection: "row", flexWrap: "wrap", gap: 6 }, reminderOption: { minHeight: 44, borderWidth: 1, borderRadius: 18, paddingHorizontal: 10, justifyContent: "center" }, reminderOptionText: { fontSize: 13, fontWeight: "800" },
});

const templateStyles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 8 }, heading: { fontSize: 16, fontWeight: "800" }, copy: { fontSize: 12, lineHeight: 17 }, row: { flexDirection: "row", gap: 8 }, input: { flex: 1, minWidth: 0 }, saveButton: { minHeight: 48, borderWidth: 1, borderRadius: 12, alignItems: "center", justifyContent: "center", paddingHorizontal: 13 }, saveButtonText: { fontWeight: "800" }, item: { borderTopWidth: 1, paddingTop: 8, gap: 7 }, name: { fontSize: 14, fontWeight: "800" }, actions: { flexDirection: "row", flexWrap: "wrap", gap: 7 }, action: { minHeight: 42, borderWidth: 1, borderRadius: 10, justifyContent: "center", paddingHorizontal: 11 },
});

const favoriteStyles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 7 }, heading: { fontSize: 16, fontWeight: "800" }, copy: { fontSize: 12, lineHeight: 17 }, list: { flexDirection: "row", flexWrap: "wrap", gap: 7 }, chip: { minHeight: 44, borderWidth: 1, borderRadius: 18, justifyContent: "center", paddingHorizontal: 12 }, toggle: { minHeight: 42, borderWidth: 1, borderRadius: 10, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 },
});

const checklistStyles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 8 }, heading: { fontSize: 16, fontWeight: "800" }, copy: { fontSize: 12, lineHeight: 17 }, actions: { flexDirection: "row", flexWrap: "wrap", gap: 7 }, action: { minHeight: 42, borderWidth: 1, borderRadius: 18, justifyContent: "center", paddingHorizontal: 11 },
});
