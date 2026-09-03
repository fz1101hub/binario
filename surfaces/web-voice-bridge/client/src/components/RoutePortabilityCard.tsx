import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { trpc } from "@/lib/trpc";
import { Download, FileUp, Share2, ShieldCheck } from "lucide-react";
import React, { ChangeEvent, useMemo, useRef, useState } from "react";

type PortableRoute = {
  origin: string; originStationCode?: string | null; destination: string; destinationStationCode?: string | null; name: string; isPinned?: boolean; travelDate?: string | null; travelTime?: string | null; travelProfile?: "commuter" | "occasional" | "accessibility"; plannedReminderMinutes?: number | null; personalNote?: string | null; accessibilityNote?: string | null; personalTags?: string[];
};
type ExportableRoute = PortableRoute & { id: number; archivedAt?: Date | string | null };
const maxImportBytes = 1_000_000;

function parseTags(value: unknown) {
  if (typeof value !== "string") return [];
  try { const parsed: unknown = JSON.parse(value); return Array.isArray(parsed) && parsed.every(tag => typeof tag === "string") ? parsed : []; } catch { return []; }
}

function toPortableRoute(route: ExportableRoute): PortableRoute {
  return { origin: route.origin, originStationCode: route.originStationCode ?? null, destination: route.destination, destinationStationCode: route.destinationStationCode ?? null, name: route.name, isPinned: route.isPinned ?? false, travelDate: route.travelDate ?? null, travelTime: route.travelTime ?? null, travelProfile: route.travelProfile ?? "occasional", plannedReminderMinutes: route.plannedReminderMinutes ?? null, personalNote: route.personalNote ?? null, accessibilityNote: route.accessibilityNote ?? null, personalTags: parseTags((route as ExportableRoute & { personalTagsJson?: string }).personalTagsJson) };
}

function buildFile(routes: ExportableRoute[]) {
  return new File([JSON.stringify({ schemaVersion: 1, formatRevision: 2, exportedAtUtcMs: Date.now(), routes: routes.map(toPortableRoute) }, null, 2)], "binario-tratte-private.json", { type: "application/json" });
}

export function RoutePortabilityCard({ routes }: { routes: ExportableRoute[] }) {
  const utils = trpc.useUtils();
  const inputRef = useRef<HTMLInputElement>(null);
  const [status, setStatus] = useState("");
  const exportable = useMemo(() => routes.filter(route => !route.archivedAt), [routes]);
  const importer = trpc.routes.import.useMutation({ onSuccess: result => { utils.routes.list.invalidate(); setStatus(`${result.imported} ${result.imported === 1 ? "tratta importata" : "tratte importate"}.`); if (inputRef.current) inputRef.current.value = ""; }, onError: () => setStatus("Il file non rispetta il formato Binario; nessuna tratta è stata importata.") });

  function download() {
    if (exportable.length === 0) { setStatus("Non ci sono tratte attive da esportare."); return; }
    const file = buildFile(exportable);
    const url = URL.createObjectURL(file);
    const link = document.createElement("a");
    link.href = url; link.download = file.name; link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    setStatus("File privato creato sul dispositivo. Controllane il contenuto prima di condividerlo.");
  }
  async function share() {
    if (exportable.length === 0) { setStatus("Non ci sono tratte attive da condividere."); return; }
    const file = buildFile(exportable);
    if (!navigator.share || !navigator.canShare?.({ files: [file] })) { download(); return; }
    try { await navigator.share({ title: "Tratte private Binario", text: "File di tratte personali Binario: non contiene token, audio, coordinate o dati realtime.", files: [file] }); setStatus("Condivisione conclusa."); } catch (error) { if ((error as DOMException).name !== "AbortError") setStatus("Condivisione non riuscita; puoi scaricare il file privato."); }
  }
  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > maxImportBytes) { setStatus("Il file supera 1 MB e non è stato letto."); event.target.value = ""; return; }
    try {
      const parsed = JSON.parse(await file.text()) as { schemaVersion?: unknown; routes?: unknown };
      if (parsed.schemaVersion !== 1 || !Array.isArray(parsed.routes) || parsed.routes.length < 1 || parsed.routes.length > 50 || !parsed.routes.every(route => route && typeof route === "object")) throw new Error("Formato non valido");
      importer.mutate({ schemaVersion: 1, routes: parsed.routes as PortableRoute[] });
    } catch { setStatus("Il file non è JSON Binario valido; nessuna tratta è stata importata."); event.target.value = ""; }
  }
  return <Card className="border-slate-200 shadow-sm"><CardHeader className="pb-3"><div className="flex items-start justify-between gap-3"><div><CardTitle>Portabilità privata</CardTitle><CardDescription className="mt-1">Esporta, importa o condividi soltanto le tue tratte attive con un file controllabile.</CardDescription></div><ShieldCheck className="h-5 w-5 text-blue-700" /></div></CardHeader><CardContent className="space-y-3"><p className="text-xs leading-5 text-muted-foreground">Il file esclude dispositivi, token, audio, coordinate, cronologia di apertura e stato ferroviario. Le note personali e i tag restano inclusi solo perché scegli esplicitamente di esportarli.</p><div className="grid gap-2 sm:grid-cols-2"><Button type="button" variant="outline" onClick={download}><Download className="mr-2 h-4 w-4" />Esporta tratte</Button><Button type="button" variant="outline" onClick={() => void share()}><Share2 className="mr-2 h-4 w-4" />Condividi file</Button></div><Input ref={inputRef} type="file" accept="application/json,.json" aria-label="Importa file tratte Binario" onChange={event => void importFile(event)} disabled={importer.isPending} /><p className="text-xs text-muted-foreground">Massimo 50 tratte e 1 MB. Conserva il file solo dove lo ritieni sicuro: l’app non crea backup automatici. L’importazione aggiunge tratte e non sovrascrive l’archivio esistente.</p>{status ? <p role="status" className="rounded-xl border border-blue-100 bg-blue-50 p-3 text-sm text-blue-950">{status}</p> : null}</CardContent></Card>;
}
