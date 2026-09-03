import { StyleSheet, Text, View } from "react-native";
import type { CompanionSnapshot } from "@/lib/companion-snapshot";
import { evaluateSnapshot } from "@/lib/companion-snapshot";
import { useCompanionTheme } from "@/lib/theme";

export function SnapshotCard({ snapshot }: { snapshot: CompanionSnapshot }) {
  const { colors } = useCompanionTheme();
  const decision = evaluateSnapshot(snapshot);
  if (decision.action === "suppress") return null;
  return <View accessibilityRole="summary" accessibilityLabel={`${snapshot.title}. ${snapshot.summary}. ${decision.notice}`} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text style={[styles.title, { color: colors.foreground }]}>{snapshot.title}</Text><Text style={{ color: colors.foreground }}>{snapshot.summary}</Text><Text style={{ color: colors.muted }}>Fonte: {snapshot.provenance.sourceLabel}</Text><Text style={[styles.notice, { color: colors.muted }]}>{decision.notice}</Text></View>;
}
const styles = StyleSheet.create({ card: { borderWidth: 1, borderRadius: 16, padding: 15, gap: 5 }, title: { fontSize: 17, fontWeight: "800" }, notice: { fontSize: 13, lineHeight: 18 } });
