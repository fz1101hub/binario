import DashboardLayout from "@/components/DashboardLayout";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { StationAutocompleteInput } from "@/components/StationAutocompleteInput";
import { NearbyStationsPanel } from "@/components/NearbyStationsPanel";
import { VoiceJourneyPlanner } from "@/components/VoiceJourneyPlanner";
import { PrivacyControlsCard } from "@/components/PrivacyControlsCard";
import { LocalBrowserDataCard } from "@/components/LocalBrowserDataCard";
import { LocalJourneyTransferCard } from "@/components/LocalJourneyTransferCard";
import { RoutePortabilityCard } from "@/components/RoutePortabilityCard";
import { QuickActionsCard } from "@/components/QuickActionsCard";
import { OptInRouteSyncCard } from "@/components/OptInRouteSyncCard";
import { useAuth } from "@/_core/hooks/useAuth";
import { startLogin } from "@/const";
import { trpc } from "@/lib/trpc";
import { createPersonalRouteName, getRailDataStatus, swapEndpoints } from "@shared/railPresentation";
import { getStationBoardPath, type CanonicalStation } from "@shared/stationCatalog";
import { getRouteDetailPath } from "@shared/deepLinks";
import { LOCAL_GUEST_ROUTES_STORAGE_KEY, parseLocalGuestRoutes, prependLocalGuestRoute, type LocalGuestRoute } from "@shared/localGuestRoutes";
import { JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY, checklistFromTemplates, createReplannedJourneyDraft, createReturnJourneyDraft, defaultJourneyExtension, parseJourneyAccessibilityPreferences } from "@shared/localJourneyToolkit";
import { LOCAL_JOURNEY_TEMPLATES_STORAGE_KEY, parseLocalJourneyTemplates, prependLocalJourneyTemplate, type LocalJourneyTemplate } from "@shared/localJourneyTemplates";
import { prepareOptInRouteSyncPreview, selectedOptInRouteSyncDrafts } from "@shared/optInRouteSync";
import { getRouteActionLabels } from "@shared/routeAccessibility";
import {
  ArrowDownUp,
  ArrowRight,
  Archive,
  BellRing,
  CalendarDays,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CircleAlert,
  Clock3,
  History,
  Link2Off,
  MapPin,
  Pin,
  Plus,
  Radio,
  RotateCcw,
  Route,
  Search,
  ShieldCheck,
  Star,
  TrainFront,
} from "lucide-react";
import React, { FormEvent, useEffect, useMemo, useState } from "react";
import { useLocation } from "wouter";

const unavailableRailData = getRailDataStatus({ hasRealtimeSource: false, hasScheduledSource: false });

const statusToneClasses = {
  live: "border-emerald-200 bg-emerald-50 text-emerald-900",
  scheduled: "border-violet-200 bg-violet-50 text-violet-900",
  unavailable: "border-amber-200 bg-amber-50 text-amber-950",
} as const;

function toLocalDateInputValue() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

