import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from "react";
import { NativeModules } from "react-native";
type Incoming = { text: string | null; clear: () => void };
const Context = createContext<Incoming | null>(null);
export function IncomingSharedTextProvider({ children }: PropsWithChildren) { const [text, setText] = useState<string | null>(null); useEffect(() => { const bridge = NativeModules.BinarioSharedTextStore as { consume?: () => Promise<string | null> } | undefined; void bridge?.consume?.().then(value => setText(typeof value === "string" && value.length <= 4_096 ? value : null)).catch(() => undefined); }, []); const value = useMemo<Incoming>(() => ({ text, clear: () => setText(null) }), [text]); return <Context.Provider value={value}>{children}</Context.Provider>; }
export function useIncomingSharedText() { const value = useContext(Context); if (!value) throw new Error("IncomingSharedTextProvider mancante"); return value; }
