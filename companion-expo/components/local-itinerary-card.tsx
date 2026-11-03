import { useEffect, useMemo, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import type { LocalPlannedRoute } from "@/lib/local-route-contract";
import { addLocalJourneyItinerary, loadLocalJourneyItineraries, removeLocalJourneyItinerary, type SavedLocalJourneyItinerary } from "@/lib/local-itineraries";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";

export function LocalItineraryCard({ routes }: { routes: readonly LocalPlannedRoute[] }) {
  const { colors } = useCompanionTheme();
  const { t } = useTranslation();
  const [itineraries, setItineraries] = useState<SavedLocalJourneyItinerary[]>([]);
  const [message, setMessage] = useState("");
  const available = useMemo(() => new Set(routes.map(route => route.id)), [routes]);
  useEffect(() => { void loadLocalJourneyItineraries().then(items => setItineraries(items.filter(item => item.routeIds.every(id => available.has(id))))); }, [available]);
  const create = async () => { const candidates = routes.filter(route => route.completedAtUtcMs == null).slice(0, 4); if (candidates.length < 2) { setMessage(t("itinerary.needTwo")); return; } const created = await addLocalJourneyItinerary({ label: `${candidates[0].origin} → ${candidates[candidates.length - 1].destination}`, routeIds: candidates.map(route => route.id) }); if (!created) { setMessage(t("itinerary.invalid")); return; } setItineraries(await loadLocalJourneyItineraries()); setMessage(t("itinerary.saved")); };
  const remove = async (id: string) => { await removeLocalJourneyItinerary(id); setItineraries(await loadLocalJourneyItineraries()); setMessage(t("itinerary.deleted")); };
  return <View style={[styles.card, { borderColor: colors.border, backgroundColor: colors.background }]}><Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("itinerary.title")}</Text><Text style={[styles.copy, { color: colors.muted }]}>{t("itinerary.description")}</Text><Pressable accessibilityRole="button" onPress={() => void create()} style={({ pressed }) => [styles.action, { borderColor: colors.primary, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.primary, fontWeight: "800" }}>{t("itinerary.create")}</Text></Pressable>{itineraries.map(item => <View key={item.id} style={[styles.item, { borderColor: colors.border }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{item.label}</Text><Text style={{ color: colors.muted }}>{t("itinerary.legs", { count: item.routeIds.length })}</Text><Pressable accessibilityRole="button" accessibilityLabel={t("itinerary.deleteA11y", { label: item.label })} onPress={() => void remove(item.id)} style={styles.remove}><Text style={{ color: colors.danger, fontWeight: "800" }}>{t("common.delete")}</Text></Pressable></View>)}{message ? <Text accessibilityLiveRegion="polite" style={{ color: colors.muted }}>{message}</Text> : null}</View>;
}
const styles = StyleSheet.create({ card: { borderWidth: 1, borderRadius: 14, padding: 13, gap: 8 }, title: { fontSize: 16, fontWeight: "800" }, copy: { fontSize: 13, lineHeight: 18 }, action: { minHeight: 44, borderWidth: 1, borderRadius: 10, alignItems: "center", justifyContent: "center" }, item: { borderTopWidth: 1, paddingTop: 8, gap: 3 }, remove: { minHeight: 40, alignSelf: "flex-start", justifyContent: "center" } });
