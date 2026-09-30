import { addLocalPlannedRoute } from "@/lib/local-routes";
import { applyLocalJourneyChecklistPreset, localJourneyChecklistPresets, type ControlledLocalRouteDraft, type LocalJourneyChecklistItem } from "@/lib/local-route-contract";
import { parseLocalDictation } from "@/lib/local-dictation";
import { requestNearestOfflineStation } from "@/lib/nearby-offline-stations";
import { createLocalJourneyTemplate, loadLocalJourneyTemplates, removeLocalJourneyTemplate, saveLocalJourneyTemplate, type LocalJourneyTemplate } from "@/lib/local-journey-templates";
import { acknowledgeNativeAction, confirmNativeAction } from "@/lib/native-feedback";
import { loadLocalFavoriteStations, saveLocalFavoriteStations, toggleLocalFavoriteStation, type LocalFavoriteStation } from "@/lib/local-favorite-stations";
import { loadLocalJourneyTimingPreferences } from "@/lib/local-journey-timing";
import { useCompanionTheme } from "@/lib/theme";
import { createTranslator, useTranslation } from "@/lib/i18n";
import { StationAutocompleteInput } from "@/components/station-autocomplete-input";
import { formatLocalJourneyDate, formatLocalJourneyTime, fromLocalDateAndTime, toLocalIsoDate, toLocalTime } from "@/lib/local-date-time";
import { useEffect, useRef, useState } from "react";
import DateTimePicker, { type DateTimePickerEvent } from "@react-native-community/datetimepicker";
import { AccessibilityInfo, Platform, Pressable, StyleSheet, Text, TextInput, View } from "react-native";

function todayIso() { return toLocalIsoDate(new Date()); }
const reminderOptions: ReadonlyArray<number | null> = [null, 0, 10, 15, 30, 60];

function reminderLabel(minutes: number | null, locale?: Parameters<typeof createTranslator>[0]) {
  const t = createTranslator(locale);
  if (minutes === null) return t("plan.reminder.none");
  if (minutes === 0) return t("plan.reminder.atTime");
  return t("plan.reminder.minutes", { minutes });
}

type LocalPlanCardPlanningDraft = { kind: "return" | "replan"; draft: ControlledLocalRouteDraft };

