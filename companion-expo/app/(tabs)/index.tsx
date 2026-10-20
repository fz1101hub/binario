import { MaterialIcons } from "@expo/vector-icons";
import { AccessibilityInfo, Platform, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ScreenContainer } from "@/components/screen-container";
import { SnapshotCard } from "@/components/snapshot-card";
import { LocalPlanCard } from "@/components/local-plan-card";
import { clampTimerDelay, msUntilSnapshotExpiry } from "@/lib/network-status";
import { syncNativeCompanionSurfaces } from "@/lib/native-surface-bridge";
import { createLocalScheduledSnapshot, createUnavailableSnapshot, type CompanionSnapshot } from "@/lib/companion-snapshot";
import { loadLocalPlannedRoutes } from "@/lib/local-routes";
import { createReplannedLocalRouteDraft, createReturnLocalRouteDraft, localCountdownLabel, remainingLocalJourneyChecklistItems, selectNextLocalPlannedRoute, sortLocalPlannedRoutes, type ControlledLocalRouteDraft, type LocalPlannedRoute } from "@/lib/local-route-contract";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";
import { getCompanionGuidance } from "@/lib/simple-mode";
import { railwayStatusSemantics } from "@/lib/companion-ui-semantics";
import { router, useFocusEffect, type Href } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";

type HomePlanningDraft = { kind: "return" | "replan"; draft: ControlledLocalRouteDraft };

