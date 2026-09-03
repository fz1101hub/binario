import DashboardLayout from "@/components/DashboardLayout";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { trpc } from "@/lib/trpc";
import { useAuth } from "@/_core/hooks/useAuth";
import { getRailDataStatus } from "@shared/railPresentation";
import { getStationBoardContext } from "@shared/stationCatalog";
import { ArrowLeft, BellRing, Clock3, MapPin, Radio, RefreshCw, Star, TrainFront } from "lucide-react";
import React, { useEffect, useState } from "react";
import { useLocation } from "wouter";

const unavailableRailData = getRailDataStatus({ hasRealtimeSource: false, hasScheduledSource: false });

export function StationBoardDetails({
  stationName,
  stationCode,
  onBack,
  isFavorite = false,
  onFavoriteChange,
}: {
  stationName: string | null;
  stationCode: string | null;
  onBack: () => void;
  isFavorite?: boolean;
  onFavoriteChange?: () => void;
}) {
  const [direction, setDirection] = useState<"departures" | "arrivals">("departures");
  const directionLabel = direction === "departures" ? "Partenze" : "Arrivi";

  return (
    <div className="binario-page mx-auto max-w-4xl space-y-6 pb-12">
      <header className="relative overflow-hidden rounded-[1.75rem] bg-slate-950 px-6 py-7 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8">
        <div className="pointer-events-none absolute inset-y-0 right-0 w-2/5 bg-[linear-gradient(135deg,transparent_10%,rgba(34,211,238,0.13)_10%,transparent_11%,transparent_23%,rgba(59,130,246,0.18)_23%,transparent_24%)]" />
        <div className="relative flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <Button variant="ghost" onClick={onBack} className="mb-4 h-auto p-0 text-cyan-200 hover:bg-transparent hover:text-white"><ArrowLeft className="mr-2 h-4 w-4" />Torna ai viaggi</Button>
            <p className="flex items-center gap-2 text-sm font-medium text-cyan-200"><MapPin className="h-4 w-4" />Stazioni</p>
            <h1 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">{stationName ?? "Tabellone stazione"}</h1>
            <p className="mt-3 max-w-xl text-sm leading-6 text-slate-300">{stationCode ? `Codice stazione ${stationCode}.` : "Seleziona una stazione dalla home per aprire il suo tabellone."} Partenze, arrivi e avvisi indicheranno sempre la fonte e l’orario di aggiornamento.</p>
          </div>
          <div className="flex flex-wrap items-center gap-2"><Badge className="binario-status-badge w-fit border border-amber-300/40 bg-amber-300 text-amber-950 hover:bg-amber-300"><Radio className="mr-1.5 h-3.5 w-3.5" />{unavailableRailData.label}</Badge>{stationName && stationCode && onFavoriteChange ? <Button type="button" variant="outline" size="sm" className="border-slate-500/70 bg-slate-950/40 text-slate-50 hover:bg-slate-800 hover:text-white" onClick={onFavoriteChange} aria-pressed={isFavorite}><Star className={`mr-1.5 h-3.5 w-3.5 ${isFavorite ? "fill-amber-300 text-amber-300" : ""}`} />{isFavorite ? "Preferita" : "Aggiungi ai preferiti"}</Button> : null}</div>
        </div>
      </header>
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="border-b border-slate-100 pb-4">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div><CardTitle>{directionLabel}</CardTitle><CardDescription className="mt-1">Vista operativa del tabellone di stazione.</CardDescription></div>
            <div className="flex rounded-xl border border-slate-200 bg-slate-50 p-1" role="tablist" aria-label="Direzione del tabellone">
              <button type="button" role="tab" aria-selected={direction === "departures"} onClick={() => setDirection("departures")} className={`rounded-lg px-3 py-2 text-sm font-medium transition-colors ${direction === "departures" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600 hover:text-slate-950"}`}><TrainFront className="mr-1.5 inline h-4 w-4" />Partenze</button>
              <button type="button" role="tab" aria-selected={direction === "arrivals"} onClick={() => setDirection("arrivals")} className={`rounded-lg px-3 py-2 text-sm font-medium transition-colors ${direction === "arrivals" ? "bg-white text-blue-700 shadow-sm" : "text-slate-600 hover:text-slate-950"}`}><TrainFront className="mr-1.5 inline h-4 w-4" />Arrivi</button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-5 sm:p-6">
          {stationName ? (
            <div className="rounded-2xl border border-dashed border-amber-200 bg-amber-50/70 p-5">
              <div className="flex items-center gap-3"><div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-amber-100 text-amber-900"><Clock3 className="h-5 w-5" /></div><div><p className="font-semibold text-amber-950">{directionLabel} non ancora disponibili</p><p className="mt-1 text-sm leading-6 text-amber-900">Il tabellone per {stationName} è pronto a ricevere dati. Non vengono mostrati orari, ritardi, binari o disservizi finché non sarà collegata una fonte ferroviaria autorizzata.</p></div></div>
              <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-amber-200 pt-4 text-xs text-amber-900"><span className="flex items-center gap-1.5"><RefreshCw className="h-3.5 w-3.5" />Aggiornamento: non disponibile</span><span className="flex items-center gap-1.5"><BellRing className="h-3.5 w-3.5" />Avvisi: non disponibili</span></div>
            </div>
          ) : (
            <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50 p-6 text-center"><MapPin className="mx-auto h-6 w-6 text-blue-700" /><p className="mt-3 font-semibold text-slate-950">Scegli una stazione per iniziare</p><p className="mx-auto mt-1 max-w-md text-sm leading-6 text-muted-foreground">Torna alla home, cerca una stazione nel catalogo canonico e apri il tabellone dalla sua card dedicata.</p><Button onClick={onBack} className="mt-4 bg-blue-700 hover:bg-blue-800">Cerca una stazione</Button></div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

export default function StationBoard() {
  const [, setLocation] = useLocation();
  const { user } = useAuth();
  const { name: stationName, code: stationCode } = getStationBoardContext(window.location.search);
  const utils = trpc.useUtils();
  const preferences = trpc.stations.listPreferences.useQuery(undefined, { enabled: Boolean(user) });
  const markOpened = trpc.stations.markOpened.useMutation({ onSuccess: () => utils.stations.listPreferences.invalidate() });
  const setFavorite = trpc.stations.setFavorite.useMutation({ onSuccess: () => utils.stations.listPreferences.invalidate() });
  const preference = preferences.data?.find(item => item.stationCode === stationCode);

  useEffect(() => {
    if (user && stationName && stationCode) markOpened.mutate({ stationName, stationCode });
  }, [user, stationName, stationCode]);

  return <DashboardLayout><StationBoardDetails stationName={stationName} stationCode={stationCode} onBack={() => setLocation("/")} isFavorite={preference?.isFavorite ?? false} onFavoriteChange={user && stationName && stationCode ? () => setFavorite.mutate({ stationName, stationCode, isFavorite: !preference?.isFavorite }) : undefined} /></DashboardLayout>;
}
