import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import DashboardLayout from "@/components/DashboardLayout";
import { GuidedTextJourneyImportCard } from "@/components/GuidedTextJourneyImportCard";
import { LocalItineraryCard } from "@/components/LocalItineraryCard";
import { useTheme } from "@/contexts/ThemeContext";
import { LOCAL_GUEST_ROUTES_STORAGE_KEY, parseLocalGuestRoutes, prependLocalGuestRoute, type LocalGuestRoute } from "@shared/localGuestRoutes";
import { JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY, defaultJourneyExtension, deriveAgenda, deriveLocalJourneyStats, journeyChecklistTemplates, journeyShareText, journeyToIcs, mergeChecklistTemplates, parseJourneyAccessibilityPreferences, parseJourneyIcsEntries, suggestedJourneyReminderMinutes, type AgendaFilter, type AgendaEntry, type ImportedJourneyCalendarEntry, type JourneyAccessibilityPreferences, type JourneyRecurrence, type JourneyReminderMinutes, type JourneyTemplateId, type JourneyWeekday } from "@shared/localJourneyToolkit";
import { Archive, ArrowLeft, BellRing, CalendarDays, Check, Clock3, Download, FileUp, RotateCcw, Send } from "lucide-react";
import React, { useMemo, useState } from "react";
import { useLocation } from "wouter";

type AgendaRange = "day" | "week";

const agendaFilters: Array<{ id: AgendaFilter; label: string }> = [
  { id: "upcoming", label: "Imminenti" },
  { id: "reminders", label: "Promemoria" },
  { id: "archived", label: "Archiviate" },
  { id: "all", label: "Tutte" },
];
const weekdays: Array<{ id: JourneyWeekday; label: string }> = [
  { id: 1, label: "Lun" }, { id: 2, label: "Mar" }, { id: 3, label: "Mer" }, { id: 4, label: "Gio" }, { id: 5, label: "Ven" }, { id: 6, label: "Sab" }, { id: 0, label: "Dom" },
];
const reminderChoices: Array<{ value: JourneyReminderMinutes | null; label: string }> = [
  { value: null, label: "Nessun promemoria" }, { value: 5, label: "5 minuti prima" }, { value: 10, label: "10 minuti prima" }, { value: 15, label: "15 minuti prima" }, { value: 20, label: "20 minuti prima" }, { value: 30, label: "30 minuti prima" }, { value: 45, label: "45 minuti prima" }, { value: 60, label: "60 minuti prima" },
];

function readRoutes(): LocalGuestRoute[] {
  return parseLocalGuestRoutes(localStorage.getItem(LOCAL_GUEST_ROUTES_STORAGE_KEY));
}

function dayStart(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), value.getDate());
}

function agendaDateLabel(value: string) {
  return new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long" }).format(new Date(`${value}T12:00:00`));
}

function agendaTimeLabel(value: string) {
  const time = /^([01]\d|2[0-3]):[0-5]\d$/.test(value) ? (() => { const [hours, minutes] = value.split(":").map(Number); return new Date(1970, 0, 1, hours!, minutes!); })() : new Date(value);
  return Number.isNaN(time.getTime()) ? "—" : new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(time);
}