function toLocalTimeInputValue() {
  const now = new Date();
  return `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
}

function routeTags(value: string | null | undefined) {
  try {
    const parsed: unknown = JSON.parse(value ?? "[]");
    return Array.isArray(parsed) && parsed.every(tag => typeof tag === "string") ? parsed : [] as string[];
  } catch { return [] as string[]; }
}

function routeFingerprint(route: { origin: string; originStationCode: string | null; destination: string; destinationStationCode: string | null; travelDate: string | null; travelTime: string | null }) {
  return [route.originStationCode ?? route.origin.trim().toLocaleLowerCase("it"), route.destinationStationCode ?? route.destination.trim().toLocaleLowerCase("it"), route.travelDate ?? "", route.travelTime ?? ""].join("|");
}

function readGuestRoutes(): LocalGuestRoute[] {
  return parseLocalGuestRoutes(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY));
}

function readGuestTemplates(): LocalJourneyTemplate[] {
  return parseLocalJourneyTemplates(localStorage.getItem(LOCAL_JOURNEY_TEMPLATES_STORAGE_KEY));
}

function stationFromTemplate(name: string, code: string | null): CanonicalStation | null {
  return code ? { stationId: code, name, code, latitude: null, longitude: null, aliases: [], confidence: "Modello locale", trenitaliaLocationId: null } : null;
}

function GuestHome() {
  const [, setLocation] = useLocation();
  const [origin, setOrigin] = useState("");
  const [destination, setDestination] = useState("");
  const [originStation, setOriginStation] = useState<CanonicalStation | null>(null);
  const [destinationStation, setDestinationStation] = useState<CanonicalStation | null>(null);
  const [travelDate, setTravelDate] = useState(toLocalDateInputValue);
  const [travelTime, setTravelTime] = useState(toLocalTimeInputValue);
  const [routes, setRoutes] = useState<LocalGuestRoute[]>(readGuestRoutes);
  const [templates, setTemplates] = useState<LocalJourneyTemplate[]>(readGuestTemplates);
  const [templateLabel, setTemplateLabel] = useState("");
  const [localRouteSearch, setLocalRouteSearch] = useState("");
  const [message, setMessage] = useState("");
  const [stationError, setStationError] = useState("");
  const quickActions = useMemo(() => [
    { id: "plan" as const, label: "Pianifica una tratta", description: "Vai a Partenza", targetId: "guest-origin" },
    { id: "station" as const, label: "Cerca una stazione", description: "Usa il catalogo offline", targetId: "guest-destination" },
    { id: "agenda" as const, label: "Apri agenda", description: "Consulta i viaggi di questo browser", onInvoke: () => setLocation("/agenda") },
  ], [setLocation]);

  function persist(next: LocalGuestRoute[]) {
    const bounded = next.slice(0, 40);
    localStorage.setItem(LOCAL_GUEST_ROUTES_STORAGE_KEY, JSON.stringify(bounded));
    setRoutes(bounded);
  }

  function persistTemplates(next: LocalJourneyTemplate[]) {
    localStorage.setItem(LOCAL_JOURNEY_TEMPLATES_STORAGE_KEY, JSON.stringify(next));
    setTemplates(next);
  }

  function saveJourneyTemplate() {
    if (!origin.trim() || !destination.trim() || origin.trim().localeCompare(destination.trim(), "it", { sensitivity: "base" }) === 0) {
      setMessage("Completa due stazioni diverse prima di salvare un modello.");
      return;
    }
    const preferences = parseJourneyAccessibilityPreferences(localStorage.getItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY));
    const defaultChecklist = preferences.accessibilityChecklistByDefault ? ["accessibility"] as const : [];
    const template: LocalJourneyTemplate = {
      schemaVersion: 1, id: crypto.randomUUID(), label: templateLabel.trim() || `${origin.trim()} → ${destination.trim()}`,
      origin: origin.trim(), destination: destination.trim(), originStationCode: originStation?.code ?? null, destinationStationCode: destinationStation?.code ?? null,
      travelTime, reminderMinutes: preferences.defaultReminderMinutes, checklistTemplateIds: [...defaultChecklist], createdAt: new Date().toISOString(),
    };
    persistTemplates(prependLocalJourneyTemplate(templates, template));
    setTemplateLabel("");
    setMessage("Modello salvato solo in questo browser. Puoi applicarlo e scegliere ogni volta la data.");
  }

  function applyJourneyTemplate(template: LocalJourneyTemplate) {
    setOrigin(template.origin); setDestination(template.destination); setTravelTime(template.travelTime);
    setOriginStation(stationFromTemplate(template.origin, template.originStationCode));
    setDestinationStation(stationFromTemplate(template.destination, template.destinationStationCode));
    setStationError("");
    setMessage(`Modello “${template.label}” applicato. Scegli la data e conferma il salvataggio.`);
  }

  function applyControlledPlanning(route: LocalGuestRoute, kind: "return" | "replan") {
    const draft = kind === "return" ? createReturnJourneyDraft(route) : createReplannedJourneyDraft(route);
    setOrigin(draft.origin);
    setDestination(draft.destination);
    setOriginStation(stationFromTemplate(draft.origin, draft.originStationCode));
    setDestinationStation(stationFromTemplate(draft.destination, draft.destinationStationCode));
    setTravelDate("");
    setTravelTime("");
    setStationError("");
    setMessage(kind === "return" ? "Ritorno preparato: scegli nuova data e ora, poi salva la tratta." : "Tratta ripianificata: scegli nuova data e ora, poi salva la tratta.");
    window.requestAnimationFrame(() => document.getElementById("guest-date")?.focus());
  }

  const visibleLocalRoutes = useMemo(() => {
    const query = localRouteSearch.trim().toLocaleLowerCase("it");
    return !query ? routes : routes.filter(route => [route.origin, route.destination, route.travelDate, route.travelTime].some(value => value.toLocaleLowerCase("it").includes(query)));
  }, [localRouteSearch, routes]);

  function saveLocalRoute(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!origin.trim() || !destination.trim() || origin.trim().toLocaleLowerCase("it") === destination.trim().toLocaleLowerCase("it")) {
      setMessage("Inserisci due stazioni diverse per salvare una tratta locale.");
      setStationError("Partenza e arrivo devono essere due stazioni diverse.");
      window.requestAnimationFrame(() => document.getElementById("guest-destination")?.focus());
      return;
    }
    const accessibilityPreferences = parseJourneyAccessibilityPreferences(localStorage.getItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY));
    const checklistTemplateIds = accessibilityPreferences.accessibilityChecklistByDefault ? ["accessibility"] as const : [];
    const route: LocalGuestRoute = {
      id: crypto.randomUUID(), origin: origin.trim(), destination: destination.trim(),
      originStationCode: originStation?.code ?? null, destinationStationCode: destinationStation?.code ?? null,
      travelDate, travelTime, ...defaultJourneyExtension(), reminderMinutes: accessibilityPreferences.defaultReminderMinutes, checklistTemplateIds: [...checklistTemplateIds], checklist: checklistFromTemplates(checklistTemplateIds),
    };
    persist(prependLocalGuestRoute(routes, route));
    setStationError("");
    setMessage("Tratta salvata solo su questo dispositivo. È un contesto programmato, non realtime.");
  }

  return <main className="binario-page min-h-screen bg-background text-foreground"><div className="mx-auto max-w-6xl px-4 py-8 sm:px-6 lg:py-12">
    <header className="mb-8 flex flex-wrap items-center justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[0.18em] text-primary">Pianificazione locale-first</p><h1 className="mt-2 text-3xl font-black tracking-tight sm:text-4xl">Binario</h1><p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">Organizza i tuoi spostamenti subito, senza creare un account. Le tratte restano in questo browser finché non le elimini.</p></div><div className="flex flex-wrap gap-2"><Button type="button" variant="outline" onClick={() => setLocation("/agenda")}>Apri agenda</Button><Button type="button" variant="outline" onClick={() => startLogin()}>Accedi facoltativamente</Button></div></header>
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]"><section className="space-y-6"><Card className="border-primary/20 shadow-sm"><CardHeader><CardTitle>Pianifica una tratta</CardTitle><CardDescription id="guest-planner-description">Salva una partenza e un arrivo sul dispositivo. Nessun dato di servizio viene simulato.</CardDescription></CardHeader><CardContent><form onSubmit={saveLocalRoute} className="grid gap-4 sm:grid-cols-2" aria-describedby="guest-planner-description"><StationAutocompleteInput id="guest-origin" label="Partenza" value={origin} onChange={value => { setOrigin(value); setStationError(""); }} onSelect={setOriginStation} selectedStation={originStation} placeholder="Es. Roma Termini" description="Scrivi almeno due caratteri per cercare nel catalogo o usa il pulsante posizione." allowNearbyStation /><StationAutocompleteInput id="guest-destination" label="Arrivo" value={destination} onChange={value => { setDestination(value); setStationError(""); }} onSelect={setDestinationStation} selectedStation={destinationStation} placeholder="Es. Firenze S. M. Novella" description="Scegli una stazione diversa dalla partenza." error={stationError} /><div><Label htmlFor="guest-date">Data</Label><Input id="guest-date" type="date" value={travelDate} min={toLocalDateInputValue()} onChange={event => setTravelDate(event.target.value)} className="mt-1.5" required aria-describedby="guest-planner-description" /></div><div><Label htmlFor="guest-time">Ora</Label><Input id="guest-time" type="time" value={travelTime} onChange={event => setTravelTime(event.target.value)} className="mt-1.5" required aria-describedby="guest-planner-description" /></div><div className="sm:col-span-2 flex flex-wrap items-center gap-3"><Button type="submit"><Plus className="mr-2 h-4 w-4" />Salva sul dispositivo</Button><p role="status" aria-live="polite" className="text-sm text-muted-foreground">{message}</p></div></form></CardContent></Card>
      <Card><CardHeader><CardTitle>Modelli di viaggio</CardTitle><CardDescription>Salva un percorso e le preferenze locali per riutilizzarli. La data resta sempre da scegliere e nessuna copia viene sincronizzata automaticamente.</CardDescription></CardHeader><CardContent className="space-y-3"><div className="flex flex-wrap items-end gap-3"><div className="min-w-52 flex-1"><Label htmlFor="guest-template-label">Nome del modello</Label><Input id="guest-template-label" value={templateLabel} onChange={event => setTemplateLabel(event.target.value)} placeholder="Es. Casa → lavoro" maxLength={80} className="mt-1.5" /></div><Button type="button" variant="outline" onClick={saveJourneyTemplate}>Salva modello corrente</Button></div>{templates.length === 0 ? <p className="text-sm text-muted-foreground">Completa il planner e salva il primo modello per riutilizzare percorso e orario.</p> : <ul className="space-y-2" aria-label="Modelli viaggio locali">{templates.map(template => <li key={template.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border p-3"><div><p className="font-semibold">{template.label}</p><p className="mt-1 text-xs text-muted-foreground">{template.origin} → {template.destination} · {template.travelTime} · Solo browser</p></div><div className="flex gap-2"><Button type="button" size="sm" variant="outline" onClick={() => applyJourneyTemplate(template)}>Applica</Button><Button type="button" size="sm" variant="ghost" className="text-destructive hover:text-destructive" onClick={() => persistTemplates(templates.filter(item => item.id !== template.id))}>Elimina</Button></div></li>)}</ul>}</CardContent></Card>
      <Card><CardHeader><CardTitle>Le tue tratte su questo dispositivo</CardTitle><CardDescription>Puoi eliminarle in qualsiasi momento; per sincronizzarle in futuro l’accesso sarà una scelta, non un requisito.</CardDescription></CardHeader><CardContent className="space-y-3">{routes.length > 0 ? <div><Label htmlFor="guest-route-search">Cerca nelle tratte locali</Label><Input id="guest-route-search" value={localRouteSearch} onChange={event => setLocalRouteSearch(event.target.value)} placeholder="Stazione, data o ora" className="mt-1.5" type="search" /></div> : null}{routes.length === 0 ? <p className="text-sm text-muted-foreground">Nessuna tratta locale ancora salvata.</p> : visibleLocalRoutes.length === 0 ? <p role="status" className="text-sm text-muted-foreground">Nessuna tratta corrisponde alla ricerca. Le tratte restano salvate in questo browser.</p> : visibleLocalRoutes.map(route => <div key={route.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border p-3"><div><p className="font-semibold">{route.origin} <ArrowRight className="mx-1 inline h-4 w-4" aria-hidden="true" /> {route.destination}</p><p className="mt-1 text-xs text-muted-foreground">{route.travelDate} · {route.travelTime} · Programmato, non realtime</p></div><div className="flex flex-wrap gap-2"><Button type="button" size="sm" variant="outline" onClick={() => applyControlledPlanning(route, "return")}>Pianifica ritorno</Button><Button type="button" size="sm" variant="outline" onClick={() => applyControlledPlanning(route, "replan")}>Ripianifica</Button><Button type="button" size="sm" variant="outline" onClick={() => setLocation(getStationBoardPath({ name: route.origin, code: route.originStationCode ?? "" }))} disabled={!route.originStationCode}>Tabellone</Button><Button type="button" size="sm" variant="ghost" className="text-destructive hover:text-destructive" onClick={() => persist(routes.filter(item => item.id !== route.id))}>Elimina</Button></div></div>)}</CardContent></Card>
    </section><aside className="space-y-6"><QuickActionsCard actions={quickActions} /><Card className="border-amber-200 bg-amber-50/60 dark:border-amber-900 dark:bg-amber-950/20"><CardHeader><CardTitle className="text-base">Stato ferroviario</CardTitle></CardHeader><CardContent className="text-sm leading-6 text-amber-950 dark:text-amber-100">Dati realtime non disponibili: Binario non inventa ritardi, binari, cancellazioni o avvisi. <Button variant="link" className="h-auto px-0 align-baseline text-current underline" onClick={() => setLocation("/trasparenza-dati")}>Come valutiamo le fonti</Button></CardContent></Card><Card><CardHeader><CardTitle className="text-base">Telefono, widget e smartwatch</CardTitle></CardHeader><CardContent className="text-sm leading-6 text-muted-foreground">L’app Binario, il widget e l’orologio funzionano localmente. Lo smartwatch associato dal sistema riceve solo il contesto programmato e valido dal telefono.</CardContent></Card></aside></div>
  </div><div className="mx-auto max-w-6xl px-4 pb-8 sm:px-6"><LocalJourneyTransferCard routes={routes} onImport={imported => { persist([...imported, ...routes]); setMessage(`${imported.length} ${imported.length === 1 ? "tratta importata" : "tratte importate"} solo in questo browser.`); }} /></div></main>;
}

export default function Home() {
  const { loading, user } = useAuth();
  if (loading) return <div className="min-h-screen bg-background" aria-busy="true" />;
  return user ? <AuthenticatedHome /> : <GuestHome />;
}

function AuthenticatedHome() {
  const [, setLocation] = useLocation();
  const utils = trpc.useUtils();
  const [showArchivedRoutes, setShowArchivedRoutes] = useState(false);
  const [routeSearch, setRouteSearch] = useState("");
  const [routeTagFilter, setRouteTagFilter] = useState("");
  const [routeMonthFilter, setRouteMonthFilter] = useState("");
  const routeListInput = useMemo(() => ({ includeArchived: showArchivedRoutes }), [showArchivedRoutes]);
  const routeSyncListInput = useMemo(() => ({ includeArchived: true }), []);
  const routes = trpc.routes.list.useQuery(routeListInput);
  const accountRoutesForSync = trpc.routes.list.useQuery(routeSyncListInput);
  const stationPreferences = trpc.stations.listPreferences.useQuery();
  const voice = trpc.voice.status.useQuery();
  const [origin, setOrigin] = useState("");
  const [destination, setDestination] = useState("");
  const [originStation, setOriginStation] = useState<CanonicalStation | null>(null);
  const [destinationStation, setDestinationStation] = useState<CanonicalStation | null>(null);
  const [stationQuery, setStationQuery] = useState("");
  const [selectedBoardStation, setSelectedBoardStation] = useState<CanonicalStation | null>(null);
  const [name, setName] = useState("");
  const [isPinned, setIsPinned] = useState(false);
  const [travelDate, setTravelDate] = useState(toLocalDateInputValue);
  const [travelTime, setTravelTime] = useState(toLocalTimeInputValue);
  const [browserRoutes, setBrowserRoutes] = useState<LocalGuestRoute[]>([]);
  const [showBrowserRoutePreview, setShowBrowserRoutePreview] = useState(false);
  const [browserRouteMessage, setBrowserRouteMessage] = useState("");
  const [browserSyncDisabled, setBrowserSyncDisabled] = useState(false);
  const [selectedBrowserRouteIds, setSelectedBrowserRouteIds] = useState<string[]>([]);
  const quickActions = useMemo(() => [
    { id: "plan" as const, label: "Nuova tratta", description: "Vai alla partenza", targetId: "route-origin" },
    { id: "station" as const, label: "Cerca una stazione", description: "Vai al tabellone", targetId: "station-board" },
    { id: "voice" as const, label: "Avvia dettatura", description: "Vai al testo della tratta", targetId: "voice-journey-transcript" },
    { id: "agenda" as const, label: "Apri agenda", description: "Consulta i viaggi locali", onInvoke: () => setLocation("/agenda") },
  ], [setLocation]);

  useEffect(() => { setBrowserRoutes(readGuestRoutes()); setBrowserSyncDisabled(window.localStorage.getItem("binario-route-sync-disabled-v1") === "true"); }, []);

  const createRoute = trpc.routes.create.useMutation({
    onSuccess: () => {
      utils.routes.list.invalidate();
      setOrigin("");
      setDestination("");
      setOriginStation(null);
      setDestinationStation(null);
      setName("");
      setIsPinned(false);
    },
  });
  const importBrowserRoutes = trpc.routes.import.useMutation({
    onSuccess: result => {
      utils.routes.list.invalidate();
      setBrowserRouteMessage(`${result.imported} ${result.imported === 1 ? "tratta è stata copiata" : "tratte sono state copiate"} nell’area personale. Le copie locali restano in questo browser finché non le elimini.`);
      setSelectedBrowserRouteIds([]);
      setShowBrowserRoutePreview(false);
    },
  });
  const setPinned = trpc.routes.setPinned.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const removeRoute = trpc.routes.remove.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const archiveRoute = trpc.routes.archive.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const restoreRoute = trpc.routes.restore.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const reorderRoutes = trpc.routes.reorder.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const unlink = trpc.voice.unlink.useMutation({ onSuccess: () => voice.refetch() });
  const clearRecentStations = trpc.stations.clearRecent.useMutation({ onSuccess: () => stationPreferences.refetch() });

  const favoriteStations = stationPreferences.data?.filter(station => station.isFavorite) ?? [];
  const recentStations = stationPreferences.data?.filter(station => !station.isFavorite && station.lastOpenedAt) ?? [];
  const availableRouteTags = useMemo(() => Array.from(new Set((routes.data ?? []).flatMap(route => routeTags(route.personalTagsJson)))).sort((left, right) => left.localeCompare(right, "it")), [routes.data]);
  const availableRouteMonths = useMemo(() => Array.from(new Set((routes.data ?? []).map(route => route.travelDate?.slice(0, 7)).filter((month): month is string => Boolean(month && /^\d{4}-\d{2}$/.test(month))))).sort().reverse(), [routes.data]);
  const duplicateRouteIds = useMemo(() => {
    const counts = new Map<string, number>();
    for (const route of routes.data ?? []) { const key = routeFingerprint(route); counts.set(key, (counts.get(key) ?? 0) + 1); }
    return new Set((routes.data ?? []).filter(route => (counts.get(routeFingerprint(route)) ?? 0) > 1).map(route => route.id));
  }, [routes.data]);
  const visibleRoutes = useMemo(() => {
    const query = routeSearch.trim().toLocaleLowerCase("it");
    return (routes.data ?? []).filter(route => {
      const tags = routeTags(route.personalTagsJson);
      return (!query || [route.name, route.origin, route.destination, ...tags].some(value => value.toLocaleLowerCase("it").includes(query))) && (!routeTagFilter || tags.includes(routeTagFilter)) && (!routeMonthFilter || route.travelDate?.startsWith(routeMonthFilter));
    });
  }, [routeMonthFilter, routeSearch, routeTagFilter, routes.data]);
  const browserRouteSyncPreview = useMemo(() => prepareOptInRouteSyncPreview(browserRoutes, accountRoutesForSync.data ?? []), [accountRoutesForSync.data, browserRoutes]);
  const selectedBrowserRouteImports = useMemo(() => selectedOptInRouteSyncDrafts(browserRouteSyncPreview, new Set(selectedBrowserRouteIds)), [browserRouteSyncPreview, selectedBrowserRouteIds]);

  function importConfirmedBrowserRoutes() {
    if (selectedBrowserRouteImports.length === 0) {
      setBrowserRouteMessage("Seleziona almeno una tratta nuova prima di confermare. Le copie locali e l’area personale non sono state modificate.");
      setShowBrowserRoutePreview(false);
      return;
    }
    importBrowserRoutes.mutate({ schemaVersion: 1, routes: selectedBrowserRouteImports });
  }

  function openBrowserRouteSyncPreview() { setBrowserRouteMessage(""); setSelectedBrowserRouteIds([]); setShowBrowserRoutePreview(true); }
  function toggleBrowserRouteSyncCandidate(localId: string) { setSelectedBrowserRouteIds(current => current.includes(localId) ? current.filter(id => id !== localId) : [...current, localId]); }
  function disableBrowserRouteSync() { window.localStorage.setItem("binario-route-sync-disabled-v1", "true"); setBrowserSyncDisabled(true); setShowBrowserRoutePreview(false); setSelectedBrowserRouteIds([]); setBrowserRouteMessage("Il trasferimento è disattivato in questo browser. Nessuna tratta è stata cancellata."); }
  function enableBrowserRouteSync() { window.localStorage.removeItem("binario-route-sync-disabled-v1"); setBrowserSyncDisabled(false); setBrowserRouteMessage("Revisione manuale riattivata. Nessun trasferimento è stato avviato."); }

  function moveRoute(routeId: number, direction: -1 | 1) {
    if (showArchivedRoutes || routeSearch || routeTagFilter || routeMonthFilter || !routes.data) return;
    const index = routes.data.findIndex(route => route.id === routeId);
    const targetIndex = index + direction;
    const current = routes.data[index];
    const target = routes.data[targetIndex];
    if (!current || !target || current.isPinned !== target.isPinned) return;
    const ids = routes.data.map(route => route.id);
    [ids[index], ids[targetIndex]] = [ids[targetIndex]!, ids[index]!];
    reorderRoutes.mutate({ ids });
  }

  function submitRoute(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!origin.trim() || !destination.trim()) return;
    createRoute.mutate({
      origin: origin.trim(),
      originStationCode: originStation?.code ?? null,
      destination: destination.trim(),
      destinationStationCode: destinationStation?.code ?? null,
      name: createPersonalRouteName(origin, destination, name),
      isPinned,
      travelDate,
      travelTime,
    });
  }

  function swapRoute() {
    const next = swapEndpoints(origin, destination);
    setOrigin(next.origin);
    setDestination(next.destination);
    setOriginStation(destinationStation);
    setDestinationStation(originStation);
  }

  function openStationBoard() {
    if (selectedBoardStation) setLocation(getStationBoardPath(selectedBoardStation));
  }

  function openStoredStation(station: { stationName: string; stationCode: string }) {
    setLocation(getStationBoardPath({ name: station.stationName, code: station.stationCode }));
  }

  function applyVoiceJourney(journey: { origin: CanonicalStation; destination: CanonicalStation; date: string | null; time: string | null }) {
    setOrigin(journey.origin.name);
    setOriginStation(journey.origin);
    setDestination(journey.destination.name);
    setDestinationStation(journey.destination);
    if (journey.date) setTravelDate(journey.date);
    if (journey.time) setTravelTime(journey.time);
  }

  return (
    <DashboardLayout>
      <div className="binario-page mx-auto max-w-6xl space-y-6 pb-12">
        <header className="relative overflow-hidden rounded-[1.75rem] bg-slate-950 px-6 py-7 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8">
          <div className="pointer-events-none absolute inset-y-0 right-0 w-2/5 bg-[linear-gradient(135deg,transparent_10%,rgba(34,211,238,0.13)_10%,transparent_11%,transparent_23%,rgba(59,130,246,0.18)_23%,transparent_24%)]" />
          <div className="relative flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
            <div className="max-w-2xl">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium text-cyan-200">
                <TrainFront className="h-4 w-4" />
                Binario · viaggi, stazioni e monitoraggi
              </div>
              <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Prepara il prossimo viaggio.</h1>
              <p className="mt-3 max-w-xl text-sm leading-6 text-slate-300">
                Prima scegli le stazioni e il momento; poi salva la tratta. L&apos;app resta utile anche senza realtime e i companion sono sempre opzionali.
              </p>
              <p className="mt-4 text-xs font-medium text-cyan-100">1. Cerca le stazioni · 2. Aggiungi data e ora · 3. Salva sul tuo archivio personale</p>
            </div>
            <Badge className="binario-status-badge w-fit border border-amber-300/40 bg-amber-300 text-amber-950 hover:bg-amber-300">
              <Radio className="mr-1.5 h-3.5 w-3.5" />
              {unavailableRailData.label}
            </Badge>
          </div>
        </header>

        <div className="grid gap-6 xl:grid-cols-[1.32fr_0.68fr]">
          <section className="space-y-6" aria-labelledby="planner-heading">
            <Card className="overflow-hidden border-slate-200 shadow-sm">
              <CardHeader className="border-b border-slate-100 bg-slate-50/80 pb-5">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-blue-700">
                      <Route className="h-4 w-4" /> Viaggi
                    </div>
                    <CardTitle id="planner-heading" className="text-xl">Cerca e prepara un viaggio</CardTitle>
                    <CardDescription className="mt-1.5 max-w-xl">
                      Parti dalle stazioni del catalogo nazionale e salva una tratta per averla sempre disponibile nell&apos;app. Orari e stati live compariranno solo quando la fonte sarà disponibile.
                    </CardDescription>
                    <p className="mt-3 text-xs leading-5 text-slate-600"><strong>Dettatura nell’app:</strong> usa il microfono accanto a Partenza, Arrivo o Stazione. L’audio è inviato solo per la trascrizione, non viene aggiunto ai monitoraggi e il testo resta modificabile.</p>
                  </div>
                  <div className="hidden h-10 w-10 items-center justify-center rounded-2xl bg-blue-100 text-blue-700 sm:flex">
                    <TrainFront className="h-5 w-5" />
                  </div>
                </div>
              </CardHeader>
              <CardContent className="pt-6">
                <VoiceJourneyPlanner onApply={applyVoiceJourney} />
                <OptInRouteSyncCard candidates={browserRouteSyncPreview} selectedLocalIds={selectedBrowserRouteIds} isPreviewOpen={showBrowserRoutePreview} isDisabled={browserSyncDisabled} isPending={importBrowserRoutes.isPending} message={importBrowserRoutes.error ? "Non è stato possibile copiare le tratte. Le copie locali non sono state modificate." : browserRouteMessage} error={Boolean(importBrowserRoutes.error)} onOpenPreview={openBrowserRouteSyncPreview} onClosePreview={() => { setShowBrowserRoutePreview(false); setSelectedBrowserRouteIds([]); }} onToggleCandidate={toggleBrowserRouteSyncCandidate} onConfirm={importConfirmedBrowserRoutes} onDisable={disableBrowserRouteSync} onEnable={enableBrowserRouteSync} />
                <form onSubmit={submitRoute} className="space-y-4" aria-describedby="planner-data-note">
                  <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
                    <div className="grid gap-3 border-b border-slate-200 p-4 sm:grid-cols-[1fr_auto_1fr] sm:items-end">
                      <StationAutocompleteInput id="route-origin" label="Da" value={origin} onChange={setOrigin} onSelect={setOriginStation} selectedStation={originStation} placeholder="Stazione di partenza" allowNearbyStation />
                      <Button type="button" variant="outline" size="icon" onClick={swapRoute} className="h-10 w-10 rounded-full border-slate-200 text-blue-700" aria-label="Scambia partenza e arrivo">
                        <ArrowDownUp className="h-4 w-4" />
                      </Button>
                      <StationAutocompleteInput id="route-destination" label="A" value={destination} onChange={setDestination} onSelect={setDestinationStation} selectedStation={destinationStation} placeholder="Stazione di arrivo" />
                    </div>
                    <div className="grid gap-3 p-4 sm:grid-cols-2">
                      <div className="space-y-1.5">
                        <Label htmlFor="travel-date" className="text-slate-600"><CalendarDays className="mr-1.5 inline h-3.5 w-3.5" />Data di riferimento</Label>
                        <Input id="travel-date" type="date" value={travelDate} onChange={event => setTravelDate(event.target.value)} />
                      </div>
                      <div className="space-y-1.5">
                        <Label htmlFor="travel-time" className="text-slate-600"><Clock3 className="mr-1.5 inline h-3.5 w-3.5" />Ora di riferimento</Label>
                        <Input id="travel-time" type="time" value={travelTime} onChange={event => setTravelTime(event.target.value)} />
                      </div>
                    </div>
                  </div>

                  <div className="grid gap-3 rounded-2xl bg-slate-50 p-4 sm:grid-cols-[1fr_auto] sm:items-end">
                    <div className="space-y-1.5">
                      <Label htmlFor="route-name">Nome del monitoraggio <span className="font-normal text-muted-foreground">(facoltativo)</span></Label>
                      <Input id="route-name" value={name} onChange={event => setName(event.target.value)} placeholder="Es. Casa — lavoro" maxLength={120} />
                    </div>
                    <div className="flex items-center justify-between gap-4 sm:justify-end">
                      <Label htmlFor="route-pin" className="flex cursor-pointer items-center gap-2 text-sm font-normal text-slate-700">
                        <Switch id="route-pin" checked={isPinned} onCheckedChange={setIsPinned} /> Fissa
                      </Label>
                      <Button type="submit" size="lg" disabled={createRoute.isPending} className="min-w-48 bg-blue-700 shadow-sm hover:bg-blue-800">
                        <Plus className="mr-2 h-4 w-4" />
                        {createRoute.isPending ? "Salvataggio…" : "Salva viaggio"}
                      </Button>
                    </div>
                  </div>
                  <p id="planner-data-note" className="flex gap-2 text-xs leading-5 text-muted-foreground">
                    <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-blue-700" />
                    Data e ora rendono esplicito il contesto del viaggio; in questa versione non vengono ancora inoltrate a un provider ferroviario.
                  </p>
                  {createRoute.error ? <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-800">Non è stato possibile salvare la tratta. Riprova tra poco.</p> : null}
                </form>
              </CardContent>
            </Card>

            <Card className="border-slate-200 shadow-sm">
              <CardHeader className="pb-4">
                <div className="flex items-start justify-between gap-4">
                      <div>
                        <CardTitle>{showArchivedRoutes ? "Archivio viaggi" : "I tuoi viaggi"}</CardTitle>
                        <CardDescription className="mt-1">Le tratte personali restano private. L&apos;archiviazione è reversibile e non cancella alcun dato del viaggio.</CardDescription>
                      </div>
                      <div className="flex shrink-0 items-center gap-2"><Badge variant="outline" className="border-slate-200 text-slate-600">{routes.data?.length ?? 0} {showArchivedRoutes ? "archiviati" : "attivi"}</Badge><Button type="button" variant="outline" size="sm" className="border-slate-200" onClick={() => setShowArchivedRoutes(value => !value)}>{showArchivedRoutes ? "Mostra attivi" : "Apri archivio"}</Button></div>
                </div>
              </CardHeader>
              <CardContent>
                {(routes.data?.length ?? 0) > 0 ? <div className="mb-4 grid gap-2 lg:grid-cols-[minmax(0,1fr)_auto_auto]"><div className="relative"><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" /><Input value={routeSearch} onChange={event => setRouteSearch(event.target.value)} className="pl-9" placeholder="Cerca fra i tuoi viaggi e tag" aria-label="Cerca fra i tuoi viaggi e tag" /></div>{availableRouteTags.length > 0 ? <select value={routeTagFilter} onChange={event => setRouteTagFilter(event.target.value)} className="h-10 rounded-md border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label="Filtra viaggi per tag"><option value="">Tutti i tag</option>{availableRouteTags.map(tag => <option key={tag} value={tag}>{tag}</option>)}</select> : null}{availableRouteMonths.length > 0 ? <select value={routeMonthFilter} onChange={event => setRouteMonthFilter(event.target.value)} className="h-10 rounded-md border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label="Filtra viaggi per mese"><option value="">Tutti i mesi</option>{availableRouteMonths.map(month => <option key={month} value={month}>{new Intl.DateTimeFormat("it-IT", { month: "long", year: "numeric" }).format(new Date(`${month}-01T12:00:00`))}</option>)}</select> : null}</div> : null}
                <div className="space-y-2">
                  <p role="status" className="sr-only">{routes.isLoading ? "Caricamento viaggi" : routes.error ? "Archivio personale non disponibile" : `${visibleRoutes.length} ${visibleRoutes.length === 1 ? "viaggio visualizzato" : "viaggi visualizzati"}`}</p>
                  {routes.isLoading ? <p className="rounded-xl border border-dashed border-slate-200 p-4 text-sm text-muted-foreground">Caricamento monitoraggi…</p> : null}
                  {routes.error ? <p role="alert" className="rounded-xl bg-red-50 p-4 text-sm text-red-800">L&apos;archivio personale non è disponibile. Le tratte non sono state modificate.</p> : null}
                  {!routes.isLoading && !routes.error && visibleRoutes.length === 0 ? (
                    <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50/70 p-5">
                      <TrainFront className="mb-3 h-5 w-5 text-blue-700" />
                      <p className="font-medium text-slate-900">{routeSearch || routeTagFilter || routeMonthFilter ? "Nessun viaggio corrispondente." : showArchivedRoutes ? "Nessun viaggio archiviato." : "Nessun viaggio salvato."}</p>
                      <p className="mt-1 text-sm leading-6 text-muted-foreground">{routeSearch || routeTagFilter || routeMonthFilter ? "Modifica la ricerca o rimuovi un filtro. Le tratte non sono state modificate." : showArchivedRoutes ? "Le tratte archiviate restano private e possono essere ripristinate in qualsiasi momento." : "Aggiungi una tratta per ritrovarla nell&apos;app, preparare promemoria programmati e decidere in seguito se usarla anche su widget o companion vocali."}</p>
                    </div>
                  ) : null}
                  {visibleRoutes.map((route, visibleIndex) => (
                    <article key={route.id} aria-labelledby={`route-${route.id}-name`} className="group flex items-center gap-3 rounded-2xl border border-slate-200 bg-white p-3.5 transition-colors hover:border-blue-200 hover:bg-blue-50/30 focus-within:border-blue-600 focus-within:ring-2 focus-within:ring-blue-600/30">
                      <button disabled={showArchivedRoutes || setPinned.isPending} onClick={() => setPinned.mutate({ id: route.id, isPinned: !route.isPinned })} className="rounded-xl p-2 text-slate-500 transition-colors hover:bg-blue-100 hover:text-blue-700 focus-visible:ring-2 focus-visible:ring-blue-700 disabled:cursor-not-allowed disabled:opacity-50" aria-label={getRouteActionLabels(route.name, route.isPinned).pin}>
                        <Pin className={`h-4 w-4 ${route.isPinned ? "fill-blue-600 text-blue-700" : ""}`} />
                      </button>
                      <div className="min-w-0 flex-1">
                        <h3 id={`route-${route.id}-name`} className="truncate font-semibold text-slate-950">{route.name}</h3>
                        <p className="mt-0.5 flex items-center gap-1 truncate text-sm text-muted-foreground"><span>{route.origin}</span><ArrowRight className="h-3.5 w-3.5 shrink-0" /><span>{route.destination}</span></p>
                        {route.travelDate || route.travelTime ? <p className="mt-1 text-xs text-muted-foreground">Contesto programmato, non realtime: {[route.travelDate, route.travelTime].filter(Boolean).join(" · ")}</p> : null}
                        {routeTags(route.personalTagsJson).length > 0 ? <ul className="mt-1 flex flex-wrap gap-1" aria-label={`Tag: ${routeTags(route.personalTagsJson).join(", ")}`}>{routeTags(route.personalTagsJson).map(tag => <li key={tag} className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-700">{tag}</li>)}</ul> : null}
                        {duplicateRouteIds.has(route.id) ? <p className="mt-1 text-xs text-amber-800">Tratta simile già presente: controlla le preferenze prima di conservarne più copie.</p> : null}
                      </div>
                      <div className="hidden text-right sm:block">
                        <span className="text-xs font-medium text-slate-600">{showArchivedRoutes ? "Viaggio archiviato" : "Viaggio salvato"}</span>
                        <p className="mt-1 text-xs text-muted-foreground">{showArchivedRoutes ? "Ripristinabile" : "Stato live da collegare"}</p>
                      </div>
                      <Button variant="ghost" size="icon" className="shrink-0 text-slate-500 hover:text-blue-700" onClick={() => setLocation(getRouteDetailPath(route.id))} aria-label={getRouteActionLabels(route.name, route.isPinned).open}><ChevronRight className="h-4 w-4" /></Button>
                      {!showArchivedRoutes && !routeSearch && !routeTagFilter && !routeMonthFilter ? <div className="flex flex-col"><Button variant="ghost" size="icon" className="h-10 w-10" disabled={visibleIndex === 0 || reorderRoutes.isPending} onClick={() => moveRoute(route.id, -1)} aria-label={getRouteActionLabels(route.name, route.isPinned).moveUp}><ChevronUp className="h-3.5 w-3.5" /></Button><Button variant="ghost" size="icon" className="h-10 w-10" disabled={visibleIndex === visibleRoutes.length - 1 || reorderRoutes.isPending} onClick={() => moveRoute(route.id, 1)} aria-label={getRouteActionLabels(route.name, route.isPinned).moveDown}><ChevronDown className="h-3.5 w-3.5" /></Button></div> : null}
                      {showArchivedRoutes ? <Button variant="ghost" size="icon" onClick={() => restoreRoute.mutate({ id: route.id })} disabled={restoreRoute.isPending} aria-label={getRouteActionLabels(route.name, route.isPinned).restore}><RotateCcw className="h-4 w-4 text-blue-700" /></Button> : <Button variant="ghost" size="icon" onClick={() => archiveRoute.mutate({ id: route.id })} disabled={archiveRoute.isPending} aria-label={getRouteActionLabels(route.name, route.isPinned).archive}><Archive className="h-4 w-4 text-slate-500 transition-colors group-hover:text-blue-700" /></Button>}
                      <Button variant="ghost" size="icon" onClick={() => removeRoute.mutate({ id: route.id })} aria-label={getRouteActionLabels(route.name, route.isPinned).remove}><span className="sr-only">Rimuovi</span><CircleAlert className="h-4 w-4 text-slate-400 transition-colors group-hover:text-red-600" /></Button>
                    </article>
                  ))}
                </div>
              </CardContent>
            </Card>
            <div className="grid gap-6 md:grid-cols-2">
              <Card className="border-slate-200 shadow-sm" aria-labelledby="stations-heading">
                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-blue-700"><MapPin className="h-3.5 w-3.5" />Stazioni</div>
                      <CardTitle id="stations-heading" className="text-lg">Apri un tabellone</CardTitle>
                      <CardDescription className="mt-1">Cerca una stazione del catalogo nazionale per preparare la vista partenze e arrivi.</CardDescription>
                    </div>
                    <MapPin className="mt-1 h-5 w-5 text-blue-700" />
                  </div>
                </CardHeader>
                <CardContent className="space-y-3">
                  <StationAutocompleteInput id="station-board" label="Stazione" value={stationQuery} onChange={setStationQuery} onSelect={setSelectedBoardStation} selectedStation={selectedBoardStation} placeholder="Es. Roma Termini" required={false} />
                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-700">
                    <p className="font-medium">{selectedBoardStation ? selectedBoardStation.name : "Tabellone pronto da configurare"}</p>
                    <p className="mt-1 text-xs leading-5 text-muted-foreground">{selectedBoardStation ? `${selectedBoardStation.code} · tabellone live non ancora collegato` : "Seleziona una stazione: l’identificativo canonico verrà mantenuto per il futuro tabellone."}</p>
                  </div>
                  <Button type="button" variant="outline" className="w-full border-blue-200 text-blue-800 hover:bg-blue-50" onClick={openStationBoard} disabled={!selectedBoardStation}><TrainFront className="mr-2 h-4 w-4" />Apri tabellone</Button>
                  <NearbyStationsPanel onOpenStation={station => setLocation(getStationBoardPath(station))} />
                  <div className="space-y-3 border-t border-slate-200 pt-3">
                    <div className="flex items-center justify-between gap-2"><p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.1em] text-slate-600"><Star className="h-3.5 w-3.5 text-amber-500" />Preferite</p><span className="text-xs text-muted-foreground">{favoriteStations.length}</span></div>
                    {stationPreferences.isLoading ? <p className="text-xs text-muted-foreground">Caricamento stazioni personali…</p> : null}
                    {stationPreferences.error ? <p role="alert" className="text-xs text-amber-900 dark:text-amber-100">Le stazioni personali non sono disponibili. Il catalogo resta utilizzabile.</p> : null}
                    {!stationPreferences.isLoading && !stationPreferences.error && favoriteStations.length === 0 ? <p className="text-xs leading-5 text-muted-foreground">Apri una stazione e contrassegnala come preferita dal tabellone.</p> : null}
                    {favoriteStations.slice(0, 4).map(station => <Button key={station.stationCode} type="button" variant="outline" size="sm" className="mr-2 border-slate-200 text-slate-800 hover:bg-slate-50 dark:text-foreground" onClick={() => openStoredStation(station)}><Star className="mr-1.5 h-3.5 w-3.5 fill-amber-400 text-amber-500" />{station.stationName}</Button>)}
                    {recentStations.length > 0 ? <div className="pt-1"><div className="mb-2 flex items-center justify-between gap-2"><p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.1em] text-slate-600"><History className="h-3.5 w-3.5" />Recenti</p><Button type="button" variant="ghost" size="sm" className="h-auto px-1.5 py-1 text-xs text-slate-600" onClick={() => clearRecentStations.mutate()} disabled={clearRecentStations.isPending}>Cancella</Button></div>{recentStations.slice(0, 4).map(station => <Button key={station.stationCode} type="button" variant="ghost" size="sm" className="mr-1.5 text-slate-700 hover:bg-slate-100 dark:text-foreground" onClick={() => openStoredStation(station)}>{station.stationName}</Button>)}</div> : null}
                  </div>
                </CardContent>
              </Card>
              <Card className="border-slate-200 shadow-sm" aria-labelledby="alerts-heading">
                <CardHeader className="pb-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-blue-700"><BellRing className="h-3.5 w-3.5" />Avvisi</div>
                      <CardTitle id="alerts-heading" className="text-lg">Stato del servizio</CardTitle>
                      <CardDescription className="mt-1">Disservizi, variazioni e avvisi saranno sempre associati a una fonte e a un aggiornamento.</CardDescription>
                    </div>
                    <BellRing className="mt-1 h-5 w-5 text-blue-700" />
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="rounded-xl border border-dashed border-amber-200 bg-amber-50/70 p-3.5 text-sm text-amber-950">
                    <p className="font-semibold">Nessun avviso disponibile</p>
                    <p className="mt-1 text-xs leading-5">Non viene mostrata un&apos;assenza di disservizi: la fonte live non è ancora collegata. Quando lo sarà, ogni avviso indicherà provenienza e orario di aggiornamento.</p>
                  </div>
                </CardContent>
              </Card>
            </div>
          </section>

          <aside className="space-y-6" aria-label="Stato dei dati e companion di Binario">
            <QuickActionsCard actions={quickActions} />
            <Card className="border-slate-200 shadow-sm">
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between gap-3">
                      <div><CardTitle>Stato dei dati ferroviari</CardTitle><CardDescription className="mt-1">Informazioni chiare prima di ogni previsione.</CardDescription></div>
                  <ShieldCheck className="h-5 w-5 text-blue-700" />
                </div>
              </CardHeader>
              <CardContent>
                <div className={`rounded-2xl border p-4 ${statusToneClasses[unavailableRailData.tone]}`}>
                  <p className="flex items-center gap-2 font-semibold"><Radio className="h-4 w-4" />{unavailableRailData.label}</p>
                  <p className="mt-2 text-sm leading-6 opacity-85">{unavailableRailData.detail}</p>
                  <Button type="button" variant="link" className="mt-2 h-auto px-0 text-current underline" onClick={() => setLocation("/trasparenza-dati")}>Come Binario valuta le fonti</Button>
                </div>
                <p className="mt-4 text-xs leading-5 text-muted-foreground">Un futuro fallback con orario programmato sarà sempre distinto da ritardi, binari, cancellazioni e posizione reale.</p>
              </CardContent>
            </Card>

            <details data-binario-optional="true" className="group rounded-2xl border border-slate-200 bg-card text-card-foreground shadow-sm dark:border-slate-800">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"><span><span className="mr-2 text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">Opzionale</span>Companion, telefono e smartwatch</span><ChevronDown className="h-4 w-4 transition-transform duration-200 group-open:rotate-180" aria-hidden="true" /></summary>
              <div className="space-y-5 border-t border-slate-200 p-5 dark:border-slate-800">
                <div className="text-sm leading-6 text-muted-foreground"><p className="font-semibold text-foreground">Telefono e smartwatch</p><p className="mt-1">L’orologio associato dal sistema riceve solo una sintesi locale, programmata e valida. Non servono account, codici manuali, password o token per l’uso quotidiano.</p></div>
                <div className="border-t border-slate-200 pt-5 dark:border-slate-800"><div className="flex items-start justify-between gap-3"><div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.12em] text-slate-500">Companion</p><h2 className="text-base font-semibold">Alexa</h2><p className="mt-1 text-sm text-muted-foreground">Controllo vocale opzionale delle tratte personali.</p></div><Radio className="mt-1 h-5 w-5 text-blue-700" /></div>
                  <div className="mt-4 space-y-3">{voice.data?.configured ? <div className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-950"><strong>Configurazione server disponibile.</strong> Abilita la skill nell&apos;app Alexa per avviare il collegamento.</div> : <div className="rounded-xl bg-amber-50 p-3 text-sm text-amber-950"><strong>Non attivo.</strong> Manca la configurazione della console Alexa; il webhook rifiuta ogni richiesta.</div>}
                    <div className="rounded-xl border border-slate-200 p-3 text-sm"><p className="font-medium text-slate-900 dark:text-slate-100">Stato account</p><p className="mt-1 text-muted-foreground">{voice.data?.linked ? "Collegato e verificato." : "Non collegato. Le risposte personali restano bloccate."}</p></div>
                    {voice.data?.linked ? <Button variant="outline" className="w-full" onClick={() => unlink.mutate()} disabled={unlink.isPending}><Link2Off className="mr-2 h-4 w-4" />Scollega account Alexa</Button> : <p className="text-xs leading-5 text-muted-foreground">Il collegamento sarà disponibile solo dopo la configurazione della Skill e si avvia tramite OAuth ufficiale.</p>}
                  </div>
                </div>
              </div>
            </details>
            <PrivacyControlsCard />
            <LocalBrowserDataCard onCleared={() => { setBrowserRoutes([]); setSelectedBrowserRouteIds([]); setBrowserRouteMessage("Le copie locali di questo browser sono state cancellate. Nessuna tratta dell’account è stata modificata."); }} />
            <RoutePortabilityCard routes={routes.data ?? []} />
          </aside>
        </div>
      </div>
    </DashboardLayout>
  );
}
