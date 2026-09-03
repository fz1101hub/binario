import { Input } from "@/components/ui/input";
import { loadCanonicalStationCatalog } from "@/lib/stationCatalogClient";
import { locateNearbyCanonicalStations, nearbyStationLookupFailure } from "@/lib/nearbyStationLocator";
import { VoiceDictationButton } from "@/components/VoiceDictationButton";
import { searchCanonicalStations, type CanonicalStation } from "@shared/stationCatalog";
import { normalizeDictationText } from "@shared/dictationPresentation";
import { LOCAL_FAVORITE_STATIONS_STORAGE_KEY, favoriteStationToCanonical, parseLocalFavoriteStations, toggleLocalFavoriteStation } from "@shared/localFavoriteStations";
import { CheckCircle2, Loader2, LocateFixed, MapPin, Search, Star } from "lucide-react";
import React, { useMemo, useState } from "react";

type StationAutocompleteInputProps = {
  id: string;
  label: string;
  value: string;
  placeholder: string;
  selectedStation: CanonicalStation | null;
  onChange: (value: string) => void;
  onSelect: (station: CanonicalStation | null) => void;
  required?: boolean;
  description?: string;
  error?: string;
  allowNearbyStation?: boolean;
  favoriteStations?: CanonicalStation[];
  onToggleFavorite?: (station: CanonicalStation) => void;
};