function agendaCountdownLabel(scheduledAt: string, now: Date) {
  const minutes = Math.ceil((new Date(scheduledAt).getTime() - now.getTime()) / 60_000);
  if (!Number.isFinite(minutes) || minutes < 0) return null;
  if (minutes < 60) return `Tra ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  return remainingMinutes ? `Tra ${hours} h ${remainingMinutes} min` : `Tra ${hours} h`;
}

function isInRange(entry: AgendaEntry, range: AgendaRange, now: Date) {
  if (range === "week") return new Date(entry.scheduledAt).getTime() < dayStart(new Date(now.getFullYear(), now.getMonth(), now.getDate() + 7)).getTime();
  const date = new Date(entry.scheduledAt);
  return date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate();
}

function calendarImportKey(entry: ImportedJourneyCalendarEntry) {
  return [entry.origin, entry.destination, entry.travelDate, entry.travelTime].map(value => value.trim().toLocaleLowerCase("it")).join("|");
}

function JourneySettings({ route, preferences, onChange }: { route: LocalGuestRoute; preferences: JourneyAccessibilityPreferences; onChange: (update: (current: LocalGuestRoute) => LocalGuestRoute) => void }) {
  const initialWeekday = new Date(`${route.travelDate}T12:00:00`).getDay() as JourneyWeekday;
  const [weekdaysValue, setWeekdaysValue] = useState<JourneyWeekday[]>(route.recurrence?.weekdays ?? [initialWeekday]);
  const [time, setTime] = useState(route.recurrence?.time ?? route.travelTime);
  const [occurrences, setOccurrences] = useState(String(route.recurrence?.occurrences ?? 8));
  const [message, setMessage] = useState("");
  const suggestedReminder = suggestedJourneyReminderMinutes(preferences);

  function toggleWeekday(day: JourneyWeekday) {
    setWeekdaysValue(current => current.includes(day) ? current.filter(item => item !== day) : [...current, day].sort((left, right) => left - right));
  }

  function saveRecurrence() {
    const count = Number(occurrences);
    if (weekdaysValue.length === 0 || !/^([01]\d|2[0-3]):[0-5]\d$/.test(time) || !Number.isInteger(count) || count < 1 || count > 12) {
      setMessage("Scegli almeno un giorno, un orario valido e da 1 a 12 occorrenze.");
      return;
    }
    const recurrence: JourneyRecurrence = { schemaVersion: 1, weekdays: weekdaysValue, time, startsOn: route.travelDate, endsOn: null, occurrences: count };
    onChange(current => ({ ...current, recurrence, completedAt: null }));
    setMessage("Ricorrenza salvata solo in questa agenda. Non crea notifiche o eventi automaticamente.");
  }

  function toggleTemplate(templateId: JourneyTemplateId) {
    onChange(current => {
      const isSelected = current.checklistTemplateIds.includes(templateId);
      const checklistTemplateIds = isSelected ? current.checklistTemplateIds.filter(id => id !== templateId) : [...current.checklistTemplateIds, templateId];
      return { ...current, checklistTemplateIds, checklist: mergeChecklistTemplates(current.checklist, checklistTemplateIds) };
    });
  }

  function toggleChecklistItem(itemId: string) {
    onChange(current => ({ ...current, checklist: current.checklist.map(item => item.id === itemId ? { ...item, completed: !item.completed } : item) }));
  }

  return <details className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 dark:border-slate-800 dark:bg-slate-900/30"><summary className="cursor-pointer text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Ripetizioni, promemoria e checklist</summary><div className="mt-3 space-y-4"><p className="text-xs leading-5 text-muted-foreground">Le ripetizioni servono a visualizzare occorrenze nell’agenda. Non inviano dati, non pianificano notifiche di sistema e restano modificabili.</p><fieldset><legend className="text-xs font-medium">Giorni</legend><div className="mt-2 flex flex-wrap gap-2">{weekdays.map(day => <label key={day.id} className="flex min-h-10 items-center gap-1.5 rounded-lg border border-slate-200 bg-background px-2 text-xs font-medium dark:border-slate-700"><input type="checkbox" checked={weekdaysValue.includes(day.id)} onChange={() => toggleWeekday(day.id)} />{day.label}</label>)}</div></fieldset><div className="grid gap-3 sm:grid-cols-2"><label className="text-xs font-medium">Orario<input type="time" value={time} onChange={event => setTime(event.target.value)} className="mt-1 block h-10 w-full rounded-md border border-input bg-background px-3 text-sm" /></label><label className="text-xs font-medium">Numero occorrenze<input type="number" min="1" max="12" value={occurrences} onChange={event => setOccurrences(event.target.value)} className="mt-1 block h-10 w-full rounded-md border border-input bg-background px-3 text-sm" /></label></div><div className="flex flex-wrap gap-2"><Button type="button" size="sm" onClick={saveRecurrence}>{route.recurrence ? "Aggiorna ricorrenza" : "Crea ricorrenza"}</Button>{route.recurrence ? <Button type="button" size="sm" variant="ghost" onClick={() => { onChange(current => ({ ...current, recurrence: null, completedOccurrenceDates: [] })); setMessage("Ricorrenza disattivata. Nessuna occorrenza futura verrà creata."); }}>Disattiva ricorrenza</Button> : null}</div><label className="block text-xs font-medium">Promemoria dell’agenda<select value={route.reminderMinutes ?? ""} onChange={event => { const minutes = event.target.value ? Number(event.target.value) as JourneyReminderMinutes : null; onChange(current => ({ ...current, reminderMinutes: minutes })); }} className="mt-1 block h-10 w-full rounded-md border border-input bg-background px-3 text-sm">{reminderChoices.map(choice => <option key={choice.value ?? "none"} value={choice.value ?? ""}>{choice.label}</option>)}</select></label>{route.reminderMinutes !== suggestedReminder ? <Button type="button" size="sm" variant="outline" onClick={() => { onChange(current => ({ ...current, reminderMinutes: suggestedReminder })); setMessage(`Proposto promemoria ${suggestedReminder} minuti prima. Puoi modificarlo o disattivarlo.`); }}>Usa il margine preferito: {suggestedReminder} min</Button> : <p className="text-xs text-muted-foreground">Il promemoria usa il margine preferito locale. Puoi modificarlo o disattivarlo.</p>}<fieldset><legend className="text-xs font-medium">Modelli checklist</legend><p className="mt-1 text-xs text-muted-foreground">Aggiungi o disattiva un modello. Gli elementi già spuntati non vengono cancellati.</p><div className="mt-2 flex flex-wrap gap-2">{(Object.keys(journeyChecklistTemplates) as JourneyTemplateId[]).map(templateId => <Button key={templateId} type="button" size="sm" variant={route.checklistTemplateIds.includes(templateId) ? "secondary" : "outline"} aria-pressed={route.checklistTemplateIds.includes(templateId)} onClick={() => toggleTemplate(templateId)}>{templateId === "commuter" ? "Pendolare" : templateId === "weekend" ? "Weekend" : templateId === "bike" ? "Bici" : "Accessibilità"}</Button>)}</div></fieldset>{preferences.accessibilityChecklistByDefault && !route.checklistTemplateIds.includes("accessibility") ? <Button type="button" size="sm" variant="outline" onClick={() => toggleTemplate("accessibility")}>Applica checklist accessibilità</Button> : null}{route.checklist.length > 0 ? <ul className="space-y-2" aria-label="Checklist della tratta">{route.checklist.map(item => <li key={item.id}><label className="flex min-h-10 items-center gap-2 rounded-lg border border-slate-200 bg-background px-3 text-sm dark:border-slate-700"><input type="checkbox" checked={item.completed} onChange={() => toggleChecklistItem(item.id)} />{item.label}</label></li>)}</ul> : null}{message ? <p role="status" className="text-xs leading-5 text-blue-900 dark:text-blue-100">{message}</p> : null}</div></details>;
}

export default function Agenda() {
  const [, setLocation] = useLocation();
  const { largeText, toggleLargeText } = useTheme();
  const [routes, setRoutes] = useState<LocalGuestRoute[]>(readRoutes);
  const [filter, setFilter] = useState<AgendaFilter>("upcoming");
  const [range, setRange] = useState<AgendaRange>("week");
  const [now, setNow] = useState(() => new Date());
  const [accessibilityPreferences, setAccessibilityPreferences] = useState<JourneyAccessibilityPreferences>(() => parseJourneyAccessibilityPreferences(localStorage.getItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY)));
  const [sharingMessage, setSharingMessage] = useState("");
  const [sharingPreview, setSharingPreview] = useState("");
  const [calendarImports, setCalendarImports] = useState<ImportedJourneyCalendarEntry[]>([]);
  const [calendarImportSelection, setCalendarImportSelection] = useState<string[]>([]);

  const entries = useMemo(() => deriveAgenda(routes, filter, now).filter(entry => filter === "archived" || isInRange(entry, range, now)), [filter, now, range, routes]);
  const stats = useMemo(() => deriveLocalJourneyStats(routes), [routes]);
  const nextEntry = useMemo(() => deriveAgenda(routes, "upcoming", now)[0] ?? null, [now, routes]);
  const nextRoute = nextEntry ? routes.find(route => route.id === nextEntry.sourceJourneyId) ?? null : null;
  const nextChecklistRemaining = nextRoute?.checklist.filter(item => !item.completed).length ?? 0;

  function persist(next: LocalGuestRoute[]) {
    localStorage.setItem(LOCAL_GUEST_ROUTES_STORAGE_KEY, JSON.stringify(next));
    setRoutes(next);
  }

  function updateRoute(routeId: string, update: (route: LocalGuestRoute) => LocalGuestRoute) {
    persist(routes.map(route => route.id === routeId ? update(route) : route));
  }

  function updateAccessibilityPreferences(update: (current: JourneyAccessibilityPreferences) => JourneyAccessibilityPreferences) {
    const next = update(accessibilityPreferences);
    localStorage.setItem(JOURNEY_ACCESSIBILITY_PREFERENCES_STORAGE_KEY, JSON.stringify(next));
    setAccessibilityPreferences(next);
  }

  function markCompleted(entry: AgendaEntry) {
    updateRoute(entry.sourceJourneyId, route => entry.isGeneratedOccurrence
      ? { ...route, completedOccurrenceDates: Array.from(new Set([...route.completedOccurrenceDates, entry.travelDate])).slice(0, 12) }
      : { ...route, completedAt: new Date().toISOString() });
  }

  function toggleArchive(entry: AgendaEntry) {
    updateRoute(entry.sourceJourneyId, route => ({ ...route, archivedAt: route.archivedAt ? null : new Date().toISOString() }));
  }

  function downloadCalendar(route: LocalGuestRoute) {
    const calendar = journeyToIcs(route);
    if (!calendar) {
      setSharingMessage("La tratta non ha una data e un orario validi per il calendario.");
      return;
    }
    const anchor = document.createElement("a");
    const url = URL.createObjectURL(new Blob([calendar], { type: "text/calendar;charset=utf-8" }));
    anchor.href = url;
    anchor.download = `binario-${route.travelDate}-${route.id}.ics`;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
    setSharingPreview("");
    setSharingMessage("File calendario creato localmente. Scegli tu se e dove importarlo.");
  }

  async function shareSummary(route: LocalGuestRoute) {
    const text = journeyShareText(route);
    setSharingPreview(text);
    if (typeof navigator.share === "function") {
      try {
        await navigator.share({ title: "Binario — viaggio programmato", text });
        setSharingMessage("Hai scelto un canale di condivisione. Binario non conserva né invia il riepilogo da solo.");
      } catch {
        setSharingMessage("Condivisione annullata o non disponibile. Il riepilogo resta visibile solo qui.");
      }
      return;
    }
    setSharingMessage("Riepilogo pronto da copiare o selezionare. Non è stato inviato alcun dato.");
  }

  async function importCalendarFile(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    if (file.size > 32_000) {
      setSharingMessage("Il file calendario supera il limite locale di 32 KB e non è stato importato.");
      return;
    }
    const imported = parseJourneyIcsEntries(await file.text());
    if (imported.length === 0) {
      setSharingMessage("Il file non contiene tratte Binario valide con origine, destinazione, data e ora. Nessun dato è stato salvato.");
      return;
    }
    const existingKeys = routes.map(calendarImportKey);
    const candidates = imported.filter(entry => !existingKeys.includes(calendarImportKey(entry)));
    if (candidates.length === 0) {
      setSharingMessage("Le tratte valide del file sono già presenti in questo browser. Nessun dato è stato modificato.");
      return;
    }
    setCalendarImports(candidates);
    setCalendarImportSelection(candidates.map(calendarImportKey));
    setSharingPreview("");
    setSharingMessage(`${candidates.length} ${candidates.length === 1 ? "tratta pronta" : "tratte pronte"} per l’anteprima. Nulla è stato ancora importato.`);
  }

  function toggleCalendarImportSelection(key: string) {
    setCalendarImportSelection(current => current.includes(key) ? current.filter(value => value !== key) : [...current, key]);
  }

  function confirmCalendarImport() {
    const selected = calendarImports.filter(entry => calendarImportSelection.includes(calendarImportKey(entry)));
    if (selected.length === 0) {
      setSharingMessage("Seleziona almeno una tratta valida oppure annulla l’anteprima. Nessun dato è stato salvato.");
      return;
    }
    const extension = defaultJourneyExtension();
    const checklistTemplateIds = accessibilityPreferences.accessibilityChecklistByDefault ? ["accessibility"] as const : [];
    let nextRoutes = routes;
    for (const imported of selected) {
      const route: LocalGuestRoute = { id: crypto.randomUUID(), origin: imported.origin, destination: imported.destination, originStationCode: null, destinationStationCode: null, travelDate: imported.travelDate, travelTime: imported.travelTime, ...extension, reminderMinutes: accessibilityPreferences.defaultReminderMinutes, checklistTemplateIds: [...checklistTemplateIds], checklist: mergeChecklistTemplates([], checklistTemplateIds) };
      nextRoutes = prependLocalGuestRoute(nextRoutes, route);
    }
    persist(nextRoutes);
    setCalendarImports([]);
    setCalendarImportSelection([]);
    setSharingPreview("");
    setSharingMessage(`${selected.length} ${selected.length === 1 ? "tratta importata" : "tratte importate"} solo in questo browser. Verifica e completa i dati stazione prima di usarle per un tabellone.`);
  }

  return (
    <DashboardLayout>
      <div className="binario-page mx-auto max-w-5xl space-y-6 pb-12">
        <header className="rounded-[1.75rem] bg-slate-950 px-6 py-7 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="max-w-2xl">
              <p className="flex items-center gap-2 text-sm font-medium text-cyan-200"><CalendarDays className="h-4 w-4" aria-hidden="true" /> Agenda locale</p>
              <h1 className="mt-3 text-3xl font-semibold tracking-tight sm:text-4xl">I tuoi viaggi, quando ti servono.</h1>
              <p className="mt-3 text-sm leading-6 text-slate-300">Questa agenda usa soltanto le tratte conservate in questo browser. Il countdown è programmato sul tuo orario; ritardi, binari e cancellazioni non sono dati realtime.</p>
            </div>
            <Button type="button" variant="outline" className="border-slate-600 bg-slate-900 text-slate-50 hover:bg-slate-800 hover:text-white" onClick={() => setLocation("/")}><ArrowLeft className="mr-2 h-4 w-4" />Torna a Binario</Button>
          </div>
        </header>

        <section aria-labelledby="agenda-heading" className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h2 id="agenda-heading" className="text-xl font-semibold">Agenda viaggi</h2><p className="mt-1 text-sm text-muted-foreground">Scegli cosa visualizzare e aggiorna l’elenco senza trasferire dati a servizi esterni.</p></div>
            <Button type="button" variant="outline" size="sm" onClick={() => setNow(new Date())}><Clock3 className="mr-2 h-4 w-4" />Aggiorna orario</Button>
          </div>

          {calendarImports.length > 0 ? <Card className="border-blue-200 bg-blue-50/60 dark:border-blue-900 dark:bg-blue-950/20" aria-labelledby="calendar-import-heading"><CardHeader className="pb-3"><CardTitle id="calendar-import-heading" className="text-base">Anteprima importazione calendario</CardTitle><CardDescription>Scegli le tratte da aggiungere. L’importazione è additiva e non sovrascrive l’archivio esistente.</CardDescription></CardHeader><CardContent className="space-y-3"><ul className="space-y-2" aria-label="Tratte calendario da importare">{calendarImports.map(entry => { const key = calendarImportKey(entry); const selected = calendarImportSelection.includes(key); return <li key={key}><label className="flex min-h-10 items-center gap-2 rounded-lg border border-blue-100 bg-background px-3 text-sm dark:border-blue-950"><input type="checkbox" checked={selected} onChange={() => toggleCalendarImportSelection(key)} />{entry.origin} <span aria-hidden="true">→</span> <span className="sr-only">verso</span> {entry.destination}<span className="text-xs text-muted-foreground">· {agendaDateLabel(entry.travelDate)} · {entry.travelTime}</span></label></li>; })}</ul><div className="flex flex-wrap gap-2"><Button type="button" size="sm" onClick={confirmCalendarImport}>Importa selezionate</Button><Button type="button" size="sm" variant="outline" onClick={() => { setCalendarImports([]); setCalendarImportSelection([]); setSharingMessage("Importazione annullata. Nessuna tratta è stata aggiunta."); }}>Annulla</Button></div></CardContent></Card> : null}

          {nextEntry && nextRoute ? <Card className="border-cyan-200 bg-cyan-50/60 dark:border-cyan-900 dark:bg-cyan-950/20" aria-labelledby="ready-heading"><CardHeader className="pb-3"><CardTitle id="ready-heading" className="text-base">Pronto per partire</CardTitle><CardDescription>La prossima tratta salvata in questo browser. I dati ferroviari realtime non sono disponibili.</CardDescription></CardHeader><CardContent className="space-y-3"><div><p className="font-semibold">{nextEntry.origin} <span aria-hidden="true">→</span> <span className="sr-only">verso</span> {nextEntry.destination}</p><p className="mt-1 text-sm text-muted-foreground">{agendaDateLabel(nextEntry.travelDate)} · {agendaTimeLabel(nextEntry.scheduledAt)}{agendaCountdownLabel(nextEntry.scheduledAt, now) ? ` · ${agendaCountdownLabel(nextEntry.scheduledAt, now)}` : ""}</p></div><dl className="grid gap-2 text-sm sm:grid-cols-2"><div className="rounded-lg border border-cyan-100 bg-background/70 p-3 dark:border-cyan-950"><dt className="text-xs text-muted-foreground">Promemoria programmato</dt><dd className="mt-1 font-medium">{nextEntry.reminderMinutes ? `${nextEntry.reminderMinutes} minuti prima` : "Non impostato"}</dd></div><div className="rounded-lg border border-cyan-100 bg-background/70 p-3 dark:border-cyan-950"><dt className="text-xs text-muted-foreground">Checklist viaggio</dt><dd className="mt-1 font-medium">{nextChecklistRemaining === 0 ? "Completata" : `${nextChecklistRemaining} voci da preparare`}</dd></div></dl><div className="flex flex-wrap gap-2"><Button type="button" size="sm" variant="outline" onClick={() => setFilter("upcoming")}><Check className="mr-1.5 h-4 w-4" />Apri agenda</Button><Button type="button" size="sm" variant="outline" onClick={() => downloadCalendar(nextRoute)}><Download className="mr-1.5 h-4 w-4" />Scarica .ics</Button><Button type="button" size="sm" variant="outline" onClick={() => void shareSummary(nextRoute)}><Send className="mr-1.5 h-4 w-4" />Condividi riepilogo</Button></div><p className="text-xs leading-5 text-muted-foreground">Countdown e promemoria sono programmati localmente: non attestano ritardi, binari, cancellazioni o la partenza effettiva.</p></CardContent></Card> : null}

          <LocalItineraryCard routes={routes} minimumTransferMinutes={accessibilityPreferences.minimumTransferMinutes} />

          <GuidedTextJourneyImportCard routes={routes} onImport={drafts => {
            const extension = defaultJourneyExtension();
            const checklistTemplateIds = accessibilityPreferences.accessibilityChecklistByDefault ? ["accessibility"] as const : [];
            let nextRoutes = routes;
            for (const draft of drafts) nextRoutes = prependLocalGuestRoute(nextRoutes, { id: crypto.randomUUID(), ...draft, originStationCode: null, destinationStationCode: null, ...extension, reminderMinutes: accessibilityPreferences.defaultReminderMinutes, checklistTemplateIds: [...checklistTemplateIds], checklist: mergeChecklistTemplates([], checklistTemplateIds) });
            persist(nextRoutes);
          }} />

          <div className="flex flex-wrap gap-2" role="group" aria-label="Filtro agenda">
            {agendaFilters.map(option => <Button key={option.id} type="button" size="sm" variant={filter === option.id ? "default" : "outline"} aria-pressed={filter === option.id} onClick={() => setFilter(option.id)}>{option.label}</Button>)}
          </div>
          {filter !== "archived" ? <div className="flex flex-wrap gap-2" role="group" aria-label="Intervallo agenda"><Button type="button" size="sm" variant={range === "day" ? "secondary" : "ghost"} aria-pressed={range === "day"} onClick={() => setRange("day")}>Oggi</Button><Button type="button" size="sm" variant={range === "week" ? "secondary" : "ghost"} aria-pressed={range === "week"} onClick={() => setRange("week")}>Prossimi 7 giorni</Button></div> : null}

          <Card><CardHeader className="pb-3"><CardTitle className="text-base">Preferenze viaggio accessibile</CardTitle><CardDescription>Impostazioni locali, reversibili e senza profilazione. Non descrivono disponibilità o assistenza del vettore.</CardDescription></CardHeader><CardContent className="grid gap-4 sm:grid-cols-2"><label className="text-sm font-medium">Margine minimo che preferisci<select value={accessibilityPreferences.minimumTransferMinutes} onChange={event => updateAccessibilityPreferences(current => ({ ...current, minimumTransferMinutes: Number(event.target.value) as JourneyAccessibilityPreferences["minimumTransferMinutes"] }))} className="mt-1.5 block h-10 w-full rounded-md border border-input bg-background px-3 text-sm">{reminderChoices.slice(1).map(choice => <option key={choice.value} value={choice.value!}>{choice.value} minuti</option>)}</select></label><label className="text-sm font-medium">Promemoria predefinito per nuove tratte<select value={accessibilityPreferences.defaultReminderMinutes ?? ""} onChange={event => updateAccessibilityPreferences(current => ({ ...current, defaultReminderMinutes: event.target.value ? Number(event.target.value) as JourneyReminderMinutes : null }))} className="mt-1.5 block h-10 w-full rounded-md border border-input bg-background px-3 text-sm">{reminderChoices.map(choice => <option key={choice.value ?? "none"} value={choice.value ?? ""}>{choice.label}</option>)}</select></label><label className="flex min-h-10 items-center gap-2 text-sm"><input type="checkbox" checked={accessibilityPreferences.accessibilityChecklistByDefault} onChange={event => updateAccessibilityPreferences(current => ({ ...current, accessibilityChecklistByDefault: event.target.checked }))} />Propone la checklist accessibilità nelle nuove tratte</label><Button type="button" variant="outline" aria-pressed={largeText} onClick={toggleLargeText}>{largeText ? "Riduci testo dell’app" : "Usa testo molto grande"}</Button></CardContent></Card>

          <Card><CardHeader className="pb-3"><CardTitle className="text-base">Archivio locale in numeri</CardTitle><CardDescription>Conteggi calcolati in questo browser. Nessuna telemetria, classifica tra utenti o inferenza sulle tue abitudini.</CardDescription></CardHeader><CardContent className="space-y-4"><dl className="grid grid-cols-2 gap-3 sm:grid-cols-4"><div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-900/40"><dt className="text-xs text-muted-foreground">Tratte attive</dt><dd className="mt-1 text-2xl font-semibold tabular-nums">{stats.active}</dd></div><div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-900/40"><dt className="text-xs text-muted-foreground">Archiviate</dt><dd className="mt-1 text-2xl font-semibold tabular-nums">{stats.archived}</dd></div><div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-900/40"><dt className="text-xs text-muted-foreground">Completamenti</dt><dd className="mt-1 text-2xl font-semibold tabular-nums">{stats.completed}</dd></div><div className="rounded-xl bg-slate-50 p-3 dark:bg-slate-900/40"><dt className="text-xs text-muted-foreground">Con promemoria</dt><dd className="mt-1 text-2xl font-semibold tabular-nums">{stats.remindersConfigured}</dd></div></dl><div><h3 className="text-sm font-medium">Destinazioni più ricorrenti</h3>{stats.topDestinations.length > 0 ? <ol className="mt-2 space-y-1 text-sm">{stats.topDestinations.map(item => <li key={item.destination} className="flex justify-between gap-3"><span className="truncate">{item.destination}</span><span className="tabular-nums text-muted-foreground">{item.count} {item.count === 1 ? "tratta" : "tratte"}</span></li>)}</ol> : <p className="mt-1 text-sm text-muted-foreground">Ancora nessuna tratta salvata in questo browser.</p>}</div><p className="text-xs leading-5 text-muted-foreground">Completamenti con promemoria configurato: {stats.remindersCompleted}. Il valore non attesta la consegna di alcuna notifica.</p></CardContent></Card>

          <Card><CardHeader className="pb-3"><CardTitle className="text-base">Condivisione e calendario</CardTitle><CardDescription>Genera un riepilogo oppure importa/esporta un file ICS soltanto su tua richiesta. Note personali, token, audio e stato realtime non vengono inclusi.</CardDescription></CardHeader><CardContent className="space-y-3"><label className="inline-flex min-h-10 cursor-pointer items-center gap-2 rounded-md border border-input bg-background px-3 text-sm font-medium"><FileUp className="h-4 w-4" />Importa file .ics<input type="file" accept=".ics,text/calendar" className="sr-only" onChange={importCalendarFile} /></label>{routes.filter(route => !route.archivedAt).map(route => <div key={route.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 p-3 dark:border-slate-800"><p className="text-sm font-medium">{route.origin} <span aria-hidden="true">→</span> {route.destination}<span className="mt-0.5 block text-xs font-normal text-muted-foreground">{route.travelDate} · {route.travelTime} · programmato, non realtime</span></p><div className="flex flex-wrap gap-2"><Button type="button" size="sm" variant="outline" onClick={() => shareSummary(route)}><Send className="mr-1.5 h-4 w-4" />Riepilogo</Button><Button type="button" size="sm" variant="outline" onClick={() => downloadCalendar(route)}><Download className="mr-1.5 h-4 w-4" />Scarica .ics</Button></div></div>)}{routes.filter(route => !route.archivedAt).length === 0 ? <p className="text-sm text-muted-foreground">Aggiungi una tratta per creare un riepilogo o un calendario.</p> : null}{sharingPreview ? <textarea aria-label="Riepilogo del viaggio pronto per la condivisione" readOnly value={sharingPreview} className="min-h-24 w-full rounded-md border border-input bg-background p-3 text-sm" /> : null}{sharingMessage ? <p role="status" className="text-sm leading-6 text-blue-900 dark:text-blue-100">{sharingMessage}</p> : null}</CardContent></Card>

          {routes.filter(route => !route.archivedAt).length > 0 ? <Card><CardHeader className="pb-3"><CardTitle className="text-base">Organizza le tue abitudini</CardTitle><CardDescription>Configura manualmente ripetizioni e promemoria su ogni tratta. Binario non attiva automazioni nascoste.</CardDescription></CardHeader><CardContent className="space-y-3">{routes.filter(route => !route.archivedAt).map(route => <div key={route.id} className="rounded-xl border border-slate-200 p-3 dark:border-slate-800"><p className="mb-2 text-sm font-semibold">{route.origin} <span aria-hidden="true">→</span> {route.destination}</p><JourneySettings route={route} preferences={accessibilityPreferences} onChange={update => updateRoute(route.id, update)} /></div>)}</CardContent></Card> : null}

          <p role="status" className="sr-only">{entries.length} {entries.length === 1 ? "viaggio visualizzato" : "viaggi visualizzati"} nell’agenda.</p>
          {entries.length === 0 ? <Card className="border-dashed"><CardContent className="p-6"><CalendarDays className="mb-3 h-5 w-5 text-blue-700" /><p className="font-semibold">Nessun viaggio in questa vista.</p><p className="mt-1 text-sm leading-6 text-muted-foreground">Aggiungi una tratta dalla schermata Binario oppure modifica filtro e intervallo. Nulla viene creato automaticamente.</p><Button type="button" className="mt-4" onClick={() => setLocation("/")}>Pianifica una tratta</Button></CardContent></Card> : null}

          <div className="space-y-3">
            {entries.map(entry => <article key={`${entry.sourceJourneyId}-${entry.travelDate}-${entry.travelTime}`} className="rounded-2xl border border-slate-200 bg-card p-4 text-card-foreground shadow-sm dark:border-slate-800">
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div className="min-w-0">
                  <p className="text-xs font-semibold uppercase tracking-[0.12em] text-blue-700">{agendaDateLabel(entry.travelDate)} · {agendaTimeLabel(entry.scheduledAt)}</p>
                  <h3 className="mt-1 text-lg font-semibold">{entry.origin} <span aria-hidden="true">→</span> <span className="sr-only">verso</span> {entry.destination}</h3>
                  <div className="mt-2 flex flex-wrap gap-2"><Badge variant="outline" className="border-violet-200 text-violet-800 dark:text-violet-200">Programmato, non realtime</Badge>{entry.isGeneratedOccurrence ? <Badge variant="outline">Ricorrenza</Badge> : null}{entry.reminderMinutes ? <Badge variant="outline" className="border-blue-200 text-blue-800 dark:text-blue-200"><BellRing className="mr-1 h-3.5 w-3.5" />Promemoria {entry.reminderMinutes} min</Badge> : null}</div>
                </div>
                <div className="flex flex-wrap gap-2"><Button type="button" size="sm" variant="outline" onClick={() => markCompleted(entry)}><Check className="mr-1.5 h-4 w-4" />Completata</Button><Button type="button" size="sm" variant="ghost" onClick={() => toggleArchive(entry)}>{entry.archivedAt ? <><RotateCcw className="mr-1.5 h-4 w-4" />Ripristina</> : <><Archive className="mr-1.5 h-4 w-4" />Archivia</>}</Button></div>
              </div>
            </article>)}
          </div>
        </section>
      </div>
    </DashboardLayout>
  );
}
