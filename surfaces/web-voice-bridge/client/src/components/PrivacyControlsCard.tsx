import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { trpc } from "@/lib/trpc";
import { createWebDiagnosticReport } from "@shared/diagnosticReport";
import { privacyDataMap } from "@shared/privacyDataMap";
import { Download, FileText, ShieldCheck, Trash2 } from "lucide-react";
import React, { useState } from "react";

function downloadJson(data: unknown) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `binario-dati-personali-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(url);
}

export function PrivacyControlsCard() {
  const utils = trpc.useUtils();
  const exportData = trpc.privacy.exportData.useQuery(undefined, { enabled: false });
  const [confirmation, setConfirmation] = useState("");
  const [status, setStatus] = useState("");
  const deleteData = trpc.privacy.deleteData.useMutation({
    onSuccess: () => {
      setConfirmation("");
      setStatus("Dati personali Binario eliminati. L’account tecnico resta disponibile per un eventuale nuovo utilizzo.");
      void Promise.all([utils.routes.list.invalidate(), utils.stations.listPreferences.invalidate(), utils.companion.listDevices.invalidate(), utils.voice.status.invalidate(), utils.profile.get.invalidate()]);
    },
    onError: () => setStatus("Non è stato possibile eliminare i dati. Nessuna conferma di cancellazione è disponibile: riprova più tardi."),
  });

  async function handleExport() {
    const result = await exportData.refetch();
    if (result.data) {
      downloadJson(result.data);
      setStatus("Esportazione scaricata. Non contiene token, audio, coordinate o dati realtime.");
    } else {
      setStatus("Esportazione non disponibile. I dati non sono stati modificati.");
    }
  }

  function handleDiagnosticDownload() {
    downloadJson(createWebDiagnosticReport());
    setStatus("Diagnostica tecnica scaricata su tua richiesta. Non contiene dati personali o ferroviari.");
  }

  return <Card className="border-slate-200 shadow-sm" aria-labelledby="privacy-heading">
    <CardHeader className="pb-3"><div className="flex items-start justify-between gap-3"><div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.12em] text-blue-700">Controllo dati</p><CardTitle id="privacy-heading">Privacy e archivio</CardTitle><CardDescription className="mt-1">Gestisci solo i dati personali conservati da Binario.</CardDescription></div><ShieldCheck className="h-5 w-5 text-blue-700" /></div></CardHeader>
    <CardContent className="space-y-3">
      <p className="text-xs leading-5 text-muted-foreground">L’esportazione esclude password, token, codici di pairing, audio, posizione, biglietti e dati realtime. La cancellazione revoca anche collegamenti Alexa e dispositivi companion.</p>
      <section aria-labelledby="privacy-data-map-heading" className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 dark:border-slate-800 dark:bg-slate-900/40">
        <h3 id="privacy-data-map-heading" className="text-sm font-semibold text-slate-900 dark:text-slate-100">Dove restano i dati</h3>
        <dl className="mt-3 space-y-3">{privacyDataMap.map(item => <div key={item.id}><dt className="text-xs font-semibold text-slate-900 dark:text-slate-100">{item.title} <span className="font-normal text-muted-foreground">· {item.location}</span></dt><dd className="mt-1 text-xs leading-5 text-muted-foreground">{item.description}</dd></div>)}</dl>
      </section>
      <Button type="button" variant="outline" className="w-full" onClick={() => void handleExport()} disabled={exportData.isFetching}><Download className="mr-2 h-4 w-4" />{exportData.isFetching ? "Preparazione esportazione…" : "Esporta dati Binario"}</Button>
      <Button type="button" variant="outline" className="w-full" onClick={handleDiagnosticDownload}><FileText className="mr-2 h-4 w-4" />Scarica diagnostica tecnica</Button>
      <AlertDialog>
        <AlertDialogTrigger asChild><Button type="button" variant="outline" className="w-full border-red-200 text-red-700 hover:bg-red-50 hover:text-red-800"><Trash2 className="mr-2 h-4 w-4" />Elimina dati Binario</Button></AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader><AlertDialogTitle>Eliminare i dati personali Binario?</AlertDialogTitle><AlertDialogDescription>Verranno eliminati tratte, preferiti, recenti, profilo companion, collegamenti Alexa, dispositivi, codici e token associati. L’account tecnico rimane per consentirti di riutilizzare l’app.</AlertDialogDescription></AlertDialogHeader>
          <div className="space-y-2"><Label htmlFor="privacy-confirmation">Scrivi <strong>ELIMINA</strong> per confermare</Label><Input id="privacy-confirmation" value={confirmation} onChange={event => setConfirmation(event.target.value)} autoComplete="off" /></div>
          <AlertDialogFooter><AlertDialogCancel onClick={() => setConfirmation("")}>Annulla</AlertDialogCancel><AlertDialogAction disabled={confirmation !== "ELIMINA" || deleteData.isPending} onClick={event => { if (confirmation !== "ELIMINA") event.preventDefault(); else deleteData.mutate(); }} className="bg-red-700 hover:bg-red-800">{deleteData.isPending ? "Eliminazione…" : "Elimina definitivamente"}</AlertDialogAction></AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {status ? <p role="status" aria-live="polite" className="rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-700">{status}</p> : null}
    </CardContent>
  </Card>;
}
