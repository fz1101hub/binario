import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useState } from "react";

export type PreDepartureChecklistItem = { id: string; label: string; done: boolean };

export function parsePreDepartureChecklist(value: string): PreDepartureChecklistItem[] {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every(item => item && typeof item === "object" && typeof (item as PreDepartureChecklistItem).id === "string" && typeof (item as PreDepartureChecklistItem).label === "string" && typeof (item as PreDepartureChecklistItem).done === "boolean") ? parsed as PreDepartureChecklistItem[] : [];
  } catch { return []; }
}

export function PreDepartureChecklistCard({ value, onChange, disabled }: { value: PreDepartureChecklistItem[]; onChange: (items: PreDepartureChecklistItem[]) => void; disabled: boolean }) {
  const [draft, setDraft] = useState("");
  const [notice, setNotice] = useState("");
  function add() {
    const label = draft.trim().replace(/\s+/g, " ");
    if (!label) { setNotice("Scrivi un elemento prima di aggiungerlo."); return; }
    if (value.length >= 8 || label.length > 140) { setNotice("Massimo 8 elementi di 140 caratteri."); return; }
    const id = globalThis.crypto?.randomUUID?.() ?? `item-${Date.now().toString(36)}`;
    onChange([...value, { id, label, done: false }]); setDraft(""); setNotice("");
  }
  return <Card className="border-slate-200 shadow-sm"><CardHeader><CardTitle>Checklist pre-partenza</CardTitle><CardDescription>Promemoria personali da rivedere manualmente. Non è un controllo del servizio ferroviario e non attiva azioni automatiche.</CardDescription></CardHeader><CardContent className="space-y-3"><div className="flex gap-2"><Input value={draft} disabled={disabled || value.length >= 8} onChange={event => setDraft(event.target.value)} maxLength={140} placeholder="Es. verifica gli effetti personali" aria-label="Nuovo elemento checklist" onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); add(); } }} /><Button type="button" variant="outline" disabled={disabled || value.length >= 8} onClick={add}>Aggiungi</Button></div><p className="text-xs text-muted-foreground">{value.length}/8 elementi. Salva la tratta per conservare le modifiche.</p>{value.length === 0 ? <p className="rounded-xl border border-dashed border-slate-200 p-3 text-sm text-muted-foreground">Nessun elemento personale aggiunto.</p> : <ul className="space-y-2" aria-label="Elementi checklist pre-partenza">{value.map(item => <li key={item.id} className="flex items-center gap-3 rounded-xl border border-slate-200 p-3"><input type="checkbox" checked={item.done} disabled={disabled} onChange={() => onChange(value.map(candidate => candidate.id === item.id ? { ...candidate, done: !candidate.done } : candidate))} aria-label={`Segna ${item.label} come ${item.done ? "da fare" : "completato"}`} /><span className={`min-w-0 flex-1 text-sm ${item.done ? "text-muted-foreground line-through" : "text-foreground"}`}>{item.label}</span><Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={() => onChange(value.filter(candidate => candidate.id !== item.id))} aria-label={`Rimuovi ${item.label}`}>Rimuovi</Button></li>)}</ul>}{notice ? <p role="status" className="text-sm text-muted-foreground">{notice}</p> : null}</CardContent></Card>;
}
