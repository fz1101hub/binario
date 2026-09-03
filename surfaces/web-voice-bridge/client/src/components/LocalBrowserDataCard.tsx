import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { clearBrowserJourneyData, inspectBrowserJourneyData, type BrowserJourneyDataSummary } from "@shared/localBrowserJourneyData";
import { Laptop, Trash2 } from "lucide-react";
import React, { useEffect, useState } from "react";

type Props = { onCleared?: () => void };
const emptySummary: BrowserJourneyDataSummary = { localRoutes: 0, localItineraries: 0, hasJourneyPreferences: false };

export function LocalBrowserDataCard({ onCleared }: Props) {
  const [summary, setSummary] = useState<BrowserJourneyDataSummary>(emptySummary);
  const [confirmation, setConfirmation] = useState("");
  const [status, setStatus] = useState("");

  useEffect(() => { setSummary(inspectBrowserJourneyData(window.localStorage)); }, []);

  function clearLocalData() {
    const result = clearBrowserJourneyData(window.localStorage);
    setSummary(emptySummary);
    setConfirmation("");
    onCleared?.();
    setStatus(result.cleared ? `Cancellate ${result.localRoutes} ${result.localRoutes === 1 ? "tratta locale" : "tratte locali"}, ${result.localItineraries} ${result.localItineraries === 1 ? "itinerario" : "itinerari"}${result.hasJourneyPreferences ? " e le preferenze viaggio di questo browser" : ""}. Account, tema e preferenze di accessibilità generali non sono stati modificati.` : "Non sono presenti tratte, itinerari o preferenze viaggio locali da cancellare in questo browser.");
  }

  return <Card className="border-slate-200 shadow-sm" aria-labelledby="local-browser-data-heading">
    <CardHeader className="pb-3"><div className="flex items-start justify-between gap-3"><div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.12em] text-violet-700">Solo questo browser</p><CardTitle id="local-browser-data-heading">Tratte locali sul dispositivo</CardTitle><CardDescription className="mt-1">Gestisci le copie create senza accesso in questo browser corrente.</CardDescription></div><Laptop className="h-5 w-5 text-violet-700" /></div></CardHeader>
    <CardContent className="space-y-3"><div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 text-xs leading-5 text-muted-foreground dark:border-slate-800 dark:bg-slate-900/40"><p><strong className="text-foreground">{summary.localRoutes}</strong> {summary.localRoutes === 1 ? "tratta locale" : "tratte locali"} salvate in questo browser.</p><p className="mt-1"><strong className="text-foreground">{summary.localItineraries}</strong> {summary.localItineraries === 1 ? "itinerario a tappe" : "itinerari a tappe"} locali.</p><p className="mt-1">Preferenze viaggio locali: <strong className="text-foreground">{summary.hasJourneyPreferences ? "presenti" : "non salvate"}</strong>.</p><p className="mt-2">La cancellazione non tocca dati dell’account, dispositivo companion, sincronizzazione, tema, contrasto, testo grande o scorciatoie.</p></div>
      <AlertDialog><AlertDialogTrigger asChild><Button type="button" variant="outline" className="w-full border-red-200 text-red-700 hover:bg-red-50 hover:text-red-800"><Trash2 className="mr-2 h-4 w-4" />Cancella dati viaggio da questo browser</Button></AlertDialogTrigger><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Cancellare le copie locali del browser?</AlertDialogTitle><AlertDialogDescription>Verranno cancellate solo le tratte, gli itinerari a tappe e le preferenze viaggio di questo browser. L’account Binario, i dati su altri dispositivi e le preferenze globali di accessibilità restano invariati.</AlertDialogDescription></AlertDialogHeader><div className="space-y-2"><Label htmlFor="local-browser-clear-confirmation">Scrivi <strong>CANCELLA LOCALE</strong> per confermare</Label><Input id="local-browser-clear-confirmation" value={confirmation} onChange={event => setConfirmation(event.target.value)} autoComplete="off" /></div><AlertDialogFooter><AlertDialogCancel onClick={() => setConfirmation("")}>Annulla</AlertDialogCancel><AlertDialogAction className="bg-red-700 hover:bg-red-800" disabled={confirmation !== "CANCELLA LOCALE"} onClick={event => { if (confirmation !== "CANCELLA LOCALE") event.preventDefault(); else clearLocalData(); }}>Cancella solo locale</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
      {status ? <p role="status" aria-live="polite" className="rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-700 dark:bg-slate-900 dark:text-slate-200">{status}</p> : null}
    </CardContent>
  </Card>;
}
