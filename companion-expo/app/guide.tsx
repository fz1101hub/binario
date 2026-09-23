import { router } from "expo-router";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { ScreenContainer } from "@/components/screen-container";
import { useCompanionTheme } from "@/lib/theme";

const dataStates = [
  { id: "scheduled", label: "Programmato", body: "Orario programmato locale: non è un aggiornamento realtime ferroviario. Deriva soltanto dalla tratta che hai salvato su questo telefono, con la data e l’ora che hai scelto." },
  { id: "realtime", label: "Realtime", body: "Ritardi, binari, cancellazioni e disservizi restano soppressi finché non esisteranno fonte autorizzata, timestamp entro validità e affidabilità verificata. Binario non inventa ritardi, binari, cancellazioni o avvisi." },
  { id: "expired", label: "Scaduto", body: "Pianificazione scaduta: dati non più mostrati. Quando l’orario scelto è passato, la tratta non compare più su telefono, widget o smartwatch finché non la ripianifichi." },
  { id: "unavailable", label: "Non disponibile", body: "Quando un dato realtime non è autorizzato, aggiornato o affidabile, Binario lo sopprime invece di presentarlo come aggiornato. Widget e orologio mostrano “Dati non disponibili in tempo reale”." },
];

export default function GuideScreen() {
  const { colors } = useCompanionTheme();
  return <ScreenContainer><ScrollView contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: colors.foreground }]}>Come leggere i dati</Text>
    <Text style={[styles.copy, { color: colors.muted }]}>Binario distingue sempre ciò che hai pianificato tu da ciò che arriverebbe da una fonte ferroviaria. Questi sono i quattro stati che puoi incontrare.</Text>
    {dataStates.map(state => <View key={state.id} accessible accessibilityLabel={`${state.label}. ${state.body}`} style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>{state.label}</Text><Text style={[styles.copy, { color: colors.muted }]}>{state.body}</Text></View>)}
    <Pressable accessibilityRole="button" accessibilityLabel="Torna alla schermata precedente" onPress={() => { if (router.canGoBack()) router.back(); else router.replace("/"); }} style={({ pressed }) => [styles.action, { borderColor: colors.border, opacity: pressed ? 0.72 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>Indietro</Text></Pressable>
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({ content: { padding: 20, gap: 14, paddingBottom: 34 }, title: { fontSize: 30, fontWeight: "900" }, card: { borderWidth: 1, borderRadius: 18, padding: 17, gap: 6 }, heading: { fontSize: 18, fontWeight: "800" }, copy: { fontSize: 15, lineHeight: 22 }, action: { minHeight: 48, borderWidth: 1, borderRadius: 12, alignItems: "center", justifyContent: "center" } });
