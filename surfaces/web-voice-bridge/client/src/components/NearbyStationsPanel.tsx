import { Button } from "@/components/ui/button";
import { locateNearbyCanonicalStations, nearbyStationLookupFailure } from "@/lib/nearbyStationLocator";
import type { NearbyStation } from "@shared/nearbyStations";
import type { CanonicalStation } from "@shared/stationCatalog";
import React, { useState } from "react";
import { LocateFixed } from "lucide-react";

type NearbyState = "idle" | "locating" | "ready" | "denied" | "unavailable";

export function NearbyStationsPanel({ onOpenStation }: { onOpenStation: (station: CanonicalStation) => void }) {
  const [nearbyStations, setNearbyStations] = useState<NearbyStation[]>([]);
  const [nearbyState, setNearbyState] = useState<NearbyState>("idle");

  function findNearbyStations() {
    setNearbyState("locating");
    void locateNearbyCanonicalStations().then(stations => { setNearbyStations(stations); setNearbyState("ready"); }).catch(error => setNearbyState(nearbyStationLookupFailure(error)));
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 dark:border-slate-800 dark:bg-slate-950/40">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="flex items-center gap-1.5 text-sm font-semibold text-slate-900 dark:text-foreground"><LocateFixed className="h-4 w-4 text-blue-700" />Stazioni vicine</p>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">La posizione viene letta dal dispositivo solo dopo il tocco e non viene inviata o salvata.</p>
        </div>
        <Button type="button" variant="ghost" size="sm" onClick={findNearbyStations} disabled={nearbyState === "locating"}>{nearbyState === "locating" ? "Cerco…" : "Trova"}</Button>
      </div>
      {nearbyState === "denied" ? <p role="status" className="mt-2 text-xs text-amber-900 dark:text-amber-100">Permesso posizione non disponibile. Puoi continuare a cercare manualmente.</p> : null}
      {nearbyState === "unavailable" ? <p role="status" className="mt-2 text-xs text-amber-900 dark:text-amber-100">Non è possibile calcolare stazioni vicine in questo momento.</p> : null}
      {nearbyState === "ready" && nearbyStations.length === 0 ? <p role="status" className="mt-2 text-xs text-muted-foreground">Nessuna stazione con coordinate disponibili nel catalogo.</p> : null}
      {nearbyStations.length > 0 ? <div className="mt-2 flex flex-wrap gap-1.5">{nearbyStations.map(({ station, distanceKm }) => <Button key={station.code} type="button" variant="outline" size="sm" className="border-slate-200 text-slate-800 hover:bg-white dark:text-foreground" onClick={() => onOpenStation(station)}>{station.name} <span className="ml-1 text-xs text-muted-foreground">{distanceKm.toFixed(1)} km</span></Button>)}</div> : null}
    </div>
  );
}
