import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { buildManualGuestTransferArchive, MAX_MANUAL_GUEST_TRANSFER_BYTES, parseManualGuestTransferArchive, previewManualGuestTransfer, type ManualGuestTransferPreview } from "@shared/manualGuestJourneyTransfer";
import { defaultJourneyExtension } from "@shared/localJourneyToolkit";
import type { LocalGuestRoute } from "@shared/localGuestRoutes";
import { Download, FileUp, Share2 } from "lucide-react";
import React, { ChangeEvent, useMemo, useRef, useState } from "react";

const checklistLabels: Record<string, string> = { "time-margin": "Verifica il margine di anticipo", documents: "Prepara titolo di viaggio e documenti", belongings: "Controlla gli effetti personali", connection: "Verifica la coincidenza", bike: "Prepara il necessario per la bici", assistance: "Prepara l’eventuale assistenza già concordata" };

function importRoute(preview: ManualGuestTransferPreview): LocalGuestRoute {
  const { route } = preview;
  return { id: crypto.randomUUID(), origin: route.origin, originStationCode: route.originStationCode, destination: route.destination, destinationStationCode: route.destinationStationCode, travelDate: route.travelDate, travelTime: route.travelTime, ...defaultJourneyExtension(), reminderMinutes: route.plannedReminderMinutes as LocalGuestRoute["reminderMinutes"], checklistTemplateIds: [], checklist: route.checklist.map(item => ({ id: item.id, label: checklistLabels[item.id], completed: item.completed })).filter(item => Boolean(item.label)) };
}

export function LocalJourneyTransferCard({ routes, onImport }: { routes: LocalGuestRoute[]; onImport: (routes: LocalGuestRoute[]) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [status, setStatus] = useState("");
  const [preview, setPreview] = useState<ManualGuestTransferPreview[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const exportable = useMemo(() => routes.filter(route => !route.archivedAt), [routes]);

  function archiveFile() {
    return new File([JSON.stringify(buildManualGuestTransferArchive(exportable), null, 2)], "binario-viaggi-locali.json", { type: "application/json" });
  }
  function download() {
    if (exportable.length === 0) { setStatus("Non ci sono tratte attive da esportare."); return; }
    const file = archiveFile(); const url = URL.createObjectURL(file); const link = document.createElement("a");
    link.href = url; link.download = file.name; link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 0);
    setStatus("Archivio creato sul dispositivo. Controllane il contenuto prima di condividerlo.");
  }
  async function share() {
    if (exportable.length === 0) { setStatus("Non ci sono tratte attive da condividere."); return; }
    const file = archiveFile();
    if (!navigator.share || !navigator.canShare?.({ files: [file] })) { download(); return; }
    try { await navigator.share({ title: "Archivio viaggi Binario", text: "Archivio locale Binario: contiene solo tratte, promemoria e checklist ferroviarie scelti.", files: [file] }); setStatus("Condivisione conclusa."); } catch (error) { if ((error as DOMException).name !== "AbortError") setStatus("Condivisione non riuscita. Puoi creare il file locale."); }
  }
  async function readFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_MANUAL_GUEST_TRANSFER_BYTES) { setStatus("Il file supera 1 MB e non è stato letto."); event.target.value = ""; return; }
    const archive = parseManualGuestTransferArchive(await file.text());
    if (!archive) { setStatus("Il file non è un archivio Binario valido. Nessuna tratta è stata importata."); event.target.value = ""; return; }
    const next = previewManualGuestTransfer(routes, archive.routes); const selectable = next.filter(item => item.state === "new");
    if (selectable.length === 0) { setStatus("Le tratte dell’archivio sono già presenti. Nessun dato è stato modificato."); event.target.value = ""; return; }
    setPreview(next); setSelectedKeys(selectable.map(item => item.key)); setStatus(`${selectable.length} ${selectable.length === 1 ? "tratta pronta" : "tratte pronte"} per l’anteprima. Nulla è stato ancora importato.`);
  }
  function confirmImport() {
    const selected = preview.filter(item => item.state === "new" && selectedKeys.includes(item.key));
    if (selected.length === 0) { setStatus("Seleziona almeno una tratta da importare oppure annulla l’anteprima."); return; }
    onImport(selected.map(importRoute)); setPreview([]); setSelectedKeys([]); if (inputRef.current) inputRef.current.value = "";
    setStatus(`${selected.length} ${selected.length === 1 ? "tratta importata" : "tratte importate"} solo in questo browser.`);
  }
  return <Card className="border-slate-200 shadow-sm"><CardHeader className="pb-3"><CardTitle className="text-base">Trasferisci i viaggi manualmente</CardTitle><CardDescription>Crea o scegli un archivio Binario soltanto quando lo desideri. Non è un backup automatico e non richiede un account.</CardDescription></CardHeader><CardContent className="space-y-3"><p className="text-xs leading-5 text-muted-foreground">Il file contiene solo tratte ferroviarie, codici stazione, data/ora programmati, promemoria e checklist pertinenti. Esclude posizione, audio, token, account, dati realtime e preferenze globali.</p><div className="grid gap-2 sm:grid-cols-2"><Button type="button" variant="outline" onClick={download}><Download className="mr-2 h-4 w-4" />Crea archivio</Button><Button type="button" variant="outline" onClick={() => void share()}><Share2 className="mr-2 h-4 w-4" />Condividi archivio</Button></div><label className="inline-flex min-h-10 cursor-pointer items-center gap-2 rounded-md border border-input bg-background px-3 text-sm font-medium"><FileUp className="h-4 w-4" />Scegli archivio Binario<Input ref={inputRef} type="file" accept="application/json,.json" className="sr-only" aria-label="Scegli archivio viaggi Binario" onChange={event => void readFile(event)} /></label>{preview.length > 0 ? <div className="space-y-2 rounded-xl border border-blue-100 bg-blue-50/60 p-3 dark:border-blue-950 dark:bg-blue-950/20"><p className="text-sm font-medium">Anteprima importazione</p><ul className="space-y-2">{preview.map(item => <li key={item.key} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={selectedKeys.includes(item.key)} disabled={item.state !== "new"} onChange={() => setSelectedKeys(current => current.includes(item.key) ? current.filter(key => key !== item.key) : [...current, item.key])} /><span>{item.route.origin} <span aria-hidden="true">→</span> {item.route.destination} · {item.route.travelDate} · {item.route.travelTime}{item.state === "already_present" ? " · già presente" : ""}</span></li>)}</ul><div className="flex flex-wrap gap-2"><Button type="button" size="sm" onClick={confirmImport}>Importa selezionate</Button><Button type="button" size="sm" variant="outline" onClick={() => { setPreview([]); setSelectedKeys([]); setStatus("Importazione annullata. Nessun dato è stato modificato."); }}>Annulla</Button></div></div> : null}{status ? <p role="status" className="text-sm leading-6 text-blue-900 dark:text-blue-100">{status}</p> : null}</CardContent></Card>;
}
