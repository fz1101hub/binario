import React from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { RouteSyncCandidate } from "@shared/optInRouteSync";

type OptInRouteSyncCardProps = {
  candidates: readonly RouteSyncCandidate[];
  selectedLocalIds: readonly string[];
  isPreviewOpen: boolean;
  isDisabled: boolean;
  isPending: boolean;
  message: string;
  error: boolean;
  onOpenPreview: () => void;
  onClosePreview: () => void;
  onToggleCandidate: (localId: string) => void;
  onConfirm: () => void;
  onDisable: () => void;
  onEnable: () => void;
};

const stateCopy: Record<RouteSyncCandidate["state"], string> = {
  new: "Pronta: non è stata ancora inviata.",
  already_in_account: "Già presente nell’area personale: non viene duplicata.",
  conflict: "Conflitto: stesso viaggio ma identificativi diversi. Non verrà inviata.",
};

export function OptInRouteSyncCard({ candidates, selectedLocalIds, isPreviewOpen, isDisabled, isPending, message, error, onOpenPreview, onClosePreview, onToggleCandidate, onConfirm, onDisable, onEnable }: OptInRouteSyncCardProps) {
  const selectableCount = candidates.filter(candidate => candidate.state === "new").length;
  const selectedCount = selectedLocalIds.length;

  return <Card data-binario-optional="true" className="mb-5 border-blue-200 bg-blue-50/60 dark:border-blue-900 dark:bg-blue-950/20">
    <CardHeader className="pb-3"><CardTitle className="text-base text-blue-950 dark:text-blue-100">Sincronizzazione Binario facoltativa</CardTitle><CardDescription className="leading-6 text-blue-900/80 dark:text-blue-100/80">Confronta le tratte di questo browser con l’area personale. Nessun dato viene trasferito finché non selezioni le singole tratte e confermi. Non esiste una sincronizzazione in background.</CardDescription></CardHeader>
    <CardContent className="space-y-3">
      {isDisabled ? <div className="rounded-xl border border-blue-200 bg-background p-3 text-sm leading-6 dark:border-blue-900"><p className="font-medium">Trasferimento disattivato in questo browser.</p><p className="text-muted-foreground">Le tratte già locali e quelle già nell’account non vengono cancellate. Puoi riaprire una nuova revisione quando vuoi.</p><Button type="button" variant="outline" className="mt-3" onClick={onEnable}>Riattiva revisione manuale</Button></div> : <><div className="flex flex-wrap gap-2"><Button type="button" variant="outline" className="border-blue-300 bg-background text-blue-900 hover:bg-blue-100 dark:text-blue-100" onClick={onOpenPreview}>{isPreviewOpen ? "Aggiorna anteprima" : "Esamina tratte e conflitti"}</Button><Button type="button" variant="ghost" onClick={onDisable}>Disattiva trasferimento</Button></div>{isPreviewOpen ? <div className="space-y-3 rounded-xl border border-blue-200 bg-background p-3 dark:border-blue-900"><p className="text-sm font-medium">Anteprima locale: {selectableCount} {selectableCount === 1 ? "tratta pronta" : "tratte pronte"}; selezionane manualmente una o più.</p>{candidates.length ? <ul className="space-y-2" aria-label="Esito confronto tratte locali e area personale">{candidates.map(candidate => <li key={candidate.localId} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800"><label className="flex cursor-pointer items-start gap-3"><input type="checkbox" checked={selectedLocalIds.includes(candidate.localId)} disabled={candidate.state !== "new" || isPending} onChange={() => onToggleCandidate(candidate.localId)} className="mt-1 h-4 w-4" /><span><span className="block text-sm font-medium">{candidate.route.name} · {candidate.route.travelDate} · {candidate.route.travelTime}</span><span className="mt-1 block text-xs leading-5 text-muted-foreground">{stateCopy[candidate.state]}</span></span></label></li>)}</ul> : <p className="text-sm text-muted-foreground">Non sono presenti tratte locali in questo browser da confrontare.</p>}<div className="flex flex-wrap gap-2"><Button type="button" disabled={isPending || selectedCount === 0} onClick={onConfirm}>{isPending ? "Copia in corso…" : `Conferma copia di ${selectedCount} ${selectedCount === 1 ? "tratta" : "tratte"}`}</Button><Button type="button" variant="ghost" onClick={onClosePreview}>Chiudi senza copiare</Button></div></div> : null}</>}
      {message ? <p role={error ? "alert" : "status"} className={`text-sm leading-6 ${error ? "text-red-800 dark:text-red-200" : "text-blue-950 dark:text-blue-100"}`}>{message}</p> : null}
    </CardContent>
  </Card>;
}
