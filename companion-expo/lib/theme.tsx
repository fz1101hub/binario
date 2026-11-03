import AsyncStorage from "@react-native-async-storage/async-storage";
import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from "react";
import { useColorScheme } from "react-native";

export type ThemeMode = "system" | "light" | "dark";
type CompanionColors = { primary: string; background: string; surface: string; border: string; foreground: string; muted: string; danger: string; warning: string; success: string };
type ThemeContextValue = { colors: CompanionColors; isDark: boolean; mode: ThemeMode; setMode: (mode: ThemeMode) => void; highContrast: boolean; setHighContrast: (value: boolean) => void; reduceMotion: boolean; setReduceMotion: (value: boolean) => void; simpleMode: boolean; setSimpleMode: (value: boolean) => void };

const storageKey = "binario.companion.theme.v1";
const ThemeContext = createContext<ThemeContextValue | null>(null);

function makeColors(dark: boolean, highContrast: boolean): CompanionColors {
  if (dark) return highContrast
    ? { primary: "#7DDBFF", background: "#000000", surface: "#111111", border: "#FFFFFF", foreground: "#FFFFFF", muted: "#E5E7EB", danger: "#FF8A8A", warning: "#FFD166", success: "#8FF0A4" }
    : { primary: "#55C5F1", background: "#101820", surface: "#19242E", border: "#3B4A56", foreground: "#F5F8FA", muted: "#B9C6D1", danger: "#FF9B9B", warning: "#FFD166", success: "#8CE5A7" };
  return highContrast
    ? { primary: "#005A9C", background: "#FFFFFF", surface: "#FFFFFF", border: "#000000", foreground: "#000000", muted: "#1F2937", danger: "#B00020", warning: "#6A4300", success: "#0A5F2A" }
    : { primary: "#005E7A", background: "#F8FAFC", surface: "#FFFFFF", border: "#D6E0E8", foreground: "#15212B", muted: "#52616E", danger: "#B42318", warning: "#9A6700", success: "#16794C" };
}

export function CompanionThemeProvider({ children }: PropsWithChildren) {
  const systemScheme = useColorScheme();
  const [mode, setModeState] = useState<ThemeMode>("system");
  const [highContrast, setHighContrastState] = useState(false);
  const [reduceMotion, setReduceMotionState] = useState(false);
  const [simpleMode, setSimpleModeState] = useState(false);

  useEffect(() => { void AsyncStorage.getItem(storageKey).then(value => {
    try {
      const saved = JSON.parse(value ?? "{}") as Partial<Pick<ThemeContextValue, "mode" | "highContrast" | "reduceMotion" | "simpleMode">>;
      if (saved.mode === "system" || saved.mode === "light" || saved.mode === "dark") setModeState(saved.mode);
      if (typeof saved.highContrast === "boolean") setHighContrastState(saved.highContrast);
      if (typeof saved.reduceMotion === "boolean") setReduceMotionState(saved.reduceMotion);
      if (typeof saved.simpleMode === "boolean") setSimpleModeState(saved.simpleMode);
    } catch { /* Preferenze non valide: usa valori locali sicuri. */ }
  }); }, []);

  const update = (next: Partial<Pick<ThemeContextValue, "mode" | "highContrast" | "reduceMotion" | "simpleMode">>) => {
    const value = { mode, highContrast, reduceMotion, simpleMode, ...next };
    void AsyncStorage.setItem(storageKey, JSON.stringify(value)).catch(() => undefined);
  };
  const setMode = (next: ThemeMode) => { setModeState(next); update({ mode: next }); };
  const setHighContrast = (next: boolean) => { setHighContrastState(next); update({ highContrast: next }); };
  const setReduceMotion = (next: boolean) => { setReduceMotionState(next); update({ reduceMotion: next }); };
  const setSimpleMode = (next: boolean) => { setSimpleModeState(next); update({ simpleMode: next }); };
  const isDark = mode === "dark" || (mode === "system" && systemScheme === "dark");
  const value = useMemo<ThemeContextValue>(() => ({ colors: makeColors(isDark, highContrast), isDark, mode, setMode, highContrast, setHighContrast, reduceMotion, setReduceMotion, simpleMode, setSimpleMode }), [highContrast, isDark, mode, reduceMotion, simpleMode]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useCompanionTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error("CompanionThemeProvider mancante");
  return value;
}