export default function BinarioHome() {
  const { colors, simpleMode } = useCompanionTheme();
  const { locale, t } = useTranslation();
  const defaultSubject = useMemo(() => ({ kind: "route" as const, id: "active-route", label: t("home.noActiveRoute") }), [t]);
  const [snapshot, setSnapshot] = useState<CompanionSnapshot>(createUnavailableSnapshot(defaultSubject, locale));
  const [nextLocalRoute, setNextLocalRoute] = useState<LocalPlannedRoute | null>(null);
  const [localRoutes, setLocalRoutes] = useState<LocalPlannedRoute[]>([]);
  const [planningDraft, setPlanningDraft] = useState<HomePlanningDraft | null>(null);
  const [feedback, setFeedback] = useState("");
  const [expiryCheck, setExpiryCheck] = useState(0);

  const loadLocalPlan = useCallback(async () => {
    const plans = await loadLocalPlannedRoutes();
    setLocalRoutes(plans);
    const next = selectNextLocalPlannedRoute(plans);
    if (!next) {
      setNextLocalRoute(null);
      setSnapshot(createUnavailableSnapshot(defaultSubject, locale));
      return;
    }
    setNextLocalRoute(next);
    setSnapshot(createLocalScheduledSnapshot(next, Date.now(), locale));
    setFeedback(t("home.localPlanLoaded"));
  }, [defaultSubject, locale, t]);

  useFocusEffect(useCallback(() => { void loadLocalPlan(); }, [loadLocalPlan]));
  useEffect(() => { syncNativeCompanionSurfaces(snapshot, nextLocalRoute); }, [nextLocalRoute, snapshot]);
  useEffect(() => { if (feedback && Platform.OS !== "web") AccessibilityInfo.announceForAccessibility(feedback); }, [feedback]);
  useEffect(() => {
    if (snapshot.provenance.dataNature === "unavailable" || !snapshot.provenance.expiresAtUtcMs) return;
    const timer = setTimeout(() => {
      // Un ritardo tagliato da clampTimerDelay scatta prima della scadenza: in tal caso si riprogramma soltanto.
      if (msUntilSnapshotExpiry(snapshot.provenance) > 0) { setExpiryCheck(value => value + 1); return; }
      setSnapshot(createUnavailableSnapshot(snapshot.subject, locale));
      setFeedback(t("home.planExpired"));
    }, clampTimerDelay(msUntilSnapshotExpiry(snapshot.provenance) + 50));
    return () => clearTimeout(timer);
  }, [expiryCheck, locale, snapshot, t]);

  const sourceText = snapshot.provenance.dataNature === "scheduled" ? t("home.sourceScheduled") : t("home.sourceUnavailable");
  const guidance = getCompanionGuidance(simpleMode, locale);
  const upcomingRoutes = useMemo(() => sortLocalPlannedRoutes(localRoutes).filter(route => route.completedAtUtcMs == null).slice(0, 3), [localRoutes]);

  function prepareControlledPlanning(route: LocalPlannedRoute, kind: HomePlanningDraft["kind"]) {
    setPlanningDraft({ kind, draft: kind === "return" ? createReturnLocalRouteDraft(route) : createReplannedLocalRouteDraft(route) });
    setFeedback(kind === "return" ? t("home.returnReady") : t("home.replanReady"));
  }

  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text style={[styles.kicker, { color: colors.primary }]}>BINARIO</Text>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("home.title")}</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>{guidance.intro}</Text>
    <View accessible {...railwayStatusSemantics(snapshot.provenance, locale)} style={[styles.dataStatus, { borderColor: colors.border, backgroundColor: colors.surface }]}><View style={styles.inlineHeading}><MaterialIcons name="info-outline" size={19} color={colors.primary} /><Text accessibilityRole="header" style={[styles.dataStatusTitle, { color: colors.foreground }]}>{t("home.railwayStatus")}</Text></View><Text style={[styles.copy, { color: colors.muted }]}>{t("home.realtimeUnavailable")}</Text></View>
    <LocalPlanCard onSaved={() => void loadLocalPlan()} planningDraft={planningDraft} onPlanningDraftApplied={() => setPlanningDraft(null)} />
    <SnapshotCard snapshot={snapshot} />
    {nextLocalRoute ? <View accessible accessibilityLabel={t("home.readyA11y", { name: nextLocalRoute.name, schedule: localCountdownLabel(nextLocalRoute, Date.now(), locale) ?? t("home.scheduleToCheck"), count: remainingLocalJourneyChecklistItems(nextLocalRoute) })} style={[styles.ready, { borderColor: colors.border, backgroundColor: colors.surface }]}><Text accessibilityRole="header" style={[styles.readyTitle, { color: colors.foreground }]}>{t("home.ready")}</Text><Text style={[styles.readyMain, { color: colors.foreground }]}>{localCountdownLabel(nextLocalRoute, Date.now(), locale) ?? `${nextLocalRoute.travelDate} · ${nextLocalRoute.travelTime}`}</Text><Text style={[styles.copy, { color: colors.muted }]}>{nextLocalRoute.plannedReminderMinutes ? t("home.reminderBefore", { minutes: nextLocalRoute.plannedReminderMinutes }) : t("home.noReminder")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{remainingLocalJourneyChecklistItems(nextLocalRoute) === 0 ? t("home.checklistComplete") : t("home.checklistRemaining", { count: remainingLocalJourneyChecklistItems(nextLocalRoute) })}</Text><View style={styles.readyActions}><Pressable accessibilityRole="button" accessibilityLabel={t("home.openAgendaA11y")} accessibilityHint={t("home.openAgendaHint")} onPress={() => router.navigate("/(tabs)/alerts" as Href)} style={({ pressed }) => [styles.readyAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.openAgenda")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("home.refreshSummaryA11y")} onPress={() => void loadLocalPlan()} style={({ pressed }) => [styles.readyAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("common.update")}</Text></Pressable></View><Text style={[styles.readyNote, { color: colors.muted }]}>{t("home.localOnlyNote")}</Text></View> : null}
    <View accessible accessibilityLabel={t("home.quickActionsA11y")} style={[styles.quickActions, { borderColor: colors.border, backgroundColor: colors.surface }]}><Text accessibilityRole="header" style={[styles.sectionTitle, { color: colors.foreground }]}>{t("home.quickActions")}</Text><Pressable accessibilityRole="button" accessibilityLabel={t("home.agendaA11y")} accessibilityHint={t("home.agendaHint")} onPress={() => router.navigate("/(tabs)/alerts" as Href)} style={({ pressed }) => [styles.quickAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><MaterialIcons name="event-note" size={20} color={colors.primary} /><View style={styles.quickActionText}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.agenda")}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{t("home.agendaDescription")}</Text></View><MaterialIcons name="chevron-right" size={22} color={colors.muted} /></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("home.importA11y")} accessibilityHint={t("home.importHint")} onPress={() => router.navigate("/(tabs)/alerts" as Href)} style={({ pressed }) => [styles.quickAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><MaterialIcons name="file-download" size={20} color={colors.primary} /><View style={styles.quickActionText}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.import")}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{t("home.importDescription")}</Text></View><MaterialIcons name="chevron-right" size={22} color={colors.muted} /></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("home.privacyA11y")} accessibilityHint={t("home.privacyHint")} onPress={() => router.navigate("/(tabs)/settings" as Href)} style={({ pressed }) => [styles.quickAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><MaterialIcons name="shield" size={20} color={colors.primary} /><View style={styles.quickActionText}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.privacy")}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{t("home.privacyDescription")}</Text></View><MaterialIcons name="chevron-right" size={22} color={colors.muted} /></Pressable></View>
    <View accessible accessibilityLabel={t("home.localTrips")} style={[styles.journeyList, { borderColor: colors.border, backgroundColor: colors.surface }]}><View style={styles.journeyHeader}><View><Text accessibilityRole="header" style={[styles.sectionTitle, { color: colors.foreground }]}>{t("home.localTrips")}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{t("home.localTripsDescription")}</Text></View><Pressable accessibilityRole="button" accessibilityLabel={t("home.openAllA11y")} onPress={() => router.navigate("/(tabs)/alerts" as Href)} style={({ pressed }) => [styles.smallAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.openAgendaShort")}</Text></Pressable></View>{upcomingRoutes.length === 0 ? <Text style={[styles.copy, { color: colors.muted }]}>{t("home.noUpcoming")}</Text> : upcomingRoutes.map(route => <View key={route.id} style={[styles.journeyItem, { borderColor: colors.border }]}><Text style={[styles.journeyName, { color: colors.foreground }]}>{route.name}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{localCountdownLabel(route, Date.now(), locale) ?? `${route.travelDate} · ${route.travelTime}`} · {t("home.scheduledNotRealtime")}</Text><View style={styles.journeyActions}><Pressable accessibilityRole="button" accessibilityLabel={t("home.returnA11y", { name: route.name })} accessibilityHint={t("home.returnHint")} onPress={() => prepareControlledPlanning(route, "return")} style={({ pressed }) => [styles.smallAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.return")}</Text></Pressable><Pressable accessibilityRole="button" accessibilityLabel={t("home.replanA11y", { name: route.name })} accessibilityHint={t("home.replanHint")} onPress={() => prepareControlledPlanning(route, "replan")} style={({ pressed }) => [styles.smallAction, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.replan")}</Text></Pressable></View></View>)}</View>
    <Text accessibilityLiveRegion="polite" style={[styles.source, { color: colors.muted }]}>{sourceText}</Text>
    <View accessible accessibilityLabel={guidance.scheduledTitle} style={[styles.notice, { borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.noticeTitle, { color: colors.foreground }]}>{guidance.scheduledTitle}</Text><Text style={[styles.copy, { color: colors.muted }]}>{guidance.scheduledBody}</Text></View>
    {feedback ? <Text accessibilityLiveRegion="polite" style={[styles.feedback, { color: colors.muted, borderColor: colors.border }]}>{feedback}</Text> : null}
    <Pressable accessibilityRole="button" accessibilityLabel={t("home.refreshLocal")} accessibilityHint={t("home.refreshLocalHint")} onPress={() => void loadLocalPlan()} style={({ pressed }) => [styles.secondary, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{t("home.refreshLocal")}</Text></Pressable>
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { padding: 20, gap: 18, paddingBottom: 34 }, kicker: { fontWeight: "800", letterSpacing: 1.1, fontSize: 12 }, title: { fontWeight: "900", fontSize: 30, lineHeight: 36 }, copy: { fontSize: 15, lineHeight: 22 }, inlineHeading: { flexDirection: "row", alignItems: "center", gap: 8 }, dataStatus: { borderWidth: 1, borderRadius: 16, padding: 15, gap: 7 }, dataStatusTitle: { fontSize: 16, fontWeight: "800" }, ready: { borderWidth: 1, borderRadius: 16, padding: 16, gap: 5 }, readyTitle: { fontSize: 16, fontWeight: "800" }, readyMain: { fontSize: 24, fontWeight: "900", lineHeight: 30 }, readyActions: { flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 4 }, readyAction: { minHeight: 44, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, alignItems: "center", justifyContent: "center" }, readyNote: { fontSize: 12, lineHeight: 17 }, quickActions: { borderWidth: 1, borderRadius: 16, padding: 14, gap: 9 }, sectionTitle: { fontSize: 18, fontWeight: "900" }, quickAction: { minHeight: 58, borderWidth: 1, borderRadius: 12, paddingHorizontal: 11, paddingVertical: 8, flexDirection: "row", alignItems: "center", gap: 10 }, quickActionText: { flex: 1, gap: 2 }, actionDescription: { fontSize: 13, lineHeight: 18 }, journeyList: { borderWidth: 1, borderRadius: 16, padding: 14, gap: 10 }, journeyHeader: { flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", gap: 8 }, journeyItem: { borderTopWidth: 1, paddingTop: 10, gap: 4 }, journeyName: { fontSize: 16, lineHeight: 22, fontWeight: "800" }, journeyActions: { flexDirection: "row", flexWrap: "wrap", gap: 7, marginTop: 3 }, smallAction: { minHeight: 40, borderWidth: 1, borderRadius: 10, paddingHorizontal: 10, alignItems: "center", justifyContent: "center" }, source: { fontSize: 13, fontWeight: "700" }, feedback: { borderWidth: 1, borderRadius: 12, padding: 12, fontSize: 14, lineHeight: 20 }, notice: { borderWidth: 1, borderRadius: 16, padding: 16, gap: 6 }, noticeTitle: { fontWeight: "800", fontSize: 16 }, secondary: { minHeight: 48, borderWidth: 1, borderRadius: 12, alignItems: "center", justifyContent: "center" } });
