import DashboardLayout from "@/components/DashboardLayout";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { AlertTriangle, CalendarClock, CheckCircle2, CircleSlash2, ShieldCheck } from "lucide-react";

const states = [
  { title: "Programmato", badge: "Contesto personale", icon: CalendarClock, tone: "border-violet-200 bg-violet-50 text-violet-950", description: "Data, ora e promemoria scelti da te per organizzarti. Non descrivono l’orario effettivo del treno e non sono realtime." },
  { title: "Realtime verificato", badge: "Solo con fonte autorizzata", icon: CheckCircle2, tone: "border-emerald-200 bg-emerald-50 text-emerald-950", description: "Potrà comparire soltanto quando una fonte autorizzata, identificata e ancora entro scadenza fornirà uno snapshot coerente." },
  { title: "Scaduto o in conflitto", badge: "Soppresso", icon: AlertTriangle, tone: "border-amber-200 bg-amber-50 text-amber-950", description: "Un aggiornamento oltre TTL, incoerente o non sufficientemente affidabile non viene mostrato come dato operativo e non genera avvisi." },
  { title: "Non disponibile", badge: "Fail-closed", icon: CircleSlash2, tone: "border-slate-200 bg-slate-50 text-slate-900", description: "Binario non dispone di un dato idoneo. Preferisce dichiararlo apertamente invece di stimare ritardi, binari, cancellazioni o posizione." },
] as const;

export default function DataGuide() {
  return <DashboardLayout><main className="binario-page mx-auto max-w-4xl space-y-6 pb-12">
    <header className="rounded-[1.75rem] bg-slate-950 px-6 py-8 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8"><p className="text-sm font-medium text-cyan-200">Guida di affidabilità</p><h1 className="mt-2 text-3xl font-semibold tracking-tight">Come Binario presenta i dati</h1><p className="mt-3 max-w-2xl text-sm leading-6 text-slate-300">Gli stati servono a distinguere ciò che hai scelto per il viaggio da ciò che una fonte ferroviaria potrà confermare. Non esistono scorciatoie: un dato non idoneo resta indisponibile.</p></header>
    <section className="grid gap-4 sm:grid-cols-2" aria-label="Significato degli stati dati">{states.map(({ title, badge, icon: Icon, tone, description }) => <Card key={title} className={`border ${tone}`}><CardHeader className="pb-2"><div className="flex items-start justify-between gap-3"><Icon className="h-5 w-5" aria-hidden="true" /><Badge variant="outline" className="border-current bg-transparent text-current">{badge}</Badge></div><CardTitle className="mt-3">{title}</CardTitle></CardHeader><CardContent><CardDescription className="leading-6 text-current/80">{description}</CardDescription></CardContent></Card>)}</section>
    <Card><CardHeader><CardTitle className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-blue-700" />Cosa condividono i companion</CardTitle><CardDescription>App, widget, smartwatch, Alexa e notifiche usano lo stesso snapshot valutato dal core Binario.</CardDescription></CardHeader><CardContent className="space-y-2 text-sm leading-6 text-muted-foreground"><p>Provider, identificativi, osservazione, scadenza, confidenza, natura del dato e fallback sono identici su tutte le superfici.</p><p>Un companion non conserva un proprio stato ferroviario e non può trasformare una cache locale, un promemoria o un errore di rete in realtime apparente.</p></CardContent></Card>
  </main></DashboardLayout>;
}
