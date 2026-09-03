import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY, MAX_ITINERARY_SEGMENTS, createLocalJourneyItinerary, deriveItineraryChangeSummary, parseLocalJourneyItineraries, type LocalJourneyItinerary } from "@shared/localJourneyItineraries";
import type { LocalGuestRoute } from "@shared/localGuestRoutes";
import { ArrowRight, Check, Route, Trash2 } from "lucide-react";
import React, { useEffect, useMemo, useState } from "react";

function readItineraries() { return parseLocalJourneyItineraries(localStorage.getItem(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY)); }

function sameRouteIds(left: readonly string[], right: readonly string[]) { return left.length === right.length && left.every((id, index) => id === right[index]); }

function persistItineraries(next: LocalJourneyItinerary[]) {
  localStorage.setItem(LOCAL_JOURNEY_ITINERARIES_STORAGE_KEY, JSON.stringify(next));
  return next;
}

export function LocalItineraryCard({ routes, minimumTransferMinutes }: { routes: LocalGuestRoute[]; minimumTransferMinutes: number }) {
  const [itineraries, setItineraries] = useState<LocalJourneyItinerary[]>(readItineraries);
  const [selectedRouteIds, setSelectedRouteIds] = useState<string[]>([]);
  const [label, setLabel] = useState("");
  const [message, setMessage] = useState("");
  const activeRoutes = useMemo(() => routes.filter(route => !route.archivedAt).sort((left, right) => `${left.travelDate}T${left.travelTime}`.localeCompare(`${right.travelDate}T${right.travelTime}`)), [routes]);

  useEffect(() => {
    const availableRouteIds = new Set(activeRoutes.map(route => route.id));
    const next = itineraries.map(itinerary => ({ ...itinerary, routeIds: itinerary.routeIds.filter(id => availableRouteIds.has(id)) })).filter(itinerary => itinerary.routeIds.length >= 2);
    if (next.length !== itineraries.length || next.some((itinerary, index) => !sameRouteIds(itinerary.routeIds, itineraries[index]?.routeIds ?? []))) setItineraries(persistItineraries(next));
    setSelectedRouteIds(current => current.filter(id => availableRouteIds.has(id)));
  }, [activeRoutes, itineraries]);

  function toggleRoute(routeId: string) {
    setSelectedRouteIds(current => current.includes(routeId) ? current.filter(id => id !== routeId) : current.length >= MAX_ITINERARY_SEGMENTS ? current : [...current, routeId]);
  }

  function createItinerary() {
    const orderedRouteIds = activeRoutes.filter(route => selectedRouteIds.includes(route.id)).map(route => route.id);
    const itinerary = createLocalJourneyItinerary({ id: crypto.randomUUID(), label, routeIds: orderedRouteIds, createdAt: new Date().toISOString() });
    if (!itinerary) { setMessage("Seleziona da due a quattro tratte programmate per creare un itinerario. Nessun dato è stato modificato."); return; }
    if (itineraries.some(existing => sameRouteIds(existing.routeIds, itinerary.routeIds))) { setMessage("Questo insieme di tratte è già presente in un itinerario locale."); return; }
    setItineraries(persistItineraries([itinerary, ...itineraries].slice(0, 12)));
    setSelectedRouteIds([]);
    setLabel("");
    setMessage("Itinerario creato solo in questo browser. Puoi eliminare il raggruppamento senza eliminare le singole tratte.");
  }

  function removeItinerary(id: string) {
    setItineraries(persistItineraries(itineraries.filter(itinerary => itinerary.id !== id)));
    setMessage("Raggruppamento itinerario eliminato. Le tratte locali restano disponibili.");
  }

  return <Card className="border-cyan-200 bg-cyan-50/40 dark:border-cyan-900 dark:bg-cyan-950/15" aria-labelledby="itinerary-heading">
    <CardHeader className="pb-3"><CardTitle id="itinerary-heading" className="text-base">Itinerari a tappe</CardTitle><CardDescription>Raggruppa da due a quattro tratte già pianificate per consultarle come un unico viaggio. Non calcoliamo coincidenze, binari, ritardi o possibilità di cambio.</CardDescription></CardHeader>
    <CardContent className="space-y-4">
      <div className="rounded-xl border border-cyan-100 bg-background/80 p-3 dark:border-cyan-950">
        <Label htmlFor="itinerary-label" className="text-sm">Nome dell’itinerario <span className="font-normal text-muted-foreground">(facoltativo)</span></Label>
        <Input id="itinerary-label" value={label} onChange={event => setLabel(event.target.value)} placeholder="Es. Roma → Milano con cambio" maxLength={96} className="mt-1.5" />
        {activeRoutes.length < 2 ? <p className="mt-3 text-sm text-muted-foreground">Aggiungi almeno due tratte locali non archiviate per creare un itinerario.</p> : <fieldset className="mt-3"><legend className="text-sm font-medium">Scegli le tappe in ordine cronologico</legend><p className="mt-1 text-xs leading-5 text-muted-foreground">Puoi selezionare al massimo {MAX_ITINERARY_SEGMENTS} tratte. La scelta crea solo un raggruppamento locale.</p><ul className="mt-2 space-y-2" aria-label="Tratte disponibili per l’itinerario">{activeRoutes.map(route => { const selected = selectedRouteIds.includes(route.id); const disabled = !selected && selectedRouteIds.length >= MAX_ITINERARY_SEGMENTS; return <li key={route.id}><label className="flex min-h-10 items-center gap-2 rounded-lg border border-cyan-100 bg-background px-3 text-sm dark:border-cyan-950"><input type="checkbox" checked={selected} disabled={disabled} onChange={() => toggleRoute(route.id)} />{route.origin} <span aria-hidden="true">→</span><span className="sr-only">verso</span> {route.destination}<span className="text-xs text-muted-foreground">· {route.travelDate} · {route.travelTime}</span></label></li>; })}</ul></fieldset>}
        <Button type="button" size="sm" className="mt-3" onClick={createItinerary} disabled={selectedRouteIds.length < 2}>Crea itinerario locale</Button>
      </div>
      {itineraries.length > 0 ? <div className="space-y-3"><h3 className="text-sm font-semibold">Modalità cambio</h3>{itineraries.map(itinerary => { const summary = deriveItineraryChangeSummary(itinerary, activeRoutes); return <article key={itinerary.id} className="rounded-xl border border-cyan-100 bg-background/80 p-3 dark:border-cyan-950"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-semibold">{itinerary.label}</p><p className="mt-1 text-xs text-muted-foreground">{summary.segments.length} tappe locali · programmato, non realtime</p></div><Button type="button" size="sm" variant="ghost" className="text-destructive hover:text-destructive" onClick={() => removeItinerary(itinerary.id)}><Trash2 className="mr-1.5 h-4 w-4" />Elimina gruppo</Button></div>{summary.nextSegment ? <div className="mt-3 space-y-2 rounded-lg border border-cyan-100 bg-cyan-50/60 p-3 text-sm dark:border-cyan-950 dark:bg-cyan-950/30"><p className="font-medium"><Route className="mr-1.5 inline h-4 w-4 text-cyan-700" />Prossima tappa: {summary.nextSegment.origin} <ArrowRight className="mx-1 inline h-4 w-4" aria-hidden="true" /> {summary.nextSegment.destination}</p><p className="text-muted-foreground">{summary.nextSegment.travelDate} · {summary.nextSegment.travelTime}{summary.followingSegment ? <> · poi {summary.followingSegment.origin} <span aria-hidden="true">→</span> {summary.followingSegment.destination}</> : ""}</p><p className="text-xs leading-5 text-muted-foreground"><Check className="mr-1 inline h-3.5 w-3.5" />{summary.remainingChecklistItems === 0 ? "Checklist della prossima tappa completata o non impostata." : `${summary.remainingChecklistItems} voci della checklist della prossima tappa da preparare.`} Il margine personale di {minimumTransferMinutes} minuti è solo un riferimento: Binario non valuta la coincidenza.</p></div> : <p className="mt-3 text-sm text-muted-foreground">Nessuna tappa futura non completata. Il raggruppamento non attesta l’esito del viaggio.</p>}<ol className="mt-3 space-y-1 text-xs text-muted-foreground">{summary.segments.map((segment, index) => <li key={segment.id}>{index + 1}. {segment.origin} → {segment.destination} · {segment.travelDate} · {segment.travelTime}</li>)}</ol></article>; })}</div> : null}
      {message ? <p role="status" className="text-sm leading-6 text-cyan-900 dark:text-cyan-100">{message}</p> : null}
    </CardContent>
  </Card>;
}
