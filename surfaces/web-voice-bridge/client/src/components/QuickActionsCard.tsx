import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Keyboard, MapPin, Mic, Plus, Route } from "lucide-react";
import React, { useEffect, useState } from "react";

const SHORTCUTS_STORAGE_KEY = "binario-keyboard-shortcuts-v1";

export type QuickAction = {
  id: "plan" | "station" | "voice" | "agenda";
  label: string;
  description: string;
  targetId?: string;
  onInvoke?: () => void;
};

const icons = { plan: Plus, station: MapPin, voice: Mic, agenda: Route } as const;
const shortcutKeys: Partial<Record<QuickAction["id"], string>> = { plan: "P", station: "S", voice: "V", agenda: "A" };

function isTypingTarget(target: EventTarget | null) {
  return target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));
}

export function QuickActionsCard({ actions }: { actions: QuickAction[] }) {
  const [keyboardShortcutsEnabled, setKeyboardShortcutsEnabled] = useState(() => localStorage.getItem(SHORTCUTS_STORAGE_KEY) === "enabled");
  const [message, setMessage] = useState("");

  function invoke(action: QuickAction) {
    if (action.onInvoke) {
      action.onInvoke();
      setMessage(`${action.label}: azione aperta.`);
      return;
    }
    const element = action.targetId ? document.getElementById(action.targetId) : null;
    if (!element) {
      setMessage(`${action.label} non è disponibile in questa schermata.`);
      return;
    }
    element.focus();
    if ("scrollIntoView" in element) element.scrollIntoView({ behavior: "smooth", block: "center" });
    setMessage(`${action.label}: focus spostato.`);
  }

  useEffect(() => {
    if (!keyboardShortcutsEnabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (isTypingTarget(event.target) || !event.altKey || !event.shiftKey) return;
      const action = actions.find(candidate => shortcutKeys[candidate.id]?.toLocaleLowerCase("it") === event.key.toLocaleLowerCase("it"));
      if (!action) return;
      event.preventDefault();
      invoke(action);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [actions, keyboardShortcutsEnabled]);

  function toggleKeyboardShortcuts(value: boolean) {
    localStorage.setItem(SHORTCUTS_STORAGE_KEY, value ? "enabled" : "disabled");
    setKeyboardShortcutsEnabled(value);
    setMessage(value ? "Scorciatoie tastiera attivate. Usa Alt+Maiusc più la lettera indicata quando il focus non è in un campo." : "Scorciatoie tastiera disattivate.");
  }

  return <Card className="border-slate-200 shadow-sm" aria-labelledby="quick-actions-heading"><CardHeader className="pb-3"><div className="flex items-start justify-between gap-3"><div><p className="mb-1 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.12em] text-blue-700"><Keyboard className="h-3.5 w-3.5" />Azioni rapide</p><CardTitle id="quick-actions-heading" className="text-base">Vai al punto giusto</CardTitle><CardDescription className="mt-1">Azioni locali per ridurre i passaggi. Le scorciatoie da tastiera sono disattivate finché non le scegli.</CardDescription></div></div></CardHeader><CardContent className="space-y-3"><div className="grid gap-2">{actions.map(action => { const Icon = icons[action.id]; const shortcut = shortcutKeys[action.id]; return <Button key={action.id} type="button" variant="outline" className="min-h-11 justify-start text-left" onClick={() => invoke(action)}><Icon className="mr-2 h-4 w-4 shrink-0 text-blue-700" aria-hidden="true" /><span className="min-w-0 flex-1"><span className="block text-sm font-medium">{action.label}</span><span className="block text-xs font-normal text-muted-foreground">{action.description}</span></span>{shortcut ? <kbd className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">Alt+Maiusc+{shortcut}</kbd> : null}</Button>; })}</div><label className="flex min-h-10 items-start gap-2 rounded-lg bg-slate-50 p-2 text-xs leading-5 text-slate-700 dark:bg-slate-900/40 dark:text-slate-200"><input type="checkbox" className="mt-1" checked={keyboardShortcutsEnabled} onChange={event => toggleKeyboardShortcuts(event.target.checked)} />Attiva scorciatoie tastiera in questa app e browser. Non funzionano mentre scrivi in un campo.</label>{message ? <p role="status" className="text-xs leading-5 text-blue-900 dark:text-blue-100">{message}</p> : null}</CardContent></Card>;
}
