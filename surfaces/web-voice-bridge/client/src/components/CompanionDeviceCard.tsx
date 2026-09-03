import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { trpc } from "@/lib/trpc";
import { Copy, Link2, Link2Off, Smartphone } from "lucide-react";
import React, { useState } from "react";

type Platform = "android" | "ios";

function formatExpiry(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString("it-IT", { hour: "2-digit", minute: "2-digit" });
}

export function CompanionDeviceCard() {
  const utils = trpc.useUtils();
  const devices = trpc.companion.listDevices.useQuery();
  const [deviceName, setDeviceName] = useState("Binario Companion");
  const [platform, setPlatform] = useState<Platform>("android");
  const [pairing, setPairing] = useState<{ code: string; expiresAtUtcMs: number } | null>(null);
  const [feedback, setFeedback] = useState("");
  const [deviceToRevoke, setDeviceToRevoke] = useState<{ id: number; deviceName: string } | null>(null);
  const [revokeAllOpen, setRevokeAllOpen] = useState(false);
  const issue = trpc.companion.issuePairingCode.useMutation({
    onSuccess: result => { setPairing(result); setFeedback("Codice monouso generato. Copialo o inseriscilo subito nel companion."); },
  });
  const revoke = trpc.companion.revokeDevice.useMutation({
    onSuccess: () => { void utils.companion.listDevices.invalidate(); setDeviceToRevoke(null); setFeedback("Dispositivo revocato. Il token associato non può più leggere snapshot Binario."); },
    onError: () => setFeedback("Revoca non riuscita. Il dispositivo non è stato rimosso."),
  });
  const revokeAll = trpc.companion.revokeAllDevices.useMutation({
    onSuccess: result => { void utils.companion.listDevices.invalidate(); setRevokeAllOpen(false); setFeedback(result.revoked ? `${result.revoked} dispositivi revocati. Nessun token precedente può più leggere snapshot Binario.` : "Nessun dispositivo attivo da revocare."); },
    onError: () => setFeedback("Revoca globale non riuscita. I dispositivi restano invariati."),
  });
  const setManagedSync = trpc.companion.setManagedDeviceSync.useMutation({
    onSuccess: (result, input) => {
      void utils.companion.listDevices.invalidate();
      setFeedback(result.syncEnabled ? "Sincronizzazione autorizzata per questo dispositivo. Rimane comunque necessaria una selezione manuale delle tratte." : "Sincronizzazione bloccata per questo dispositivo. Il token resta attivo solo per gli snapshot Binario disponibili.");
    },
    onError: () => setFeedback("Impossibile aggiornare il consenso di sincronizzazione. Il dispositivo è rimasto invariato."),
  });

  const activeDevices = devices.data?.filter(device => !device.revokedAt && new Date(device.expiresAt).getTime() > Date.now()) ?? [];
  const canIssue = deviceName.trim().length >= 2 && !issue.isPending;

  async function copyPairingCode() {
    if (!pairing) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard non disponibile");
      await navigator.clipboard.writeText(pairing.code);
      setFeedback("Codice copiato. Rimane valido solo fino all’orario indicato.");
    } catch {
      setFeedback("Copia non disponibile nel browser corrente. Seleziona e copia manualmente il codice.");
    }
  }

  return <Card className="border-slate-200 bg-slate-950 text-slate-50 shadow-sm" aria-labelledby="companion-device-heading">
    <CardHeader className="pb-3">
      <div className="flex items-center justify-between gap-3"><div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.12em] text-cyan-200">Companion</p><CardTitle id="companion-device-heading" className="text-slate-50">Widget e smartwatch</CardTitle><CardDescription className="mt-1 text-slate-300">Collegano solo lo snapshot Binario già verificato.</CardDescription></div><Smartphone className="h-5 w-5 text-cyan-300" /></div>
    </CardHeader>
    <CardContent className="space-y-4">
      <p className="text-sm leading-6 text-slate-300">Il codice collega una sola installazione per dieci minuti. Il token ricevuto dal telefono non viene mostrato né salvato nella dashboard.</p>
      <div className="grid gap-2 sm:grid-cols-[1fr_auto]">
        <div className="space-y-1.5"><Label htmlFor="companion-device-name" className="text-slate-100">Nome dispositivo</Label><Input id="companion-device-name" value={deviceName} onChange={event => setDeviceName(event.target.value)} maxLength={80} className="border-slate-600 bg-slate-900 text-slate-50" /></div>
        <div className="space-y-1.5"><Label htmlFor="companion-platform" className="text-slate-100">Piattaforma</Label><select id="companion-platform" value={platform} onChange={event => setPlatform(event.target.value as Platform)} className="h-10 rounded-md border border-slate-600 bg-slate-900 px-3 text-sm text-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-300"><option value="android">Android</option><option value="ios">iPhone</option></select></div>
      </div>
      <Button type="button" className="w-full bg-cyan-300 text-slate-950 hover:bg-cyan-200" disabled={!canIssue} onClick={() => issue.mutate({ deviceName: deviceName.trim(), platform })}><Link2 className="mr-2 h-4 w-4" />Genera codice di collegamento</Button>
      {issue.error ? <p role="alert" className="text-sm text-rose-200">Non è possibile generare il codice. Riprova più tardi.</p> : null}
      {pairing ? <div className="rounded-xl border border-cyan-300/50 bg-slate-900 p-3" aria-live="polite"><p className="text-xs font-semibold uppercase tracking-[0.12em] text-cyan-200">Codice monouso</p><p className="mt-2 break-all font-mono text-sm text-slate-50">{pairing.code}</p><p className="mt-2 text-xs text-slate-300">Inseriscilo nell’app Binario Companion entro le {formatExpiry(pairing.expiresAtUtcMs)}. Alla scadenza viene rifiutato.</p><Button type="button" variant="outline" size="sm" className="mt-3 border-slate-600 text-slate-50 hover:bg-slate-800" onClick={() => void copyPairingCode()}><Copy className="mr-1.5 h-3.5 w-3.5" />Copia</Button></div> : null}
      {feedback ? <p role="status" aria-live="polite" className="rounded-xl border border-slate-700 bg-slate-900 p-3 text-xs leading-5 text-slate-200">{feedback}</p> : null}
      <div className="space-y-2 border-t border-slate-700 pt-3"><div className="flex items-center justify-between gap-2"><p className="text-xs font-semibold uppercase tracking-[0.12em] text-slate-300">Dispositivi autorizzati</p>{activeDevices.length > 1 ? <Button type="button" variant="ghost" size="sm" className="h-8 text-rose-200 hover:bg-rose-950/40 hover:text-rose-100" onClick={() => setRevokeAllOpen(true)} disabled={revokeAll.isPending}>Revoca tutti</Button> : null}</div>{devices.isLoading ? <p className="text-sm text-slate-300">Caricamento…</p> : null}{devices.error ? <p role="alert" className="text-sm text-rose-200">Elenco dispositivi non disponibile.</p> : null}{!devices.isLoading && !devices.error && activeDevices.length === 0 ? <p className="text-sm text-slate-300">Nessun dispositivo collegato.</p> : activeDevices.map(device => <div key={device.id} className="space-y-2 rounded-lg bg-slate-900 p-2.5"><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-sm font-medium text-slate-50">{device.deviceName}</p><p className="text-xs text-slate-300">{device.platform === "android" ? "Android" : "iPhone"} · scade il {new Date(device.expiresAt).toLocaleDateString("it-IT")}{device.lastUsedAt ? ` · ultimo uso ${new Date(device.lastUsedAt).toLocaleDateString("it-IT")}` : " · mai usato"}</p></div><Button type="button" variant="ghost" size="sm" className="text-slate-200 hover:bg-slate-800 hover:text-slate-50" onClick={() => setDeviceToRevoke({ id: device.id, deviceName: device.deviceName })} disabled={revoke.isPending || revokeAll.isPending || setManagedSync.isPending}><Link2Off className="mr-1.5 h-3.5 w-3.5" />Revoca</Button></div><div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-700 pt-2"><p className="text-xs text-slate-300"><span className="font-semibold text-slate-100">Sincronizzazione:</span> {device.syncEnabled ? "autorizzata manualmente" : "bloccata"}</p><Button type="button" variant="outline" size="sm" className="border-slate-600 text-slate-50 hover:bg-slate-800" aria-pressed={device.syncEnabled} onClick={() => setManagedSync.mutate({ id: device.id, enabled: !device.syncEnabled })} disabled={setManagedSync.isPending || revoke.isPending || revokeAll.isPending}>{device.syncEnabled ? "Blocca sincronizzazione" : "Autorizza sincronizzazione"}</Button></div></div>)}</div>
      <AlertDialog open={Boolean(deviceToRevoke)} onOpenChange={open => { if (!open) setDeviceToRevoke(null); }}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Revocare {deviceToRevoke?.deviceName}?</AlertDialogTitle><AlertDialogDescription>Il token del dispositivo sarà invalidato. L’app, il widget e lo smartwatch del device non potranno più ricevere snapshot Binario finché non verranno collegati di nuovo.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel>Annulla</AlertDialogCancel><AlertDialogAction className="bg-red-700 hover:bg-red-800" disabled={!deviceToRevoke || revoke.isPending} onClick={event => { if (!deviceToRevoke) { event.preventDefault(); return; } revoke.mutate({ id: deviceToRevoke.id }); }}> {revoke.isPending ? "Revoca…" : "Revoca dispositivo"}</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
      <AlertDialog open={revokeAllOpen} onOpenChange={setRevokeAllOpen}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Revocare tutti i dispositivi?</AlertDialogTitle><AlertDialogDescription>Tutti i token companion attivi verranno invalidati. Ogni telefono, widget o smartwatch dovrà essere collegato nuovamente con un nuovo codice.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel>Annulla</AlertDialogCancel><AlertDialogAction className="bg-red-700 hover:bg-red-800" disabled={revokeAll.isPending} onClick={() => revokeAll.mutate()}> {revokeAll.isPending ? "Revoca…" : "Revoca tutti"}</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
    </CardContent>
  </Card>;
}
