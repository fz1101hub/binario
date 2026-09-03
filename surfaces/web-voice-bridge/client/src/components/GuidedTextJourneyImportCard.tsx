import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { MAX_GUIDED_TEXT_IMPORT_CHARACTERS, parseGuidedTextJourneyImports, type GuidedTextJourneyDraft } from "@shared/localJourneyItineraries";
import type { LocalGuestRoute } from "@shared/localGuestRoutes";
import { ClipboardPaste, FileText, Import } from "lucide-react";
import React, { useMemo, useState } from "react";

function keyForDraft(draft: GuidedTextJourneyDraft) {
  return [draft.origin, draft.destination, draft.travelDate, draft.travelTime].map(value => value.trim().toLocaleLowerCase("it")).join("|");
}

function keyForRoute(route: LocalGuestRoute) {
  return keyForDraft(route);
}

export function GuidedTextJourneyImportCard({ routes, onImport }: { routes: LocalGuestRoute[]; onImport: (drafts: GuidedTextJourneyDraft[]) => void }) {
  const [rawText, setRawText] = useState("");
  const [drafts, setDrafts] = useState<GuidedTextJourneyDraft[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [reviewed, setReviewed] = useState(false);
  const [message, setMessage] = useState("");
  const existingKeys = useMemo(() => new Set(routes.map(keyForRoute)), [routes]);

  function createPreview() {
    const candidates = parseGuidedTextJourneyImports(rawText).filter(draft => !existingKeys.has(keyForDraft(draft)));
    if (candidates.length === 0) {
      setDrafts([]);
      setSelectedKeys([]);
      setReviewed(false);
      setMessage("Non ho trovato nuove tratte complete nel testo. Inserisci una riga per tratta con origine, destinazione, data e ora: nessun dato è stato salvato.");
      return;
    }
    setDrafts(candidates);
    setSelectedKeys(candidates.map(keyForDraft));
    setReviewed(false);
    setMessage(`${candidates.length} ${candidates.length === 1 ? "tratta pronta" : "tratte pronte"} per la revisione. Nulla è stato ancora importato.`);
  }

  async function readClipboard() {
    if (!navigator.clipboard?.readText) { setMessage("La lettura degli appunti non è disponibile qui. Incolla il testo nel campo manualmente."); return; }
    try {
      const value = await navigator.clipboard.readText();
      if (value.length > MAX_GUIDED_TEXT_IMPORT_CHARACTERS) { setMessage(`Il testo negli appunti supera il limite locale di ${MAX_GUIDED_TEXT_IMPORT_CHARACTERS} caratteri e non è stato letto.`); return; }
      setRawText(value);
      setDrafts([]);
      setSelectedKeys([]);
      setReviewed(false);
      setMessage("Testo copiato nel campo per la revisione. Controllalo e crea l’anteprima: non è stato importato nulla.");
    } catch {
      setMessage("Non è stato possibile leggere gli appunti. Incolla il testo nel campo manualmente.");
    }
  }

  function toggle(key: string) { setSelectedKeys(current => current.includes(key) ? current.filter(item => item !== key) : [...current, key]); }

  function confirmImport() {
    const selected = drafts.filter(draft => selectedKeys.includes(keyForDraft(draft)));
    if (selected.length === 0 || !reviewed) { setMessage("Seleziona almeno una tratta e conferma di aver verificato l’anteprima. Nessun dato è stato salvato."); return; }
    onImport(selected);
    setDrafts([]);
    setSelectedKeys([]);
    setReviewed(false);
    setRawText("");
    setMessage(`${selected.length} ${selected.length === 1 ? "tratta importata" : "tratte importate"} solo in questo browser. Puoi aprirle e modificare ogni dato.`);
  }

  return <Card className="border-violet-200 bg-violet-50/40 dark:border-violet-900 dark:bg-violet-950/15" aria-labelledby="guided-text-import-heading">
    <CardHeader className="pb-3"><CardTitle id="guided-text-import-heading" className="text-base">Importa da testo</CardTitle><CardDescription>Incolla il testo che desideri trasformare in tratte locali. Binario accetta solo righe complete, non apre link, non legge messaggi da solo e non salva nulla senza anteprima e conferma.</CardDescription></CardHeader>
    <CardContent className="space-y-3"><Label htmlFor="guided-text-import">Una riga per tratta</Label><textarea id="guided-text-import" value={rawText} onChange={event => { setRawText(event.target.value.slice(0, MAX_GUIDED_TEXT_IMPORT_CHARACTERS)); setDrafts([]); setSelectedKeys([]); setReviewed(false); }} placeholder={"Roma Termini → Bologna Centrale · 12/09/2026 alle 08:30\nBologna Centrale → Milano Centrale · 12/09/2026 alle 11:05"} maxLength={MAX_GUIDED_TEXT_IMPORT_CHARACTERS} className="min-h-28 w-full rounded-md border border-input bg-background p-3 text-sm leading-6 shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-describedby="guided-text-import-help" /><p id="guided-text-import-help" className="text-xs leading-5 text-muted-foreground">Sono richiesti origine, destinazione, data `GG/MM/AAAA` o `AAAA-MM-GG` e ora `HH:MM`. Il testo resta nel campo finché non lo cancelli o confermi l’importazione.</p><div className="flex flex-wrap gap-2"><Button type="button" size="sm" variant="outline" onClick={() => void readClipboard()}><ClipboardPaste className="mr-1.5 h-4 w-4" />Leggi appunti</Button><Button type="button" size="sm" onClick={createPreview} disabled={!rawText.trim()}><FileText className="mr-1.5 h-4 w-4" />Crea anteprima</Button></div>
      {drafts.length > 0 ? <div className="space-y-3 rounded-xl border border-violet-200 bg-background/80 p-3 dark:border-violet-900"><p className="text-sm font-medium">Rivedi le tratte prima di aggiungerle</p><ul className="space-y-2" aria-label="Anteprima tratte da testo">{drafts.map(draft => { const key = keyForDraft(draft); return <li key={key}><label className="flex min-h-10 items-center gap-2 rounded-lg border border-violet-100 px-3 text-sm dark:border-violet-950"><input type="checkbox" checked={selectedKeys.includes(key)} onChange={() => toggle(key)} />{draft.origin} <span aria-hidden="true">→</span><span className="sr-only">verso</span> {draft.destination}<span className="text-xs text-muted-foreground">· {draft.travelDate} · {draft.travelTime}</span></label></li>; })}</ul><label className="flex min-h-10 items-center gap-2 text-sm"><input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} />Ho verificato stazioni, data e ora: sono dati programmati, non realtime.</label><div className="flex flex-wrap gap-2"><Button type="button" size="sm" onClick={confirmImport} disabled={selectedKeys.length === 0 || !reviewed}><Import className="mr-1.5 h-4 w-4" />Importa selezionate</Button><Button type="button" size="sm" variant="outline" onClick={() => { setDrafts([]); setSelectedKeys([]); setReviewed(false); setMessage("Anteprima annullata. Nessun dato è stato modificato."); }}>Annulla</Button></div></div> : null}
      {message ? <p role="status" className="text-sm leading-6 text-violet-900 dark:text-violet-100">{message}</p> : null}
    </CardContent>
  </Card>;
}