export function StationAutocompleteInput({
  id,
  label,
  value,
  placeholder,
  selectedStation,
  onChange,
  onSelect,
  required = true,
  description,
  error,
  allowNearbyStation = false,
  favoriteStations = [],
  onToggleFavorite,
}: StationAutocompleteInputProps) {
  const [catalog, setCatalog] = useState<CanonicalStation[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [nearbyState, setNearbyState] = useState<"idle" | "locating" | "ready" | "denied" | "unavailable">("idle");
  const [localFavoriteStations, setLocalFavoriteStations] = useState(() => parseLocalFavoriteStations(localStorage.getItem(LOCAL_FAVORITE_STATIONS_STORAGE_KEY)));
  const suggestions = useMemo(() => catalog ? searchCanonicalStations(catalog, value) : [], [catalog, value]);
  const resolvedFavoriteStations = onToggleFavorite ? favoriteStations : localFavoriteStations.map(favoriteStationToCanonical);
  const descriptionId = description ? `${id}-description` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [descriptionId, errorId].filter(Boolean).join(" ") || undefined;

  function activate() {
    setIsOpen(true);
    if (catalog || isLoading || loadError) return;
    setIsLoading(true);
    loadCanonicalStationCatalog()
      .then(setCatalog)
      .catch(() => setLoadError(true))
      .finally(() => setIsLoading(false));
  }

  function closeSuggestions() {
    setIsOpen(false);
    setActiveIndex(-1);
  }

  function selectStation(station: CanonicalStation) {
    onChange(station.name);
    onSelect(station);
    closeSuggestions();
  }

  function applyDictation(transcript: string) {
    const text = normalizeDictationText(transcript);
    if (!text) return;
    onChange(text);
    onSelect(null);
    activate();
  }

  function selectNearbyStation() {
    setNearbyState("locating");
    void locateNearbyCanonicalStations().then(stations => {
      const nearest = stations[0];
      if (!nearest) { setNearbyState("unavailable"); return; }
      selectStation(nearest.station);
      setNearbyState("ready");
    }).catch(error => setNearbyState(nearbyStationLookupFailure(error)));
  }

  function toggleFavorite(station: CanonicalStation) {
    if (onToggleFavorite) { onToggleFavorite(station); return; }
    const next = toggleLocalFavoriteStation(localFavoriteStations, station);
    localStorage.setItem(LOCAL_FAVORITE_STATIONS_STORAGE_KEY, JSON.stringify(next));
    setLocalFavoriteStations(next);
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") { closeSuggestions(); return; }
    if (!suggestions.length) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setIsOpen(true);
      setActiveIndex(current => event.key === "ArrowDown" ? (current >= suggestions.length - 1 ? 0 : current + 1) : (current <= 0 ? suggestions.length - 1 : current - 1));
    }
    if (event.key === "Enter" && isOpen && activeIndex >= 0) {
      event.preventDefault();
      selectStation(suggestions[activeIndex]!);
    }
  }

  return (
    <div className="relative space-y-1.5">
      <label htmlFor={id} className="text-sm font-medium text-slate-600 dark:text-muted-foreground">{label}</label>
      <div className="flex items-center gap-2">
        <MapPin className="h-4 w-4 shrink-0 text-blue-700" aria-hidden="true" />
        <Input
          id={id}
          value={value}
          onFocus={activate}
          onBlur={() => window.setTimeout(closeSuggestions, 150)}
          onChange={event => {
            onChange(event.target.value);
            onSelect(null);
            setIsOpen(true);
            setActiveIndex(-1);
          }}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          maxLength={120}
          autoComplete="off"
          required={required}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={isOpen && suggestions.length > 0}
          aria-controls={`${id}-suggestions`}
          aria-activedescendant={isOpen && activeIndex >= 0 ? `${id}-option-${suggestions[activeIndex]?.stationId}` : undefined}
          aria-describedby={describedBy}
          aria-invalid={Boolean(error)}
        />
        {allowNearbyStation ? <button type="button" onClick={selectNearbyStation} disabled={nearbyState === "locating"} className="inline-flex min-h-11 shrink-0 items-center justify-center rounded-md border border-input px-2.5 text-slate-700 hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring dark:text-foreground" aria-label="Usa la stazione più vicina" aria-describedby={`${id}-nearby-status`}><LocateFixed className="h-4 w-4" aria-hidden="true" /><span className="sr-only">{nearbyState === "locating" ? "Cerco stazione vicina" : "Usa stazione vicina"}</span></button> : null}
        <VoiceDictationButton label={`Detta ${label.toLowerCase()}`} onTranscript={applyDictation} />
      </div>
      {allowNearbyStation ? <p id={`${id}-nearby-status`} role="status" aria-live="polite" className="text-xs leading-5 text-muted-foreground">{nearbyState === "idle" ? "Puoi usare la posizione una sola volta per proporre la stazione più vicina." : null}{nearbyState === "locating" ? "Cerco la stazione più vicina sul dispositivo…" : null}{nearbyState === "ready" && selectedStation ? `Selezionata ${selectedStation.name}. Puoi modificarla manualmente.` : null}{nearbyState === "denied" ? "Permesso posizione non disponibile. Puoi cercare manualmente." : null}{nearbyState === "unavailable" ? "Non è possibile calcolare la stazione vicina ora. Puoi cercare manualmente." : null}</p> : null}
      {description ? <p id={descriptionId} className="text-xs leading-5 text-muted-foreground">{description}</p> : null}
      {error ? <p id={errorId} role="alert" className="text-xs font-medium leading-5 text-red-800 dark:text-red-200">{error}</p> : null}
      {selectedStation ? <p className="flex items-center gap-1.5 text-xs text-emerald-700 dark:text-emerald-300"><CheckCircle2 className="h-3.5 w-3.5" />Codice stazione: {selectedStation.code}</p> : null}
      {selectedStation ? <button type="button" onClick={() => toggleFavorite(selectedStation)} className="inline-flex min-h-9 items-center gap-1.5 rounded-md px-2 text-xs font-medium text-slate-700 hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring dark:text-foreground" aria-pressed={resolvedFavoriteStations.some(station => station.code === selectedStation.code)}><Star className={`h-3.5 w-3.5 ${resolvedFavoriteStations.some(station => station.code === selectedStation.code) ? "fill-current text-amber-500" : ""}`} aria-hidden="true" />{resolvedFavoriteStations.some(station => station.code === selectedStation.code) ? "Rimuovi dai preferiti" : "Salva nei preferiti"}</button> : null}
      {resolvedFavoriteStations.length > 0 ? <div className="flex flex-wrap gap-1.5 pt-1" role="group" aria-label={`Stazioni preferite per ${label.toLowerCase()}`}><span className="self-center text-xs text-muted-foreground">Preferite:</span>{resolvedFavoriteStations.map(station => <button key={station.code} type="button" onClick={() => selectStation(station)} className="min-h-9 rounded-full border border-amber-200 bg-amber-50 px-2.5 text-xs font-medium text-amber-950 hover:bg-amber-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-100"><Star className="mr-1 inline h-3 w-3 fill-current text-amber-500" aria-hidden="true" />{station.name}</button>)}</div> : null}
      {isOpen && value.trim().length >= 2 ? (
        <div id={`${id}-suggestions`} role="listbox" className="absolute z-20 mt-1 w-full overflow-hidden rounded-xl border border-slate-200 bg-white shadow-lg dark:border-border dark:bg-card dark:shadow-black/40">
          {isLoading ? <p className="flex items-center gap-2 px-3 py-3 text-sm text-slate-600 dark:text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" />Caricamento catalogo stazioni…</p> : null}
          {loadError ? <p className="px-3 py-3 text-sm text-amber-900 dark:text-amber-200">Catalogo non disponibile. Puoi inserire il nome manualmente; il codice non verrà associato.</p> : null}
          {!isLoading && !loadError && suggestions.length === 0 ? <p className="flex items-center gap-2 px-3 py-3 text-sm text-slate-600 dark:text-muted-foreground"><Search className="h-4 w-4" />Nessuna stazione trovata nel catalogo. Puoi inserire il nome manualmente.</p> : null}
          {suggestions.map((station, index) => (
            <button id={`${id}-option-${station.stationId}`} key={station.stationId} type="button" role="option" aria-selected={selectedStation?.stationId === station.stationId || activeIndex === index} onMouseEnter={() => setActiveIndex(index)} onMouseDown={event => event.preventDefault()} onClick={() => selectStation(station)} className={`flex min-h-11 w-full items-center justify-between gap-3 border-b border-slate-100 px-3 py-2.5 text-left last:border-b-0 hover:bg-blue-50 focus:bg-blue-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring dark:border-border dark:hover:bg-blue-950/40 dark:focus:bg-blue-950/40 ${activeIndex === index ? "bg-blue-50 dark:bg-blue-950/40" : ""}`}>
              <span className="min-w-0"><span className="block truncate text-sm font-semibold text-slate-950 dark:text-foreground">{station.name}</span><span className="block truncate text-xs text-slate-500 dark:text-muted-foreground">{station.aliases.slice(0, 2).join(" · ") || station.confidence}</span></span>
              <span className="shrink-0 rounded-md bg-slate-100 px-1.5 py-1 font-mono text-xs text-slate-700 dark:bg-muted dark:text-muted-foreground">{station.code}</span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
