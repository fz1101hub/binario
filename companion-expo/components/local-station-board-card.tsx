import { MaterialIcons } from "@expo/vector-icons";
import { useEffect, useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { StationAutocompleteInput } from "@/components/station-autocomplete-input";
import { loadLocalFavoriteStations, saveLocalFavoriteStations, toggleLocalFavoriteStation, type LocalFavoriteStation } from "@/lib/local-favorite-stations";
import { loadLocalRecentStations, recordLocalRecentStation, saveLocalRecentStations, type LocalRecentStation } from "@/lib/local-recent-stations";
import { findCanonicalOfflineStationByCode, type OfflineStation } from "@/lib/offline-station-index";
import { acknowledgeNativeAction, confirmNativeAction } from "@/lib/native-feedback";
import { useCompanionTheme } from "@/lib/theme";

export function LocalStationBoardCard() {
  const { colors } = useCompanionTheme();
  const [query, setQuery] = useState("");
  const [station, setStation] = useState<OfflineStation | null>(null);
  const [favorites, setFavorites] = useState<LocalFavoriteStation[]>([]);
  const [recents, setRecents] = useState<LocalRecentStation[]>([]);
  const [boardOpen, setBoardOpen] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => { void Promise.all([loadLocalFavoriteStations(), loadLocalRecentStations()]).then(([nextFavorites, nextRecents]) => { setFavorites(nextFavorites); setRecents(nextRecents); }); }, []);

  async function selectStation(nextStation: OfflineStation) {
    setStation(nextStation);
    setQuery(nextStation.name);
    setBoardOpen(false);
    const nextRecents = recordLocalRecentStation(recents, { code: nextStation.code, name: nextStation.name });
    await saveLocalRecentStations(nextRecents);
    setRecents(nextRecents);
    setMessage(`${nextStation.name} selezionata. Puoi aprire il tabellone locale.`);
    acknowledgeNativeAction();
  }

  async function toggleFavorite() {
    if (!station) return;
    const nextFavorites = toggleLocalFavoriteStation(favorites, { code: station.code, name: station.name });
    await saveLocalFavoriteStations(nextFavorites);
    setFavorites(nextFavorites);
    const isFavorite = nextFavorites.some(item => item.code === station.code);
    setMessage(isFavorite ? `${station.name} aggiunta alle preferite locali.` : `${station.name} rimossa dalle preferite locali.`);
    acknowledgeNativeAction();
  }

  function openBoard() {
    if (!station) return;
    setBoardOpen(true);
    setMessage(`Tabellone di ${station.name} aperto. I dati live non sono disponibili.`);
    confirmNativeAction();
  }

  const favorite = Boolean(station && favorites.some(item => item.code === station.code));

  return <View accessible accessibilityLabel="Stazioni e tabellone" style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
    <View style={styles.headingRow}><MaterialIcons name="train" size={21} color={colors.primary} /><View style={styles.headingCopy}><Text accessibilityRole="header" style={[styles.heading, { color: colors.foreground }]}>Apri un tabellone</Text><Text style={[styles.copy, { color: colors.muted }]}>Cerca una stazione nel catalogo nazionale offline. Partenze e arrivi live compariranno solo con una fonte autorizzata.</Text></View></View>
    <StationAutocompleteInput label="Stazione" value={query} stationCode={station?.code ?? null} description="Scrivi almeno due lettere e seleziona una stazione per preparare il tabellone." onChange={text => { setQuery(text); setStation(null); setBoardOpen(false); }} onSelect={nextStation => { void selectStation(nextStation); }} />
    {favorites.length ? <View accessible accessibilityLabel="Stazioni preferite locali" style={styles.chipArea}><Text style={[styles.chipLabel, { color: colors.muted }]}>PREFERITE</Text><View style={styles.chips}>{favorites.slice(0, 4).map(item => <Pressable key={item.code} accessibilityRole="button" accessibilityLabel={`Apri il tabellone di ${item.name}`} onPress={() => { const candidate = findCanonicalOfflineStationByCode(item.code); if (candidate) void selectStation(candidate); }} style={({ pressed }) => [styles.chip, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{item.name}</Text></Pressable>)}</View></View> : null}
    {recents.length ? <View accessible accessibilityLabel="Stazioni recenti locali" style={styles.chipArea}><Text style={[styles.chipLabel, { color: colors.muted }]}>RECENTI</Text><View style={styles.chips}>{recents.slice(0, 4).map(item => <Pressable key={item.code} accessibilityRole="button" accessibilityLabel={`Riapri il tabellone di ${item.name}`} onPress={() => { const candidate = findCanonicalOfflineStationByCode(item.code); if (candidate) void selectStation(candidate); }} style={({ pressed }) => [styles.chip, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{item.name}</Text></Pressable>)}</View></View> : null}
    <View style={styles.actions}><Pressable accessibilityRole="button" accessibilityState={{ disabled: !station }} accessibilityLabel={station ? `Apri il tabellone di ${station.name}` : "Seleziona una stazione per aprire il tabellone"} disabled={!station} onPress={openBoard} style={({ pressed }) => [styles.primaryAction, { backgroundColor: colors.primary, opacity: pressed || !station ? 0.64 : 1 }]}><Text style={styles.primaryText}>Apri tabellone</Text></Pressable>{station ? <Pressable accessibilityRole="button" accessibilityLabel={favorite ? "Rimuovi dalle stazioni preferite" : "Aggiungi alle stazioni preferite"} onPress={() => void toggleFavorite()} style={({ pressed }) => [styles.secondaryAction, { borderColor: colors.border, opacity: pressed ? 0.7 : 1 }]}><Text style={{ color: colors.foreground, fontWeight: "800" }}>{favorite ? "Rimuovi preferita" : "Aggiungi preferita"}</Text></Pressable> : null}</View>
    {boardOpen && station ? <View accessible accessibilityLabel={`Tabellone di ${station.name}`} style={[styles.board, { borderColor: colors.border, backgroundColor: colors.background }]}><View style={styles.headingRow}><MaterialIcons name="departure-board" size={20} color={colors.primary} /><View style={styles.headingCopy}><Text accessibilityRole="header" style={[styles.boardTitle, { color: colors.foreground }]}>{station.name}</Text><Text style={[styles.boardCode, { color: colors.muted }]}>{station.code} · Tabellone locale</Text></View></View><Text style={[styles.boardEmptyTitle, { color: colors.foreground }]}>Nessuna partenza o arrivo live disponibile</Text><Text style={[styles.copy, { color: colors.muted }]}>Questo non significa assenza di ritardi o disservizi: non è collegata una fonte ferroviaria autorizzata. Il codice stazione è pronto per un futuro tabellone verificato.</Text></View> : null}
    {message ? <Text accessibilityLiveRegion="polite" style={[styles.message, { color: colors.muted }]}>{message}</Text> : null}
  </View>;
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 18, padding: 16, gap: 11 }, headingRow: { flexDirection: "row", alignItems: "flex-start", gap: 9 }, headingCopy: { flex: 1, gap: 3 }, heading: { fontSize: 18, lineHeight: 24, fontWeight: "800" }, copy: { fontSize: 13, lineHeight: 19 }, chipArea: { gap: 6 }, chipLabel: { fontSize: 11, letterSpacing: 0.8, fontWeight: "900" }, chips: { flexDirection: "row", flexWrap: "wrap", gap: 7 }, chip: { minHeight: 40, borderWidth: 1, borderRadius: 18, paddingHorizontal: 11, justifyContent: "center" }, actions: { flexDirection: "row", flexWrap: "wrap", gap: 8 }, primaryAction: { minHeight: 46, borderRadius: 12, paddingHorizontal: 14, justifyContent: "center", alignItems: "center" }, primaryText: { color: "#fff", fontWeight: "800" }, secondaryAction: { minHeight: 46, borderWidth: 1, borderRadius: 12, paddingHorizontal: 12, justifyContent: "center", alignItems: "center" }, board: { borderWidth: 1, borderRadius: 14, padding: 13, gap: 7 }, boardTitle: { fontSize: 16, lineHeight: 22, fontWeight: "900" }, boardCode: { fontSize: 12, lineHeight: 17, fontWeight: "700" }, boardEmptyTitle: { fontSize: 14, lineHeight: 20, fontWeight: "800" }, message: { fontSize: 13, lineHeight: 19 },
});
