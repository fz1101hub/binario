import React, { createContext, useContext, useEffect, useState } from "react";

type Theme = "light" | "dark";

interface ThemeContextType {
  theme: Theme;
  toggleTheme?: () => void;
  highContrast: boolean;
  toggleHighContrast?: () => void;
  reduceMotion: boolean;
  toggleReduceMotion?: () => void;
  largeText: boolean;
  toggleLargeText?: () => void;
  simpleMode: boolean;
  toggleSimpleMode?: () => void;
  switchable: boolean;
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

interface ThemeProviderProps {
  children: React.ReactNode;
  defaultTheme?: Theme;
  switchable?: boolean;
}

export function ThemeProvider({
  children,
  defaultTheme = "light",
  switchable = false,
}: ThemeProviderProps) {
  const [theme, setTheme] = useState<Theme>(() => {
    if (switchable) {
      const stored = localStorage.getItem("theme");
      return (stored as Theme) || defaultTheme;
    }
    return defaultTheme;
  });
  const [highContrast, setHighContrast] = useState(() => switchable && localStorage.getItem("binario-high-contrast") === "true");
  const [reduceMotion, setReduceMotion] = useState(() => switchable && localStorage.getItem("binario-reduce-motion") === "true");
  const [largeText, setLargeText] = useState(() => switchable && localStorage.getItem("binario-large-text") === "true");
  const [simpleMode, setSimpleMode] = useState(() => switchable && localStorage.getItem("binario-simple-mode") === "true");

  useEffect(() => {
    const root = document.documentElement;
    if (theme === "dark") {
      root.classList.add("dark");
    } else {
      root.classList.remove("dark");
    }

    if (switchable) {
      localStorage.setItem("theme", theme);
      localStorage.setItem("binario-high-contrast", String(highContrast));
      localStorage.setItem("binario-reduce-motion", String(reduceMotion));
      localStorage.setItem("binario-large-text", String(largeText));
      localStorage.setItem("binario-simple-mode", String(simpleMode));
    }
    root.classList.toggle("binario-high-contrast", highContrast);
    root.classList.toggle("binario-reduce-motion", reduceMotion);
    root.classList.toggle("binario-large-text", largeText);
    root.classList.toggle("binario-simple-mode", simpleMode);
  }, [theme, switchable, highContrast, reduceMotion, largeText, simpleMode]);

  const toggleTheme = switchable
    ? () => {
        setTheme(prev => (prev === "light" ? "dark" : "light"));
      }
    : undefined;
  const toggleHighContrast = switchable ? () => setHighContrast(value => !value) : undefined;
  const toggleReduceMotion = switchable ? () => setReduceMotion(value => !value) : undefined;
  const toggleLargeText = switchable ? () => setLargeText(value => !value) : undefined;
  const toggleSimpleMode = switchable ? () => setSimpleMode(value => !value) : undefined;

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme, highContrast, toggleHighContrast, reduceMotion, toggleReduceMotion, largeText, toggleLargeText, simpleMode, toggleSimpleMode, switchable }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return context;
}
