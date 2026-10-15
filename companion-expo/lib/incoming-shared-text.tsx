import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from "react";
import { DeviceEventEmitter, NativeModules } from "react-native";
import { binarioSharedTextReceiverModuleName } from "@/lib/native-module-names";
import { subscribeIncomingSharedText, type IncomingSharedTextBridge } from "@/lib/shared-text-contract";
type Incoming = { text: string | null; clear: () => void };
const Context = createContext<Incoming | null>(null);
export function IncomingSharedTextProvider({ children }: PropsWithChildren) { const [text, setText] = useState<string | null>(null); useEffect(() => subscribeIncomingSharedText({ bridge: NativeModules[binarioSharedTextReceiverModuleName] as IncomingSharedTextBridge, addListener: (eventName, handler) => DeviceEventEmitter.addListener(eventName, handler), onText: setText }), []); const value = useMemo<Incoming>(() => ({ text, clear: () => setText(null) }), [text]); return <Context.Provider value={value}>{children}</Context.Provider>; }
export function useIncomingSharedText() { const value = useContext(Context); if (!value) throw new Error("IncomingSharedTextProvider mancante"); return value; }
