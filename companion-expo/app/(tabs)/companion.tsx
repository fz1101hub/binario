import { MaterialIcons } from "@expo/vector-icons";
import { router, useFocusEffect, type Href } from "expo-router";
import { useCallback, useState } from "react";
import { FlatList, Pressable, StyleSheet, Text, View } from "react-native";
import { ScreenContainer } from "@/components/screen-container";
import { buildCompanionHub, type CompanionHubSurface } from "@/lib/companion-hub";
import { getCompanionHubActions, companionSurfaceSemantics } from "@/lib/companion-ui-semantics";
import { loadLocalPlannedRoutes } from "@/lib/local-routes";
import { useCompanionTheme } from "@/lib/theme";
import { useTranslation } from "@/lib/i18n";


export default function CompanionScreen() {
  const { colors } = useCompanionTheme();
  const { locale, t } = useTranslation();
  const [model, setModel] = useState(() => buildCompanionHub([], locale));
  const actions: Array<{ id: "planJourney" | "manageReminders"; icon: keyof typeof MaterialIcons.glyphMap; label: string; description: string; destination: Href }> = [
    { id: "planJourney", icon: "add-circle-outline", label: t("hub.planJourney"), description: t("hub.planJourneyHint"), destination: "/(tabs)" },
    { id: "manageReminders", icon: "notifications-none", label: t("hub.manageReminders"), description: t("hub.manageRemindersHint"), destination: "/(tabs)/alerts" },
  ];

  useFocusEffect(useCallback(() => {
    let active = true;
    void loadLocalPlannedRoutes().then(routes => { if (active) setModel(buildCompanionHub(routes, locale)); });
    return () => { active = false; };
  }, [locale]));

  function SurfaceCard({ surface }: { surface: CompanionHubSurface }) {
    const icon: keyof typeof MaterialIcons.glyphMap = surface.id === "widget" ? "widgets" : surface.id === "watch" ? "watch" : "record-voice-over";
    return <View accessible {...companionSurfaceSemantics(surface.title, surface.status)} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.nextTitle}><MaterialIcons name={icon} size={20} color={colors.primary} /><Text style={[styles.heading, { color: colors.foreground }]}>{surface.title}</Text></View>
      <Text style={[styles.copy, { color: colors.muted }]}>{surface.description}</Text>
      <Text style={[styles.status, { color: colors.foreground }]}>{surface.status}</Text>
    </View>;
  }

  return <ScreenContainer>
    <FlatList
      data={model.surfaces}
      keyExtractor={item => item.id}
      contentContainerStyle={styles.content}
      ListHeaderComponent={<View style={styles.header}>
        <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>{t("hub.title")}</Text>
        <Text style={[styles.copy, { color: colors.muted }]}>{t("hub.intro")}</Text>
        <View style={[styles.nextCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <View style={styles.nextTitle}><MaterialIcons name="schedule" size={20} color={colors.primary} /><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{t("hub.nextJourney")}</Text></View>
          {model.nextJourney ? <>
            <Text style={[styles.journeyName, { color: colors.foreground }]}>{model.nextJourney.name}</Text>
            <Text style={[styles.copy, { color: colors.muted }]}>{model.nextJourney.schedule}{model.nextJourney.countdown ? ` · ${model.nextJourney.countdown}` : ""}</Text>
            <Text style={[styles.nonRealtime, { color: colors.muted }]}>{t("hub.scheduledStatus")}</Text>
          </> : <Text style={[styles.copy, { color: colors.muted }]}>{t("hub.noJourney")}</Text>}
        </View>
        <View style={styles.actions}>{actions.map(action => {
          const semantics = getCompanionHubActions(locale)[action.id];
          return <Pressable key={action.id} {...semantics} onPress={() => router.navigate(action.destination)} style={({ pressed }) => [styles.action, { borderColor: colors.border, backgroundColor: colors.surface, opacity: pressed ? 0.72 : 1 }]}>
          <MaterialIcons name={action.icon} size={20} color={colors.primary} />
          <View style={styles.actionText}><Text style={[styles.actionLabel, { color: colors.foreground }]}>{action.label}</Text><Text style={[styles.actionDescription, { color: colors.muted }]}>{action.description}</Text></View>
          <MaterialIcons name="chevron-right" size={22} color={colors.muted} />
        </Pressable>;
        })}</View>
        <Text accessibilityRole="header" style={[styles.sectionTitle, { color: colors.foreground }]}>{t("hub.surfaceStatus")}</Text>
      </View>}
      renderItem={({ item }) => <SurfaceCard surface={item} />}
      ItemSeparatorComponent={() => <View style={{ height: 10 }} />}
    />
  </ScreenContainer>;
}

const styles = StyleSheet.create({
  content: { padding: 20, gap: 14, paddingBottom: 36 },
  header: { gap: 14 },
  title: { fontSize: 30, fontWeight: "900" },
  card: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 8 },
  nextCard: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 7 },
  nextTitle: { flexDirection: "row", alignItems: "center", gap: 8 },
  heading: { fontSize: 18, fontWeight: "800" },
  journeyName: { fontSize: 17, fontWeight: "800" },
  copy: { fontSize: 15, lineHeight: 22 },
  nonRealtime: { fontSize: 13, lineHeight: 19, fontWeight: "700" },
  sectionTitle: { fontSize: 18, fontWeight: "900", marginTop: 2 },
  actions: { gap: 9 },
  action: { minHeight: 62, borderWidth: 1, borderRadius: 14, padding: 12, flexDirection: "row", alignItems: "center", gap: 10 },
  actionText: { flex: 1, gap: 2 },
  actionLabel: { fontSize: 15, fontWeight: "800" },
  actionDescription: { fontSize: 13, lineHeight: 18 },
  status: { fontSize: 13, lineHeight: 19, fontWeight: "700" },
});
