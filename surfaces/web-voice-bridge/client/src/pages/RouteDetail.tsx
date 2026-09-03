import DashboardLayout from "@/components/DashboardLayout";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { parsePreDepartureChecklist, PreDepartureChecklistCard, type PreDepartureChecklistItem } from "@/components/PreDepartureChecklistCard";
import { trpc } from "@/lib/trpc";
import { Archive, ArrowDownUp, ArrowLeft, CalendarClock, Copy, RotateCcw, Save, ShieldCheck } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { useLocation, useRoute } from "wouter";

type RouteForm = {
  origin: string;
  originStationCode: string | null;
  destination: string;
  destinationStationCode: string | null;
  name: string;
  isPinned: boolean;
  travelDate: string;
  travelTime: string;
  travelProfile: "commuter" | "occasional" | "accessibility";
  plannedReminderMinutes: string;
  personalNote: string;
  accessibilityNote: string;
  personalTags: string;
  preDepartureChecklist: PreDepartureChecklistItem[];
};

type PersonalRouteRecord = {
  id: number;
  origin: string;
  originStationCode: string | null;
  destination: string;
  destinationStationCode: string | null;
  name: string;
  isPinned: boolean;
  travelDate: string | null;
  travelTime: string | null;
  travelProfile: RouteForm["travelProfile"];
  plannedReminderMinutes: number | null;
  personalNote: string | null;
  accessibilityNote: string | null;
  personalTagsJson: string;
  preDepartureChecklistJson: string;
  archivedAt: Date | string | null;
};

function tagsToInput(value: string) {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) && parsed.every(tag => typeof tag === "string") ? parsed.join(", ") : "";
  } catch { return ""; }
}

function toForm(route: PersonalRouteRecord): RouteForm {
  return {
    origin: route.origin,
    originStationCode: route.originStationCode,
    destination: route.destination,
    destinationStationCode: route.destinationStationCode,
    name: route.name,
    isPinned: route.isPinned,
    travelDate: route.travelDate ?? "",
    travelTime: route.travelTime ?? "",
    travelProfile: route.travelProfile,
    plannedReminderMinutes: route.plannedReminderMinutes?.toString() ?? "",
    personalNote: route.personalNote ?? "",
    accessibilityNote: route.accessibilityNote ?? "",
    personalTags: tagsToInput(route.personalTagsJson),
    preDepartureChecklist: parsePreDepartureChecklist(route.preDepartureChecklistJson),
  };
}

const initialForm: RouteForm = { origin: "", originStationCode: null, destination: "", destinationStationCode: null, name: "", isPinned: false, travelDate: "", travelTime: "", travelProfile: "occasional", plannedReminderMinutes: "", personalNote: "", accessibilityNote: "", personalTags: "", preDepartureChecklist: [] };

