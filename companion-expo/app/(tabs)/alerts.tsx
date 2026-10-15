import { ScreenContainer } from "@/components/screen-container";
import { LocalItineraryCard } from "@/components/local-itinerary-card";
import { GuidedTextRouteImportCard } from "@/components/guided-text-route-import-card";
import { LocalRoutePriorityCard } from "@/components/local-route-priority-card";
import { useIncomingSharedText } from "@/lib/incoming-shared-text";
import { cancelPlannedRouteReminder, consumeLegacyReminderMigrationNotice, listScheduledRouteIds, schedulePlannedRouteReminder } from "@/lib/planned-reminder-service";
import { addExplicitRecurringLocalPlannedRoutes, addLocalPlannedRoute, isLocalRoutesArchiveFull, loadLocalPlannedRoutes, removeLocalPlannedRoute, setLocalPlannedRouteChecklistItem, setLocalPlannedRouteCompletion, type LocalPlannedRoute } from "@/lib/local-routes";
import { pickLocalJourneyIcs, shareLocalJourneyIcs, type CalendarRouteDraft } from "@/lib/local-calendar";
import { reconcileLocalJourneyItineraries } from "@/lib/local-itineraries";
import { deleteLocalPlannedRoute } from "@/lib/local-journey-privacy";
import { acknowledgeNativeAction, confirmNativeAction } from "@/lib/native-feedback";
import { createReplannedLocalRouteDraft, createReturnLocalRouteDraft, filterLocalJourneyAgenda, getLocalJourneyPreparationPreset, localJourneyChecklistPresets, localJourneyChecklistTranslationKeys, localJourneyWeekdays, maxExplicitLocalJourneyOccurrences, remainingLocalJourneyChecklistItems, type ControlledLocalRouteDraft, type LocalAgendaFilter, type LocalJourneyChecklistPreset, type LocalJourneyWeekday } from "@/lib/local-route-contract";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";
import { deleteRoutePrompt, reminderFeedbackPoliteness } from "@/lib/reminder-accessibility";
import { alertsRailwayStatusSemantics } from "@/lib/companion-ui-semantics";
import { formatLocalJourneyDate, formatLocalJourneySchedule, formatLocalJourneyTime, fromLocalDateAndTime, toLocalIsoDate, toLocalTime } from "@/lib/local-date-time";
import type { GuidedTextLocalRouteDraft } from "@/lib/local-journey-itineraries";
import { useFocusEffect } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { AccessibilityInfo, Alert, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import DateTimePicker, { type DateTimePickerEvent } from "@react-native-community/datetimepicker";

export default function AlertsScreen() {
  const { colors, simpleMode } = useCompanionTheme();
  const { locale, t } = useTranslation();
  const { text: receivedSharedText, clear: clearReceivedSharedText } = useIncomingSharedText();
  const [routes, setRoutes] = useState<LocalPlannedRoute[]>([]);
  const [scheduledIds, setScheduledIds] = useState<number[]>([]);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState("");
  const [agendaFilter, setAgendaFilter] = useState<LocalAgendaFilter>("upcoming");
  const [routeSearch, setRouteSearch] = useState("");
  const [monthFilter, setMonthFilter] = useState<string | null>(null);
  const [preparationFilter, setPreparationFilter] = useState<LocalJourneyChecklistPreset["id"] | null>(null);
  const [pendingDraft, setPendingDraft] = useState<ControlledLocalRouteDraft | null>(null);
  const [pendingKind, setPendingKind] = useState<"return" | "replan" | null>(null);
  const [pendingDate, setPendingDate] = useState("");
  const [pendingTime, setPendingTime] = useState("");
  const [pendingPicker, setPendingPicker] = useState<"date" | "time" | null>(null);
  const [calendarImports, setCalendarImports] = useState<CalendarRouteDraft[]>([]);
  const [calendarImportSelection, setCalendarImportSelection] = useState<string[]>([]);
  const [recurrenceRoute, setRecurrenceRoute] = useState<LocalPlannedRoute | null>(null);
  const [recurrenceWeekdays, setRecurrenceWeekdays] = useState<LocalJourneyWeekday[]>([]);
  const [recurrenceOccurrences, setRecurrenceOccurrences] = useState(4);
  const checklistLabel = (id: keyof typeof localJourneyChecklistTranslationKeys) => t(localJourneyChecklistTranslationKeys[id]);
  const presetLabel = (id: LocalJourneyChecklistPreset["id"]) => t(localJourneyChecklistPresets.find(preset => preset.id === id)!.labelKey);
  const weekdayLabel = (id: LocalJourneyWeekday) => t(localJourneyWeekdays.find(day => day.id === id)!.labelKey);
  async function refresh() {
    setLoading(true);
    try {
      const nextRoutes = await loadLocalPlannedRoutes();
      const nextScheduled = await listScheduledRouteIds();
      const migrationNotice = await consumeLegacyReminderMigrationNotice(locale);
      setRoutes(nextRoutes);
      setScheduledIds(nextScheduled);
      if (migrationNotice) setMessage(migrationNotice);
    } catch {
      setMessage(t("alerts.readError"));
    } finally {
      setLoading(false);
    }
  }
  useFocusEffect(useCallback(() => { void refresh(); }, [locale]));
  useEffect(() => { if (message && Platform.OS !== "web") AccessibilityInfo.announceForAccessibility(message); }, [message]);
  async function toggle(route: LocalPlannedRoute) {
    try {
      if (scheduledIds.includes(route.id)) {
        await cancelPlannedRouteReminder(route.id);
        setScheduledIds(current => current.filter(id => id !== route.id));
        setMessage(t("alerts.reminderCancelled"));
        acknowledgeNativeAction();
        return;
      }
      const result = await schedulePlannedRouteReminder(route);
      if (result === "scheduled") {
        setScheduledIds(current => [...current, route.id]);
        setMessage(t("alerts.reminderScheduled"));
        confirmNativeAction();
      } else if (result === "permission-denied") {
        setMessage(t("alerts.permissionDenied"));
      } else {
        setMessage(t("alerts.reminderInvalid"));
      }
    } catch {
      setMessage(t("alerts.reminderUpdateError"));
    }
  }
  function confirmDelete(route: LocalPlannedRoute) {
    const prompt = deleteRoutePrompt(route.name, locale);
    Alert.alert(prompt.title, prompt.message, [{ text: prompt.cancel, style: "cancel" }, { text: prompt.confirm, style: "destructive", onPress: () => void deleteLocalPlannedRoute({ routeId: route.id, cancelReminder: cancelPlannedRouteReminder, removeRoute: removeLocalPlannedRoute, listRemainingRouteIds: async () => (await loadLocalPlannedRoutes()).map(item => item.id), reconcileItineraries: reconcileLocalJourneyItineraries }).then(result => { setScheduledIds(current => current.filter(id => id !== route.id)); setMessage(t(result.cancelledReminder ? "alerts.deletedWithReminder" : "alerts.deleted")); return refresh(); }).catch(() => { setMessage(t("alerts.deleteError")); }) }]);
  }
  async function toggleChecklist(route: LocalPlannedRoute, itemId: keyof typeof localJourneyChecklistTranslationKeys, completed: boolean) {
    if (await setLocalPlannedRouteChecklistItem(route.id, itemId, completed)) {
      await refresh();
      setMessage(t(completed ? "alerts.checklistCompleted" : "alerts.checklistReopened"));
    }
  }
  async function importCalendar() {
    const drafts = await pickLocalJourneyIcs();
    if (drafts.length === 0) { setMessage(t("alerts.icsInvalid")); return; }
    setCalendarImports(drafts);
    setCalendarImportSelection(drafts.map(draft => calendarImportKey(draft)));
    setMessage(t("alerts.icsPreviewReady", { count: drafts.length }));
  }
  function calendarImportKey(draft: CalendarRouteDraft) {
    return `${draft.originStationCode}|${draft.destinationStationCode}|${draft.travelDate}|${draft.travelTime}`;
  }
  function toggleCalendarImportSelection(key: string) {
    setCalendarImportSelection(current => current.includes(key) ? current.filter(item => item !== key) : [...current, key]);
  }
  async function confirmCalendarImport() {
    const chosen = calendarImports.filter(draft => calendarImportSelection.includes(calendarImportKey(draft)));
    if (chosen.length === 0) { setMessage(t("alerts.icsSelectOne")); return; }
    let imported = 0;
    for (const draft of chosen) if (await addLocalPlannedRoute(draft)) imported += 1;
    const full = imported < chosen.length && await isLocalRoutesArchiveFull();
    if (!full) { setCalendarImports([]); setCalendarImportSelection([]); }
    await refresh();
    setMessage(full ? `${imported ? `${t("alerts.icsImported", { count: imported })} ` : ""}${t("common.archiveFull")}` : imported ? t("alerts.icsImported", { count: imported }) : t("alerts.icsNoneImported"));
    if (imported) confirmNativeAction();
  }
  async function exportCalendar(route: LocalPlannedRoute) {
    try { const shared = await shareLocalJourneyIcs(route, locale); setMessage(shared ? t("alerts.icsShared") : t("alerts.icsShareUnavailable")); if (shared) acknowledgeNativeAction(); }
    catch { setMessage(t("alerts.icsShareError")); }
  }
  async function importGuidedText(drafts: GuidedTextLocalRouteDraft[]) {
    let imported = 0;
    for (const draft of drafts) if (await addLocalPlannedRoute(draft)) imported += 1;
    if (imported) await refresh();
    return { imported, full: imported < drafts.length && await isLocalRoutesArchiveFull() };
  }
  function openRecurrence(route: LocalPlannedRoute) {
    setRecurrenceRoute(route); setRecurrenceWeekdays([]); setRecurrenceOccurrences(4);
    setMessage(t("alerts.recurrenceOpen"));
  }
  async function createRecurrences() {
    if (!recurrenceRoute || recurrenceWeekdays.length === 0) { setMessage(t("alerts.recurrenceChooseDay")); return; }
    const added = await addExplicitRecurringLocalPlannedRoutes(recurrenceRoute.id, { weekdays: recurrenceWeekdays, occurrences: recurrenceOccurrences });
    setRecurrenceRoute(null); setRecurrenceWeekdays([]);
    await refresh();
    setMessage(added ? t("alerts.recurrenceAdded", { count: added }) : t("alerts.recurrenceNone"));
    if (added) confirmNativeAction();
  }
  async function toggleCompleted(route: LocalPlannedRoute) {
    const completing = route.completedAtUtcMs == null;
    if (!await setLocalPlannedRouteCompletion(route.id, completing)) { setMessage(t("alerts.routeUpdateError")); return; }
    if (completing && scheduledIds.includes(route.id)) {
      await cancelPlannedRouteReminder(route.id);
      setScheduledIds(current => current.filter(id => id !== route.id));
    }
    await refresh();
    setMessage(t(completing ? "alerts.completed" : "alerts.restored"));
    acknowledgeNativeAction();
  }
  function prepareControlledRoute(route: LocalPlannedRoute, kind: "return" | "replan") {
    setPendingDraft(kind === "return" ? createReturnLocalRouteDraft(route) : createReplannedLocalRouteDraft(route));
    setPendingKind(kind);
    setPendingDate("");
    setPendingTime("");
    setMessage(t(kind === "return" ? "alerts.returnPrepared" : "alerts.replanPrepared"));
  }
  function onPendingDateTimeChange(event: DateTimePickerEvent, value?: Date) {
    const picker = pendingPicker;
    if (Platform.OS !== "ios") setPendingPicker(null);
    if (event.type === "dismissed" || !value || !picker) return;
    if (picker === "date") setPendingDate(toLocalIsoDate(value));
    else setPendingTime(toLocalTime(value));
  }
  async function saveControlledRoute() {
    if (!pendingDraft || !pendingDate || !pendingTime) { setMessage(t("alerts.chooseDateTime")); return; }
    const route = await addLocalPlannedRoute({ ...pendingDraft, travelDate: pendingDate, travelTime: pendingTime });
    if (!route) { setMessage(t(await isLocalRoutesArchiveFull() ? "common.archiveFull" : "alerts.duplicateRoute")); return; }
    setPendingDraft(null); setPendingKind(null); setPendingDate(""); setPendingTime("");
    await refresh(); setMessage(t(pendingKind === "return" ? "alerts.returnSaved" : "alerts.replanSaved")); confirmNativeAction();
  }
  const displayedRoutes = filterLocalJourneyAgenda(routes, agendaFilter);
  const visibleRoutes = useMemo(() => {
    const query = routeSearch.trim().toLocaleLowerCase(locale);
    return displayedRoutes.filter(route => (!query || [route.origin, route.destination, route.travelDate, route.travelTime].some(value => value.toLocaleLowerCase(locale).includes(query))) && (!monthFilter || route.travelDate.slice(0, 7) === monthFilter) && (!preparationFilter || getLocalJourneyPreparationPreset(route) === preparationFilter));
  }, [displayedRoutes, monthFilter, preparationFilter, routeSearch]);
  const availableMonths = useMemo(() => Array.from(new Set(displayedRoutes.map(route => route.travelDate.slice(0, 7)))).filter(month => /^\d{4}-\d{2}$/.test(month)).sort().reverse(), [displayedRoutes]);
  const localStats = useMemo(() => {
    const upcoming = filterLocalJourneyAgenda(routes, "upcoming").length;
    const archived = filterLocalJourneyAgenda(routes, "archive").length;
    const destinations = routes.reduce<Record<string, number>>((counts, route) => ({ ...counts, [route.destination]: (counts[route.destination] ?? 0) + 1 }), {});
    const frequentDestination = Object.entries(destinations).sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0], locale))[0]?.[0] ?? null;
    return { upcoming, archived, frequentDestination };
  }, [routes]);
  const filters: Array<{ id: LocalAgendaFilter; label: string }> = [{ id: "upcoming", label: t("alerts.filterUpcoming") }, { id: "week", label: t("alerts.filterWeek") }, { id: "archive", label: t("alerts.filterArchive") }];
  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("alerts.title")}</Text>
    <View accessible {...alertsRailwayStatusSemantics(simpleMode, locale)} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("home.railwayStatus")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t(simpleMode ? "alerts.statusSimple" : "alerts.statusFull")}</Text></View>
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><View style={styles.cardHeader}><View style={styles.flex}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("alerts.agenda")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t(simpleMode ? "alerts.agendaSimple" : "alerts.agendaFull")}</Text></View><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.refreshA11y")} accessibilityHint={t("alerts.refreshHint")} onPress={() => void refresh()} style={({ pressed }) => [styles.refresh, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.update")}</Text></Pressable></View>
      <Pressable accessibilityRole="button" accessibilityLabel={t("alerts.importCalendarA11y")} accessibilityHint={t("alerts.importCalendarHint")} onPress={() => void importCalendar()} style={({ pressed }) => [styles.calendarAction, { borderColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.primary, fontWeight: "800" }}>{t("alerts.importCalendar")}</Text><Text style={{ color: colors.muted }}>ICS</Text></Pressable>
      <GuidedTextRouteImportCard existingKeys={routes.map(route => `${route.origin.toLocaleLowerCase("it")}|${route.destination.toLocaleLowerCase("it")}|${route.travelDate}|${route.travelTime}`)} onConfirmImport={importGuidedText} receivedText={receivedSharedText} onReceivedTextAccepted={clearReceivedSharedText} />
      {calendarImports.length > 0 ? <View style={[styles.pending, { borderColor: colors.primary, backgroundColor: colors.background }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("alerts.calendarPreview")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("alerts.calendarPreviewDescription")}</Text>{calendarImports.map(draft => { const key = calendarImportKey(draft); const selected = calendarImportSelection.includes(key); return <Pressable key={key} accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={t("alerts.calendarChoiceA11y", { selection: t(selected ? "common.selected" : "common.notSelectedFemale"), origin: draft.origin, destination: draft.destination, schedule: formatLocalJourneySchedule(draft.travelDate, draft.travelTime, locale) })} onPress={() => toggleCalendarImportSelection(key)} style={({ pressed }) => [styles.checklistItem, { borderColor: selected ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{selected ? "✓ " : "○ "}{draft.origin} → {draft.destination}<Text style={{ color: colors.muted }}> · {formatLocalJourneySchedule(draft.travelDate, draft.travelTime, locale)}</Text></Text></Pressable>; })}<View style={styles.pendingActions}><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.importSelectedA11y")} onPress={() => void confirmCalendarImport()} style={({ pressed }) => [styles.action, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: "#fff", fontWeight: "800" }}>{t("import.selected")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.cancelCalendarA11y")} onPress={() => { setCalendarImports([]); setCalendarImportSelection([]); setMessage(t("alerts.importCancelled")); }} style={({ pressed }) => [styles.delete, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.cancel")}</Text></Pressable></View></View> : null}
      {pendingDraft ? <View accessible accessibilityLabel={t("alerts.completeDraftA11y", { kind: t(pendingKind === "return" ? "alerts.kindReturn" : "alerts.kindReplan") })} style={[styles.pending, { borderColor: colors.primary, backgroundColor: colors.background }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t(pendingKind === "return" ? "alerts.planReturn" : "alerts.replanRoute")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("alerts.chooseNewSchedule", { origin: pendingDraft.origin, destination: pendingDraft.destination })}</Text><View style={styles.pendingRow}><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.dateA11y", { date: pendingDate ? formatLocalJourneyDate(pendingDate, locale) : t("common.notSelected") })} accessibilityHint={t("planner.dateHint")} onPress={() => setPendingPicker("date")} style={({ pressed }) => [styles.pendingPicker, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.muted }}>{t("common.date")}</Text><Text style={{ color: colors.foreground, fontWeight: "800" }}>{pendingDate ? formatLocalJourneyDate(pendingDate, locale) : t("common.select")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.timeA11y", { time: pendingTime ? formatLocalJourneyTime(pendingTime, locale) : t("common.notSelected") })} accessibilityHint={t("planner.timeHint")} onPress={() => setPendingPicker("time")} style={({ pressed }) => [styles.pendingPicker, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.muted }}>{t("common.time")}</Text><Text style={{ color: colors.foreground, fontWeight: "800" }}>{pendingTime ? formatLocalJourneyTime(pendingTime, locale) : t("common.select")}</Text></Pressable></View>{pendingPicker ? <DateTimePicker value={fromLocalDateAndTime(pendingDate || toLocalIsoDate(new Date()), pendingTime || "09:00")} mode={pendingPicker} is24Hour display={Platform.OS === "ios" ? "spinner" : "default"} onChange={onPendingDateTimeChange} /> : null}<View style={styles.pendingActions}><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.saveNewA11y")} onPress={() => void saveControlledRoute()} style={({ pressed }) => [styles.action, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: "#fff", fontWeight: "800" }}>{t("planner.saveRoute")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.cancelDraftA11y")} onPress={() => { setPendingDraft(null); setPendingKind(null); setPendingPicker(null); setMessage(t("alerts.draftCancelled")); }} style={({ pressed }) => [styles.delete, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.cancel")}</Text></Pressable></View></View> : null}
      <LocalRoutePriorityCard routes={routes} onChanged={() => void refresh()} />
      <LocalItineraryCard routes={routes} />
      {recurrenceRoute ? <View accessible accessibilityLabel={t("alerts.recurrenceA11y", { name: recurrenceRoute.name })} style={[styles.pending, { borderColor: colors.primary, backgroundColor: colors.background }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("alerts.recurrenceTitle")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("alerts.recurrenceDescription", { origin: recurrenceRoute.origin, destination: recurrenceRoute.destination, max: maxExplicitLocalJourneyOccurrences })}</Text><View accessible accessibilityLabel={t("alerts.recurrenceDaysA11y")} style={styles.filters}>{localJourneyWeekdays.map(day => { const selected = recurrenceWeekdays.includes(day.id); return <Pressable key={day.id} accessibilityRole="checkbox" accessibilityState={{ checked: selected }} accessibilityLabel={t("alerts.dayChoiceA11y", { selection: t(selected ? "alerts.selectedMale" : "alerts.notSelectedMale"), day: weekdayLabel(day.id) })} onPress={() => setRecurrenceWeekdays(current => current.includes(day.id) ? current.filter(item => item !== day.id) : [...current, day.id])} style={({ pressed }) => [styles.filter, { borderColor: selected ? colors.primary : colors.border, backgroundColor: selected ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: selected ? "#fff" : colors.foreground, fontWeight: "800" }}>{weekdayLabel(day.id)}</Text></Pressable>; })}</View><View style={styles.recurrenceCount}><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.decreaseCopiesA11y")} accessibilityState={{ disabled: recurrenceOccurrences <= 1 }} disabled={recurrenceOccurrences <= 1} onPress={() => setRecurrenceOccurrences(value => Math.max(1, value - 1))} style={({ pressed }) => [styles.countButton, { borderColor: colors.border, opacity: recurrenceOccurrences <= 1 ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>−</Text></Pressable><Text accessibilityLiveRegion="polite" style={{ color: colors.foreground, fontWeight: "800" }}>{t("alerts.futureCopies", { count: recurrenceOccurrences })}</Text><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.increaseCopiesA11y")} accessibilityState={{ disabled: recurrenceOccurrences >= maxExplicitLocalJourneyOccurrences }} disabled={recurrenceOccurrences >= maxExplicitLocalJourneyOccurrences} onPress={() => setRecurrenceOccurrences(value => Math.min(maxExplicitLocalJourneyOccurrences, value + 1))} style={({ pressed }) => [styles.countButton, { borderColor: colors.border, opacity: recurrenceOccurrences >= maxExplicitLocalJourneyOccurrences ? 0.5 : pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>+</Text></Pressable></View><View style={styles.pendingActions}><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.createCopiesA11y", { count: recurrenceOccurrences })} onPress={() => void createRecurrences()} style={({ pressed }) => [styles.action, { borderColor: colors.primary, backgroundColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: "#fff", fontWeight: "800" }}>{t("alerts.createCopies")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.cancelRecurrenceA11ly")} onPress={() => { setRecurrenceRoute(null); setRecurrenceWeekdays([]); setMessage(t("alerts.recurrenceCancelled")); }} style={({ pressed }) => [styles.delete, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.cancel")}</Text></Pressable></View></View> : null}
      <View accessibilityRole="tablist" accessibilityLabel={t("alerts.filterA11y")} style={styles.filters}>{filters.map(filter => <Pressable key={filter.id} accessibilityRole="tab" accessibilityState={{ selected: agendaFilter === filter.id }} accessibilityLabel={t("alerts.showFilterA11y", { filter: filter.label.toLocaleLowerCase(locale) })} onPress={() => setAgendaFilter(filter.id)} style={({ pressed }) => [styles.filter, { borderColor: agendaFilter === filter.id ? colors.primary : colors.border, backgroundColor: agendaFilter === filter.id ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: agendaFilter === filter.id ? "#fff" : colors.foreground, fontWeight: "800" }}>{filter.label}</Text></Pressable>)}</View>
      {routes.length > 0 ? <><TextInput accessibilityLabel={t("alerts.searchA11y")} accessibilityHint={t("alerts.searchHint")} value={routeSearch} onChangeText={setRouteSearch} placeholder={t("alerts.searchPlaceholder")} placeholderTextColor={colors.muted} style={[styles.search, { color: colors.foreground, borderColor: colors.border }]} />{availableMonths.length > 1 ? <View accessibilityRole="radiogroup" accessibilityLabel={t("alerts.monthFilterA11y")} style={styles.filters}><Pressable accessibilityRole="radio" accessibilityState={{ checked: monthFilter === null }} accessibilityLabel={t("alerts.allMonths")} onPress={() => setMonthFilter(null)} style={({ pressed }) => [styles.filter, { borderColor: monthFilter === null ? colors.primary : colors.border, backgroundColor: monthFilter === null ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: monthFilter === null ? "#fff" : colors.foreground, fontWeight: "800" }}>{t("alerts.allMonths")}</Text></Pressable>{availableMonths.map(month => <Pressable key={month} accessibilityRole="radio" accessibilityState={{ checked: monthFilter === month }} accessibilityLabel={t("alerts.monthA11y", { month: formatLocalJourneyDate(`${month}-01`, locale) })} onPress={() => setMonthFilter(month)} style={({ pressed }) => [styles.filter, { borderColor: monthFilter === month ? colors.primary : colors.border, backgroundColor: monthFilter === month ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: monthFilter === month ? "#fff" : colors.foreground, fontWeight: "800" }}>{formatLocalJourneyDate(`${month}-01`, locale)}</Text></Pressable>)}</View> : null}<View accessibilityRole="radiogroup" accessibilityLabel={t("alerts.preparationFilterA11y")} style={styles.filters}><Pressable accessibilityRole="radio" accessibilityState={{ checked: preparationFilter === null }} accessibilityLabel={t("alerts.allPreparations")} onPress={() => setPreparationFilter(null)} style={({ pressed }) => [styles.filter, { borderColor: preparationFilter === null ? colors.primary : colors.border, backgroundColor: preparationFilter === null ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: preparationFilter === null ? "#fff" : colors.foreground, fontWeight: "800" }}>{t("common.all")}</Text></Pressable>{localJourneyChecklistPresets.map(preset => <Pressable key={preset.id} accessibilityRole="radio" accessibilityState={{ checked: preparationFilter === preset.id }} accessibilityLabel={t("alerts.preparationA11y", { label: presetLabel(preset.id) })} onPress={() => setPreparationFilter(preset.id)} style={({ pressed }) => [styles.filter, { borderColor: preparationFilter === preset.id ? colors.primary : colors.border, backgroundColor: preparationFilter === preset.id ? colors.primary : colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: preparationFilter === preset.id ? "#fff" : colors.foreground, fontWeight: "800" }}>{presetLabel(preset.id)}</Text></Pressable>)}</View><View accessible accessibilityLabel={t("alerts.statsA11y", { upcoming: localStats.upcoming, archived: localStats.archived, destination: localStats.frequentDestination ? t("alerts.frequentDestinationA11y", { destination: localStats.frequentDestination }) : "" })} style={[styles.stats, { borderColor: colors.border, backgroundColor: colors.background }]}><Text style={[styles.statsText, { color: colors.foreground }]}>{t("alerts.stats", { upcoming: localStats.upcoming, archived: localStats.archived })}</Text>{localStats.frequentDestination ? <Text style={[styles.statsMeta, { color: colors.muted }]}>{t("alerts.frequentDestination", { destination: localStats.frequentDestination })}</Text> : null}<Text style={[styles.statsMeta, { color: colors.muted }]}>{t("alerts.statsDisclaimer")}</Text></View></> : null}
      {loading ? <Text accessibilityLiveRegion="polite" style={[styles.copy, { color: colors.muted }]}>{t("alerts.loading")}</Text> : null}
      {!loading && routes.length === 0 ? <Text accessibilityLiveRegion="polite" style={[styles.copy, { color: colors.muted }]}>{t("alerts.noRoutes")}</Text> : null}
      {!loading && routes.length > 0 && visibleRoutes.length === 0 ? <Text accessibilityLiveRegion="polite" style={[styles.copy, { color: colors.muted }]}>{t(routeSearch.trim() || monthFilter || preparationFilter ? "alerts.noSearchResults" : "alerts.noFiltered")}</Text> : null}
      {visibleRoutes.map(route => { const scheduled = scheduledIds.includes(route.id); const completed = route.completedAtUtcMs != null; const lead = route.plannedReminderMinutes ? t("alerts.leadBefore", { minutes: route.plannedReminderMinutes }) : t("alerts.leadAtTime"); const remainingChecklist = remainingLocalJourneyChecklistItems(route); const schedule = formatLocalJourneySchedule(route.travelDate, route.travelTime, locale); return <View key={route.id} accessibilityLabel={t("alerts.routeA11y", { name: route.name, origin: route.origin, destination: route.destination, schedule, state: completed ? t("alerts.completedState") : t("alerts.checklistState", { count: remainingChecklist }), reminder: t(scheduled ? "alerts.reminderActive" : "alerts.reminderInactive") })} style={[styles.route, { borderColor: colors.border }]}><View style={styles.flex}><Text accessibilityRole="header" style={[styles.routeName, { color: colors.foreground }]}>{route.name}</Text><Text style={[styles.meta, { color: colors.muted }]}>{route.origin} → {route.destination}</Text><Text style={[styles.meta, { color: colors.muted }]}>{schedule} · {lead}</Text>{completed ? <Text style={[styles.meta, { color: colors.muted }]}>{t("alerts.completedDisclaimer")}</Text> : <View accessibilityRole="list" accessibilityLabel={t("alerts.checklistA11y", { name: route.name })} style={styles.checklist}>{(route.checklist ?? [{ id: "time-margin", completed: false }, { id: "documents", completed: false }, { id: "belongings", completed: false }]).map(item => <Pressable key={item.id} accessibilityRole="checkbox" accessibilityState={{ checked: item.completed }} accessibilityLabel={checklistLabel(item.id)} onPress={() => void toggleChecklist(route, item.id, !item.completed)} style={({ pressed }) => [styles.checklistItem, { borderColor: item.completed ? colors.primary : colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground }}>{item.completed ? "✓ " : "○ "}{checklistLabel(item.id)}</Text></Pressable>)}</View>}</View><View style={styles.actions}><Pressable accessibilityRole="button" accessibilityLabel={t(completed ? "alerts.restoreA11y" : "alerts.completeA11y", { name: route.name })} accessibilityHint={t("alerts.manualStateHint")} onPress={() => void toggleCompleted(route)} style={({ pressed }) => [styles.action, { borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t(completed ? "alerts.restore" : "alerts.complete")}</Text></Pressable>{!completed ? <Pressable accessibilityRole="button" accessibilityLabel={t("alerts.duplicateA11y", { name: route.name })} accessibilityHint={t("alerts.duplicateHint")} onPress={() => openRecurrence(route)} style={({ pressed }) => [styles.action, { borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("alerts.recurrence")}</Text></Pressable> : null}<Pressable accessibilityRole="button" accessibilityLabel={t("alerts.returnA11y", { name: route.name })} accessibilityHint={t("alerts.returnHint")} onPress={() => prepareControlledRoute(route, "return")} style={({ pressed }) => [styles.action, { borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.return")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.replanA11y", { name: route.name })} accessibilityHint={t("alerts.replanHint")} onPress={() => prepareControlledRoute(route, "replan")} style={({ pressed }) => [styles.action, { borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.replan")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.exportCalendarA11y", { name: route.name })} accessibilityHint={t("alerts.exportCalendarHint")} onPress={() => void exportCalendar(route)} style={({ pressed }) => [styles.calendarAction, { borderColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.primary, fontWeight: "800" }}>{t("alerts.exportCalendar")}</Text><Text style={{ color: colors.muted }}>ICS</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t(scheduled ? "alerts.cancelReminderA11y" : "alerts.scheduleReminderA11y", { name: route.name })} accessibilityHint={t("alerts.reminderHint")} onPress={() => void toggle(route)} style={({ pressed }) => [styles.action, { borderColor: scheduled ? colors.warning : colors.primary, backgroundColor: scheduled ? colors.surface : colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: scheduled ? colors.foreground : "#fff", fontWeight: "800" }}>{t(scheduled ? "common.cancel" : "alerts.schedule")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("alerts.deleteA11y", { name: route.name })} accessibilityHint={t("alerts.deleteHint")} onPress={() => confirmDelete(route)} style={({ pressed }) => [styles.delete, { borderColor: colors.danger, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.danger, fontWeight: "800" }}>{t("common.delete")}</Text></Pressable></View></View>; })}
      {message ? <Text accessibilityLiveRegion={reminderFeedbackPoliteness(message)} style={[styles.feedback, { color: colors.muted }]}>{message}</Text> : null}
    </View>
  </ScrollView></ScreenContainer>;
}
const styles = StyleSheet.create({ content: { padding: 20, gap: 14 }, title: { fontSize: 30, fontWeight: "900", marginBottom: 4 }, card: { borderWidth: 1, borderRadius: 18, padding: 18, gap: 11 }, cardHeader: { flexDirection: "row", gap: 12, alignItems: "flex-start" }, flex: { flex: 1, gap: 4 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 15, lineHeight: 22 }, refresh: { minHeight: 48, minWidth: 48, borderWidth: 1, paddingHorizontal: 11, paddingVertical: 9, borderRadius: 10, alignItems: "center", justifyContent: "center" }, calendarAction: { minHeight: 48, borderWidth: 1, borderRadius: 11, paddingHorizontal: 13, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }, pending: { borderWidth: 1, borderRadius: 14, padding: 12, gap: 9 }, pendingRow: { flexDirection: "row", flexWrap: "wrap", gap: 8 }, pendingPicker: { flexGrow: 1, flexBasis: 140, minHeight: 48, borderWidth: 1, borderRadius: 11, paddingHorizontal: 12, justifyContent: "center", gap: 2 }, pendingActions: { gap: 8 }, filters: { flexDirection: "row", flexWrap: "wrap", gap: 8 }, filter: { minHeight: 44, borderWidth: 1, borderRadius: 20, justifyContent: "center", paddingHorizontal: 13 }, recurrenceCount: { minHeight: 48, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 }, countButton: { minWidth: 48, minHeight: 48, borderWidth: 1, borderRadius: 11, alignItems: "center", justifyContent: "center" }, search: { minHeight: 48, borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, fontSize: 15 }, stats: { borderWidth: 1, borderRadius: 12, padding: 12, gap: 3 }, statsText: { fontSize: 14, fontWeight: "800" }, statsMeta: { fontSize: 12, lineHeight: 17 }, route: { borderTopWidth: 1, paddingTop: 13, gap: 10 }, routeName: { fontSize: 16, fontWeight: "800" }, meta: { fontSize: 13, lineHeight: 18 }, checklist: { gap: 6, marginTop: 5 }, checklistItem: { minHeight: 44, borderWidth: 1, borderRadius: 10, paddingHorizontal: 11, justifyContent: "center" }, actions: { gap: 8 }, action: { minHeight: 48, alignItems: "center", justifyContent: "center", borderRadius: 11, borderWidth: 1 }, delete: { minHeight: 48, alignItems: "center", justifyContent: "center", borderRadius: 11, borderWidth: 1 }, feedback: { fontSize: 14, lineHeight: 20 }, });