export function LocalPlanCard({ onSaved, planningDraft, onPlanningDraftApplied }: { onSaved: () => void; planningDraft?: LocalPlanCardPlanningDraft | null; onPlanningDraftApplied?: () => void }) {
  const { colors } = useCompanionTheme();
  const { locale, t } = useTranslation();
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
    setMessage(kind === "return" ? t("planner.returnPrepared") : t("planner.replanPrepared"));
    onPlanningDraftApplied?.();
  }, [onPlanningDraftApplied, planningDraft]);

  function applyDictation() {
    const draft = parseLocalDictation(dictationText);
    if (!draft.origin && !draft.destination && !draft.travelDate && !draft.travelTime) {
      setMessage(t("planner.dictationIncomplete"));
      return;
    }
    if (draft.origin) { setOrigin(draft.origin); setOriginStationCode(null); }
    if (draft.destination) { setDestination(draft.destination); setDestinationStationCode(null); }
    if (draft.travelDate) setTravelDate(draft.travelDate);
    if (draft.travelTime) setTravelTime(draft.travelTime);
    setMessage(t("planner.dictationApplied"));
  }

  async function useNearestOrigin() {
    setLocatingNearby(true);
    setNearbyStatus(t("planner.nearbySearching"));
    try {
      const result = await requestNearestOfflineStation();
      if (result.state === "ready") {
        setOrigin(result.station.station.name);
        setOriginStationCode(result.station.station.code);
        setFieldError("");
        setNearbyStatus(t("planner.nearbyReady", { station: result.station.station.name, distance: result.station.distanceKm.toFixed(1) }));
        acknowledgeNativeAction();
        return;
      }
      setNearbyStatus(result.state === "denied" ? t("planner.nearbyDenied") : result.state === "empty" ? t("planner.nearbyEmpty") : t("planner.nearbyUnavailable"));
    } finally { setLocatingNearby(false); }
  }

  async function saveTemplate() {
    const template = createLocalJourneyTemplate({ id: `template-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, label: templateLabel || `${origin} → ${destination}`, origin, originStationCode: originStationCode ?? "", destination, destinationStationCode: destinationStationCode ?? "", travelTime, plannedReminderMinutes, checklist: checklist.map(item => ({ ...item, completed: false })) });
    if (!template) { setMessage(t("planner.templateInvalid")); return; }
    const next = await saveLocalJourneyTemplate(template);
    setTemplates(next); setTemplateLabel("");
    setMessage(t("planner.templateSaved"));
    confirmNativeAction();
  }

  function applyTemplate(template: LocalJourneyTemplate) {
    setOrigin(template.origin); setOriginStationCode(template.originStationCode);
    setDestination(template.destination); setDestinationStationCode(template.destinationStationCode);
    setTravelTime(template.travelTime); setPlannedReminderMinutes(template.plannedReminderMinutes); setChecklist(template.checklist);
    setFieldError(""); setScheduleConfirmation({ date: false, time: false }); setMessage(t("planner.templateApplied", { label: template.label }));
  }

  async function deleteTemplate(id: string) { const next = await removeLocalJourneyTemplate(id); setTemplates(next); setMessage(t("planner.templateDeleted")); }

  async function toggleOriginFavorite() {
    if (!originStationCode || !origin) return;
    const next = toggleLocalFavoriteStation(favoriteStations, { code: originStationCode, name: origin });
    await saveLocalFavoriteStations(next); setFavoriteStations(next);
    const exists = next.some(item => item.code === originStationCode);
    setMessage(exists ? t("planner.favoriteAdded", { station: origin }) : t("planner.favoriteRemoved", { station: origin }));
    acknowledgeNativeAction();
  }

  async function save() {
    setSaving(true);
    setMessage("");
    try {
      if (scheduleConfirmation.date || scheduleConfirmation.time) {
        setFieldError(t("planner.newScheduleError"));
        setMessage(t("planner.newScheduleMessage"));
        return;
      }
      if (!originStationCode || !destinationStationCode) {
        setFieldError(t("planner.stationSelectionError"));
        setMessage(t("planner.stationFieldsMessage"));
        destinationRef.current?.focus();
        return;
      }
      const route = await addLocalPlannedRoute({ origin, originStationCode, destination, destinationStationCode, travelDate, travelTime, plannedReminderMinutes, checklist });
      if (!route) {
        setFieldError(t("planner.routeFieldsError"));
        setMessage(t("planner.routeFieldsMessage"));
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
      setMessage(t("planner.saved"));
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
      <Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("hub.planJourney")}</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>{t("planner.intro")}</Text>
    <View accessible accessibilityLabel={t("planner.dictationA11y")} style={[styles.dictation, { borderColor: colors.border, backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[styles.dictationHeading, { color: colors.foreground }]}>{t("planner.dictationTitle")}</Text>
      <Text style={[styles.copy, { color: colors.muted }]}>{t("planner.dictationDescription")}</Text>
      <TextInput accessibilityLabel={t("planner.dictationInputA11y")} accessibilityHint={t("planner.dictationInputHint")} value={dictationText} onChangeText={setDictationText} placeholder={t("planner.dictationPlaceholder")} placeholderTextColor={colors.muted} multiline maxLength={240} style={[styles.input, styles.dictationInput, { color: colors.foreground, borderColor: colors.border }]} />
      <Pressable accessibilityRole="button" accessibilityLabel={t("planner.dictationActionA11y")} accessibilityHint={t("planner.dictationActionHint")} onPress={applyDictation} style={({ pressed }) => [styles.secondaryButton, { borderColor: colors.primary, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.secondaryButtonText, { color: colors.primary }]}>{t("planner.dictationAction")}</Text></Pressable>
    </View>
    {favoriteStations.length ? <View accessible accessibilityLabel={t("planner.favoritesA11y")} style={[favoriteStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}><Text style={[favoriteStyles.heading, { color: colors.foreground }]}>{t("planner.favoritesTitle")}</Text><Text style={[favoriteStyles.copy, { color: colors.muted }]}>{t("planner.favoritesDescription")}</Text><View style={favoriteStyles.list}>{favoriteStations.map(station => <Pressable key={station.code} accessibilityRole="button" accessibilityLabel={t("planner.useFavoriteA11y", { station: station.name })} onPress={() => { setOrigin(station.name); setOriginStationCode(station.code); setFieldError(""); acknowledgeNativeAction(); }} style={({ pressed }) => [favoriteStyles.chip, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{station.name}</Text></Pressable>)}</View></View> : null}
    <StationAutocompleteInput label={t("planner.origin")} value={origin} stationCode={originStationCode} description={t("planner.originDescription")} error={fieldError} onChange={text => { setOrigin(text); setOriginStationCode(null); setFieldError(""); }} onSelect={station => { setOrigin(station.name); setOriginStationCode(station.code); setFieldError(""); }} onUseNearestStation={() => void useNearestOrigin()} locatingNearby={locatingNearby} nearbyStatus={nearbyStatus} />
    {originStationCode ? <Pressable accessibilityRole="button" accessibilityLabel={favoriteStations.some(item => item.code === originStationCode) ? t("planner.favoriteRemoveA11y") : t("planner.favoriteAddA11y")} onPress={() => void toggleOriginFavorite()} style={({ pressed }) => [favoriteStyles.toggle, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{favoriteStations.some(item => item.code === originStationCode) ? t("planner.favoriteRemove") : t("planner.favoriteAdd")}</Text></Pressable> : null}
    <StationAutocompleteInput inputRef={destinationRef} label={t("planner.destination")} value={destination} stationCode={destinationStationCode} description={t("planner.destinationDescription")} error={fieldError} onChange={text => { setDestination(text); setDestinationStationCode(null); setFieldError(""); }} onSelect={station => { setDestination(station.name); setDestinationStationCode(station.code); setFieldError(""); }} />
    <View style={styles.row}>
      {Platform.OS === "web" ? <TextInput accessibilityLabel={t("planner.dateWebA11y")} value={travelDate} onChangeText={setTravelDate} placeholder="AAAA-MM-GG" placeholderTextColor={colors.muted} style={[styles.input, styles.half, { color: colors.foreground, borderColor: colors.border }]} /> : <Pressable accessibilityRole="button" accessibilityLabel={t("planner.dateA11y", { date: formatLocalJourneyDate(travelDate, locale) })} accessibilityHint={t("planner.dateHint")} onPress={() => setPicker("date")} style={({ pressed }) => [styles.pickerButton, styles.half, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.pickerLabel, { color: colors.muted }]}>{t("common.date")}</Text><Text style={[styles.pickerValue, { color: colors.foreground }]}>{formatLocalJourneyDate(travelDate, locale)}</Text></Pressable>}
      {Platform.OS === "web" ? <TextInput accessibilityLabel={t("planner.timeWebA11y")} value={travelTime} onChangeText={setTravelTime} placeholder="HH:MM" placeholderTextColor={colors.muted} keyboardType="numbers-and-punctuation" returnKeyType="done" style={[styles.input, styles.half, { color: colors.foreground, borderColor: colors.border }]} /> : <Pressable accessibilityRole="button" accessibilityLabel={t("planner.timeA11y", { time: travelTime ? formatLocalJourneyTime(travelTime, locale) : t("common.notSelected") })} accessibilityHint={t("planner.timeHint")} onPress={() => setPicker("time")} style={({ pressed }) => [styles.pickerButton, styles.half, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.pickerLabel, { color: colors.muted }]}>{t("common.time")}</Text><Text style={[styles.pickerValue, { color: colors.foreground }]}>{travelTime ? formatLocalJourneyTime(travelTime, locale) : t("common.select")}</Text></Pressable>}
    </View>
    {picker ? <DateTimePicker value={fromLocalDateAndTime(travelDate, travelTime)} mode={picker} is24Hour display={Platform.OS === "ios" ? "spinner" : "default"} onChange={onDateTimeChange} /> : null}
    <View style={[templateStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}> 
      <Text accessibilityRole="header" style={[templateStyles.heading, { color: colors.foreground }]}>{t("planner.templatesTitle")}</Text>
      <Text style={[templateStyles.copy, { color: colors.muted }]}>{t("planner.templatesDescription")}</Text>
      <View style={templateStyles.row}><TextInput accessibilityLabel={t("planner.templateNameA11y")} value={templateLabel} onChangeText={setTemplateLabel} placeholder={t("planner.templatePlaceholder")} placeholderTextColor={colors.muted} maxLength={80} style={[styles.input, templateStyles.input, { color: colors.foreground, borderColor: colors.border }]} /><Pressable accessibilityRole="button" accessibilityLabel={t("planner.templateSaveA11y")} onPress={() => void saveTemplate()} style={({ pressed }) => [templateStyles.saveButton, { borderColor: colors.primary, opacity: pressed ? 0.7 : 1 }]}><Text style={[templateStyles.saveButtonText, { color: colors.primary }]}>{t("common.save")}</Text></Pressable></View>
      {templates.length === 0 ? <Text style={[templateStyles.copy, { color: colors.muted }]}>{t("planner.templatesEmpty")}</Text> : templates.map(template => <View key={template.id} style={[templateStyles.item, { borderColor: colors.border }]}><View><Text style={[templateStyles.name, { color: colors.foreground }]}>{template.label}</Text><Text style={[templateStyles.copy, { color: colors.muted }]}>{template.origin} → {template.destination} · {template.travelTime}</Text></View><View style={templateStyles.actions}><Pressable accessibilityRole="button" accessibilityLabel={t("planner.templateApplyA11y", { label: template.label })} onPress={() => applyTemplate(template)} style={({ pressed }) => [templateStyles.action, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.apply")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("planner.templateDeleteA11y", { label: template.label })} onPress={() => void deleteTemplate(template.id)} style={({ pressed }) => [templateStyles.action, { borderColor: colors.danger, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.danger, fontWeight: "800" }}>{t("common.delete")}</Text></Pressable></View></View>)}
    </View>
    <View accessible accessibilityLabel={t("planner.preparationA11y")} style={[checklistStyles.card, { borderColor: colors.border, backgroundColor: colors.background }]}>
      <Text accessibilityRole="header" style={[checklistStyles.heading, { color: colors.foreground }]}>{t("planner.preparationTitle")}</Text>
      <Text style={[checklistStyles.copy, { color: colors.muted }]}>{t("planner.preparationDescription")}</Text>
      <View style={checklistStyles.actions}>{localJourneyChecklistPresets.map(preset => { const label = t(preset.labelKey); return <Pressable key={preset.id} accessibilityRole="button" accessibilityLabel={t("planner.preparationApplyA11y", { label })} onPress={() => { setChecklist(applyLocalJourneyChecklistPreset({ checklist }, preset.id)); setMessage(t("planner.preparationApplied", { label })); acknowledgeNativeAction(); }} style={({ pressed }) => [checklistStyles.action, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{label}</Text></Pressable>; })}</View>
    </View>
      <View accessible accessibilityRole="radiogroup" accessibilityLabel={t("hub.manageReminders")} style={[styles.reminder, { borderColor: colors.border }]}>
      <Text accessibilityRole="header" style={[styles.reminderHeading, { color: colors.foreground }]}>{t("planner.reminderTitle")}</Text>
      <Text style={[styles.reminderCopy, { color: colors.muted }]}>{t("planner.reminderDescription")}</Text>
      <View style={styles.reminderOptions}>{reminderOptions.map(option => <Pressable key={String(option)} accessibilityRole="radio" accessibilityState={{ checked: plannedReminderMinutes === option }} accessibilityLabel={t("planner.reminderOptionA11y", { option: reminderLabel(option, locale) })} onPress={() => setPlannedReminderMinutes(option)} style={({ pressed }) => [styles.reminderOption, { borderColor: plannedReminderMinutes === option ? colors.primary : colors.border, backgroundColor: plannedReminderMinutes === option ? colors.background : colors.surface, opacity: pressed ? 0.7 : 1 }]}><Text style={[styles.reminderOptionText, { color: plannedReminderMinutes === option ? colors.primary : colors.foreground }]}>{reminderLabel(option, locale)}</Text></Pressable>)}</View>
    </View>
    <Pressable accessibilityRole="button" accessibilityState={{ disabled: saving, busy: saving }} accessibilityLabel={t("planner.saveA11y")} accessibilityHint={t("planner.saveHint")} disabled={saving} onPress={() => void save()} style={({ pressed }) => [styles.button, { backgroundColor: colors.primary, opacity: pressed || saving ? 0.7 : 1 }]}><Text style={styles.buttonText}>{saving ? t("planner.saving") : t("planner.saveRoute")}</Text></Pressable>
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