export default function RouteDetail() {
  const [, setLocation] = useLocation();
  const [, params] = useRoute("/viaggi/:id");
  const routeId = Number(params?.id);
  const validRouteId = Number.isSafeInteger(routeId) && routeId > 0;
  const input = useMemo(() => ({ id: routeId }), [routeId]);
  const utils = trpc.useUtils();
  const routeQuery = trpc.routes.get.useQuery(input, { enabled: validRouteId });
  const [form, setForm] = useState<RouteForm>(initialForm);
  const [loadedRouteId, setLoadedRouteId] = useState<number | null>(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    if (routeQuery.data && routeQuery.data.id !== loadedRouteId) {
      setForm(toForm(routeQuery.data as PersonalRouteRecord));
      setLoadedRouteId(routeQuery.data.id);
    }
  }, [loadedRouteId, routeQuery.data]);

  const update = trpc.routes.update.useMutation({ onSuccess: data => { utils.routes.get.invalidate({ id: data.id }); utils.routes.list.invalidate(); setNotice("Preferenze della tratta aggiornate."); } });
  const duplicate = trpc.routes.duplicate.useMutation({ onSuccess: data => { utils.routes.list.invalidate(); setLocation(`/viaggi/${data.id}`); } });
  const invert = trpc.routes.invert.useMutation({ onSuccess: data => { utils.routes.get.invalidate({ id: data.id }); utils.routes.list.invalidate(); setNotice("Partenza e arrivo sono stati invertiti."); } });
  const archive = trpc.routes.archive.useMutation({ onSuccess: () => { utils.routes.list.invalidate(); setLocation("/"); } });
  const restore = trpc.routes.restore.useMutation({ onSuccess: () => { utils.routes.get.invalidate({ id: routeId }); utils.routes.list.invalidate(); setNotice("Tratta ripristinata. Ora puoi modificarla."); routeQuery.refetch(); } });

  function updateText(field: keyof RouteForm, value: string) {
    setForm(current => ({ ...current, [field]: value, ...(field === "origin" ? { originStationCode: null } : {}), ...(field === "destination" ? { destinationStationCode: null } : {}) }));
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!validRouteId) return;
    const reminder = form.plannedReminderMinutes.trim();
    update.mutate({
      id: routeId,
      origin: form.origin,
      originStationCode: form.originStationCode,
      destination: form.destination,
      destinationStationCode: form.destinationStationCode,
      name: form.name,
      isPinned: form.isPinned,
      travelDate: form.travelDate || null,
      travelTime: form.travelTime || null,
      travelProfile: form.travelProfile,
      plannedReminderMinutes: reminder === "" ? null : Number(reminder),
      personalNote: form.personalNote || null,
      accessibilityNote: form.accessibilityNote || null,
      personalTags: form.personalTags.split(",").map(tag => tag.trim()).filter(Boolean),
      preDepartureChecklist: form.preDepartureChecklist,
    });
  }

  if (!validRouteId) return <DashboardLayout><main className="mx-auto max-w-3xl p-6"><Card><CardHeader><CardTitle>Tratta non valida</CardTitle><CardDescription>Apri una tratta dall’elenco personale per gestirne le preferenze.</CardDescription></CardHeader><CardContent><Button onClick={() => setLocation("/")}><ArrowLeft className="mr-2 h-4 w-4" />Torna ai viaggi</Button></CardContent></Card></main></DashboardLayout>;
  const route = routeQuery.data as PersonalRouteRecord | null | undefined;
  const archived = Boolean(route?.archivedAt);

  return <DashboardLayout><main className="binario-page mx-auto max-w-4xl space-y-6 pb-12">
    <header className="relative overflow-hidden rounded-[1.75rem] bg-slate-950 px-6 py-7 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8">
      <div className="pointer-events-none absolute inset-y-0 right-0 w-2/5 bg-[linear-gradient(135deg,transparent_10%,rgba(34,211,238,0.13)_10%,transparent_11%,transparent_23%,rgba(59,130,246,0.18)_23%,transparent_24%)]" />
      <div className="relative"><Button variant="ghost" className="-ml-3 mb-5 text-cyan-100 hover:bg-white/10 hover:text-white" onClick={() => setLocation("/")}><ArrowLeft className="mr-2 h-4 w-4" />Torna ai viaggi</Button><div className="flex flex-wrap items-center gap-3"><p className="text-sm font-medium text-cyan-200">Tratta personale</p>{archived ? <Badge className="bg-amber-300 text-amber-950">Archiviata</Badge> : <Badge className="bg-cyan-400 text-slate-950">Privata</Badge>}</div><h1 className="mt-2 text-3xl font-semibold tracking-tight">{route?.name ?? "Carico la tratta…"}</h1><p className="mt-2 text-sm text-slate-300">Le note, il profilo e l’anticipo sono tue preferenze. Non descrivono lo stato operativo del treno.</p></div>
    </header>
    {routeQuery.isLoading ? <Card><CardContent className="p-6 text-sm text-muted-foreground">Caricamento tratta…</CardContent></Card> : null}
    {routeQuery.error || (!routeQuery.isLoading && !route) ? <Card><CardHeader><CardTitle>Tratta non disponibile</CardTitle><CardDescription>Potrebbe essere stata rimossa oppure non appartenere al tuo account.</CardDescription></CardHeader><CardContent><Button onClick={() => setLocation("/")}>Torna ai viaggi</Button></CardContent></Card> : null}
    {route ? <form onSubmit={submit} className="space-y-6" aria-describedby="route-data-notice">
      <Card className="border-slate-200 shadow-sm"><CardHeader><CardTitle>Dettaglio del viaggio</CardTitle><CardDescription>Modifica il tuo riferimento personale. Se cambi il nome di una stazione, il codice canonico viene rimosso finché non la selezioni di nuovo dal catalogo nella Home.</CardDescription></CardHeader><CardContent className="space-y-5">
        <div className="grid gap-4 sm:grid-cols-[1fr_auto_1fr] sm:items-end"><div className="space-y-1.5"><Label htmlFor="detail-origin">Partenza</Label><Input id="detail-origin" value={form.origin} disabled={archived} onChange={event => updateText("origin", event.target.value)} maxLength={120} /></div><Button type="button" variant="outline" size="icon" disabled={archived || invert.isPending} onClick={() => invert.mutate({ id: route.id })} aria-label="Inverti partenza e arrivo"><ArrowDownUp className="h-4 w-4" /></Button><div className="space-y-1.5"><Label htmlFor="detail-destination">Arrivo</Label><Input id="detail-destination" value={form.destination} disabled={archived} onChange={event => updateText("destination", event.target.value)} maxLength={120} /></div></div>
        <div className="grid gap-4 sm:grid-cols-2"><div className="space-y-1.5"><Label htmlFor="detail-name">Nome tratta</Label><Input id="detail-name" value={form.name} disabled={archived} onChange={event => updateText("name", event.target.value)} maxLength={120} /></div><div className="space-y-1.5"><Label htmlFor="detail-profile">Profilo d’uso</Label><select id="detail-profile" value={form.travelProfile} disabled={archived} onChange={event => setForm(current => ({ ...current, travelProfile: event.target.value as RouteForm["travelProfile"] }))} className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><option value="occasional">Viaggio occasionale</option><option value="commuter">Pendolare</option><option value="accessibility">Assistenza mobilità</option></select></div></div>
        <div className="grid gap-4 sm:grid-cols-3"><div className="space-y-1.5"><Label htmlFor="detail-date">Data scelta</Label><Input id="detail-date" type="date" disabled={archived} value={form.travelDate} onChange={event => updateText("travelDate", event.target.value)} /></div><div className="space-y-1.5"><Label htmlFor="detail-time">Ora scelta</Label><Input id="detail-time" type="time" disabled={archived} value={form.travelTime} onChange={event => updateText("travelTime", event.target.value)} /></div><div className="space-y-1.5"><Label htmlFor="detail-reminder">Anticipo promemoria</Label><Input id="detail-reminder" type="number" min="0" max="1440" inputMode="numeric" disabled={archived} value={form.plannedReminderMinutes} onChange={event => updateText("plannedReminderMinutes", event.target.value)} placeholder="Minuti" /></div></div>
        <div className="rounded-xl border border-cyan-100 bg-cyan-50/50 p-3 text-xs leading-5 text-cyan-950"><CalendarClock className="mr-1.5 inline h-3.5 w-3.5" /><strong>Solo programmato.</strong> L’anticipo potrà essere usato da una notifica locale sul tuo dispositivo dopo la conferma esplicita. Non è un avviso realtime né prova un orario ferroviario.</div>
      </CardContent></Card>
      <Card className="border-slate-200 shadow-sm"><CardHeader><CardTitle>Note e organizzazione</CardTitle><CardDescription>Informazioni scritte da te, private e cancellabili. Non vengono dedotte da posizione, abitudini o fonti ferroviarie.</CardDescription></CardHeader><CardContent className="space-y-4"><div className="space-y-1.5"><Label htmlFor="detail-tags">Tag personali</Label><Input id="detail-tags" disabled={archived} value={form.personalTags} onChange={event => updateText("personalTags", event.target.value)} maxLength={170} placeholder="Es. lavoro, mattina" aria-describedby="detail-tags-help" /><p id="detail-tags-help" className="text-xs text-muted-foreground">Fino a 5 tag di massimo 32 caratteri, separati da virgole. Restano solo nel tuo archivio.</p></div><div className="space-y-1.5"><Label htmlFor="detail-note">Nota per questa tratta</Label><Textarea id="detail-note" disabled={archived} value={form.personalNote} onChange={event => updateText("personalNote", event.target.value)} maxLength={1000} rows={4} placeholder="Es. incontro all’uscita esterna" /></div><div className="space-y-1.5"><Label htmlFor="detail-accessibility">Nota accessibilità</Label><Textarea id="detail-accessibility" disabled={archived} value={form.accessibilityNote} onChange={event => updateText("accessibilityNote", event.target.value)} maxLength={500} rows={3} placeholder="Es. necessità personali da verificare prima della partenza" /></div></CardContent></Card>
      <PreDepartureChecklistCard value={form.preDepartureChecklist} onChange={preDepartureChecklist => setForm(current => ({ ...current, preDepartureChecklist }))} disabled={archived} />
      <div className="flex flex-wrap items-center justify-between gap-3"><p id="route-data-notice" className="flex max-w-xl gap-2 text-xs leading-5 text-muted-foreground"><ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-blue-700" />Questi dati restano nel tuo archivio Binario e sono disponibili per esportazione o eliminazione nel controllo dati.</p><div className="flex flex-wrap gap-2">{archived ? <Button type="button" onClick={() => restore.mutate({ id: route.id })} disabled={restore.isPending}><RotateCcw className="mr-2 h-4 w-4" />{restore.isPending ? "Ripristino…" : "Ripristina"}</Button> : <><Button type="button" variant="outline" onClick={() => duplicate.mutate({ id: route.id })} disabled={duplicate.isPending}><Copy className="mr-2 h-4 w-4" />Duplica</Button><Button type="button" variant="outline" onClick={() => archive.mutate({ id: route.id })} disabled={archive.isPending}><Archive className="mr-2 h-4 w-4" />Archivia</Button><Button type="submit" disabled={update.isPending}><Save className="mr-2 h-4 w-4" />{update.isPending ? "Salvataggio…" : "Salva modifiche"}</Button></>}</div></div>
      {notice ? <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">{notice}</p> : null}{update.error ? <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-800">La tratta non è stata modificata. Verifica i campi e riprova.</p> : null}
    </form> : null}
  </main></DashboardLayout>;
}
