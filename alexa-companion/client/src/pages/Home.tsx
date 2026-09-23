import DashboardLayout from "@/components/DashboardLayout";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { trpc } from "@/lib/trpc";
import { Bot, ChevronRight, CircleAlert, Link2Off, MapPin, Pin, Plus, Radio, Trash2, TrainFront } from "lucide-react";
import { FormEvent, useState } from "react";

export default function Home() {
  const utils = trpc.useUtils();
  const routes = trpc.routes.list.useQuery();
  const voice = trpc.voice.status.useQuery();
  const [origin, setOrigin] = useState("");
  const [destination, setDestination] = useState("");
  const [name, setName] = useState("");
  const [isPinned, setIsPinned] = useState(false);
  const createRoute = trpc.routes.create.useMutation({ onSuccess: () => { utils.routes.list.invalidate(); setOrigin(""); setDestination(""); setName(""); setIsPinned(false); } });
  const setPinned = trpc.routes.setPinned.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const removeRoute = trpc.routes.remove.useMutation({ onSuccess: () => utils.routes.list.invalidate() });
  const unlink = trpc.voice.unlink.useMutation({ onSuccess: () => voice.refetch() });

  function submitRoute(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!origin.trim() || !destination.trim() || !name.trim()) return;
    createRoute.mutate({ origin, destination, name, isPinned });
  }

  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl space-y-6 pb-12">
        <header className="rounded-3xl bg-slate-950 px-6 py-7 text-slate-50 shadow-xl shadow-slate-950/15 sm:px-8">
          <div className="flex flex-col gap-5 md:flex-row md:items-start md:justify-between">
            <div className="max-w-2xl">
              <div className="mb-3 flex items-center gap-2 text-sm font-medium text-cyan-200"><TrainFront className="h-4 w-4" /> Binario Alexa Companion</div>
              <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Collega Alexa alla tua app Binario.</h1>
              <p className="mt-3 max-w-xl text-sm leading-6 text-slate-300">Questa è una superficie tecnica di collegamento: l’app smartphone resta il prodotto principale e la fonte delle tue tratte. Alexa accede ai dati personali solo dopo un consenso verificato.</p>
            </div>
            <Badge className="w-fit bg-amber-300 text-amber-950 hover:bg-amber-300">Dati live non collegati</Badge>
          </div>
        </header>

        <div className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader className="pb-4">
              <div className="flex items-center justify-between gap-4"><div><CardTitle>Tratte personali</CardTitle><CardDescription className="mt-1">Conservate nel tuo profilo Binario. Nessuna cronologia di viaggio o posizione viene registrata.</CardDescription></div><MapPin className="h-5 w-5 text-cyan-700" /></div>
            </CardHeader>
            <CardContent className="space-y-5">
              <form onSubmit={submitRoute} className="grid gap-3 rounded-2xl bg-slate-50 p-4 sm:grid-cols-2">
                <div className="space-y-1.5"><Label htmlFor="route-name">Nome</Label><Input id="route-name" value={name} onChange={event => setName(event.target.value)} placeholder="Casa — lavoro" maxLength={120} required /></div>
                <div className="space-y-1.5"><Label htmlFor="route-origin">Origine</Label><Input id="route-origin" value={origin} onChange={event => setOrigin(event.target.value)} placeholder="Roma Termini" maxLength={120} required /></div>
                <div className="space-y-1.5"><Label htmlFor="route-destination">Destinazione</Label><Input id="route-destination" value={destination} onChange={event => setDestination(event.target.value)} placeholder="Tivoli" maxLength={120} required /></div>
                <div className="flex items-end justify-between gap-3"><Label htmlFor="route-pin" className="flex cursor-pointer items-center gap-2 pb-2 text-sm font-normal"><Switch id="route-pin" checked={isPinned} onCheckedChange={setIsPinned} /> Fissa la tratta</Label><Button type="submit" disabled={createRoute.isPending}><Plus className="mr-2 h-4 w-4" />Salva</Button></div>
              </form>
              <div className="space-y-2" aria-live="polite">
                {routes.isLoading ? <p className="text-sm text-muted-foreground">Caricamento tratte…</p> : null}
                {routes.data?.length === 0 ? <p className="rounded-xl border border-dashed p-4 text-sm text-muted-foreground">Non hai ancora salvato tratte. Aggiungine una per preparare le future richieste vocali.</p> : null}
                {routes.data?.map(route => <div key={route.id} className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
                  <button onClick={() => setPinned.mutate({ id: route.id, isPinned: !route.isPinned })} className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 focus-visible:ring-2 focus-visible:ring-cyan-700" aria-label={route.isPinned ? `Rimuovi ${route.name} dalle tratte fissate` : `Fissa ${route.name}`}><Pin className={`h-4 w-4 ${route.isPinned ? "fill-cyan-600 text-cyan-700" : ""}`} /></button>
                  <div className="min-w-0 flex-1"><p className="font-medium text-slate-900">{route.name}</p><p className="truncate text-sm text-muted-foreground">{route.origin} <span aria-hidden="true">→</span> {route.destination}</p></div>
                  <Button variant="ghost" size="icon" onClick={() => removeRoute.mutate({ id: route.id })} aria-label={`Rimuovi la tratta ${route.name}`}><Trash2 className="h-4 w-4 text-slate-500" /></Button>
                </div>)}
              </div>
            </CardContent>
          </Card>

          <div className="space-y-6">
            <Card className="border-slate-200 shadow-sm">
              <CardHeader className="pb-3"><div className="flex items-center justify-between"><div><CardTitle>Alexa</CardTitle><CardDescription className="mt-1">Account linking OAuth 2.0 e webhook firmato.</CardDescription></div><Radio className="h-5 w-5 text-cyan-700" /></div></CardHeader>
              <CardContent className="space-y-4">
                {voice.data?.configured ? <div className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-950"><strong>Configurazione server disponibile.</strong> Abilita la skill nell’app Alexa per avviare il collegamento.</div> : <div className="rounded-xl bg-amber-50 p-3 text-sm text-amber-950"><strong>Non attivo.</strong> Manca la configurazione della console Alexa; il webhook rifiuta ogni richiesta.</div>}
                <div className="rounded-xl border border-slate-200 p-3 text-sm"><p className="font-medium text-slate-900">Stato account</p><p className="mt-1 text-muted-foreground">{voice.data?.linked ? "Collegato e verificato." : "Non collegato. Le risposte personali restano bloccate."}</p></div>
                {voice.data?.linked ? <Button variant="outline" className="w-full" onClick={() => unlink.mutate()} disabled={unlink.isPending}><Link2Off className="mr-2 h-4 w-4" />Scollega account Alexa</Button> : <p className="text-xs leading-5 text-muted-foreground">Dopo aver configurato la skill, attivala nell’app Alexa: il collegamento si avvia solo dalla procedura OAuth ufficiale di Alexa.</p>}
              </CardContent>
            </Card>

            <Card className="border-slate-200 shadow-sm">
              <CardHeader className="pb-3"><CardTitle className="text-base">Garanzia sul dato ferroviario</CardTitle></CardHeader>
              <CardContent><div className="flex gap-3 rounded-xl bg-slate-100 p-3 text-sm text-slate-800"><CircleAlert className="mt-0.5 h-4 w-4 shrink-0" /><p>Finché non è configurata una fonte ferroviaria autorizzata, Binario risponde esattamente: <strong>“dati non disponibili in tempo reale”</strong>.</p></div></CardContent>
            </Card>

            <Card className="border-slate-200 shadow-sm"><CardHeader className="pb-3"><CardTitle className="text-base">Google Assistant su Android</CardTitle></CardHeader><CardContent><p className="text-sm leading-6 text-muted-foreground">Le App Actions possono aprire Binario sul telefono o su Wear OS. Non sostituiscono una Custom Skill conversazionale su Google Home.</p><a className="mt-3 inline-flex items-center text-sm font-medium text-cyan-800 hover:underline" href="/app/active-route">Prova deep link tratta attiva <ChevronRight className="h-4 w-4" /></a></CardContent></Card>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
