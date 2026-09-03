import { VoiceDictationButton } from "@/components/VoiceDictationButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { loadCanonicalStationCatalog } from "@/lib/stationCatalogClient";
import { confirmVoiceStation, createVoiceJourneyReview, type VoiceJourneyReview } from "@shared/voiceJourney";
import type { CanonicalStation } from "@shared/stationCatalog";
import { CheckCircle2, CircleAlert, Mic, PencilLine, Route, Sparkles } from "lucide-react";
import React, { useRef, useState } from "react";

type AppliedVoiceJourney = {
  origin: CanonicalStation;
  destination: CanonicalStation;
  date: string | null;
  time: string | null;
};

type VoiceJourneyPlannerProps = {
  onApply: (journey: AppliedVoiceJourney) => void;
};

function ResolutionPanel({
  label,
  resolution,
  onChoose,
}: {
  label: string;
  resolution: VoiceJourneyReview["origin"];
  onChoose: (station: CanonicalStation) => void;
}) {
  if (resolution.status === "confirmed" && resolution.station) {
    return (
      <div className="rounded-xl border border-emerald-200 bg-emerald-50/70 p-3 text-sm dark:border-emerald-800 dark:bg-emerald-950/30">
        <p className="flex items-center gap-1.5 font-semibold text-emerald-900 dark:text-emerald-200"><CheckCircle2 className="h-4 w-4" />{label}: {resolution.station.name}</p>
        <p className="mt-1 text-xs text-emerald-800 dark:text-emerald-300">Codice stazione {resolution.station.code}</p>
      </div>
    );
  }

  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50/70 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30">
      <p className="font-semibold text-amber-950 dark:text-amber-100">{label}: {resolution.phrase ?? "non rilevata"}</p>
      {resolution.status === "needs-review" ? (
        <div className="mt-2 space-y-1.5">
          <p className="text-xs leading-5 text-amber-900 dark:text-amber-200">Scegli la stazione corretta prima di applicare la tratta.</p>
          <div className="flex flex-wrap gap-2">
            {resolution.candidates.map(station => <Button key={station.stationId} type="button" variant="outline" size="sm" className="border-amber-300 bg-background text-amber-950 hover:bg-amber-100 dark:border-amber-700 dark:bg-card dark:text-amber-100 dark:hover:bg-amber-950/50" onClick={() => onChoose(station)}>{station.name}</Button>)}
          </div>
        </div>
      ) : <p className="mt-1 text-xs leading-5 text-amber-900 dark:text-amber-200">Modifica la frase e interpretala di nuovo, oppure usa il campo stazione manuale.</p>}
    </div>
  );
}

export function VoiceJourneyPlanner({ onApply }: VoiceJourneyPlannerProps) {
  const [transcript, setTranscript] = useState("");
  const [review, setReview] = useState<VoiceJourneyReview | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [catalogError, setCatalogError] = useState(false);
  const transcriptInputRef = useRef<HTMLInputElement>(null);

  async function interpret(text: string) {
    const value = text.trim();
    if (!value) return;
    setTranscript(value);
    setCatalogError(false);
    setIsResolving(true);
    try {
      const catalog = await loadCanonicalStationCatalog();
      setReview(createVoiceJourneyReview(value, catalog));
    } catch {
      setCatalogError(true);
      setReview(null);
      window.requestAnimationFrame(() => transcriptInputRef.current?.focus());
    } finally {
      setIsResolving(false);
    }
  }

  function updateTranscript(value: string) {
    setTranscript(value);
    setReview(null);
  }

  function applyReview() {
    if (!review?.origin.station || !review.destination.station || review.origin.status !== "confirmed" || review.destination.status !== "confirmed") return;
    onApply({
      origin: review.origin.station,
      destination: review.destination.station,
      date: review.date,
      time: review.time,
    });
    setReview(null);
  }

  const canApply = review?.origin.status === "confirmed" && review.destination.status === "confirmed" && Boolean(review.origin.station && review.destination.station);

  return (
    <section className="rounded-2xl border border-blue-200 bg-blue-50/55 p-4 dark:border-blue-900 dark:bg-blue-950/25" aria-labelledby="voice-journey-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-blue-700 dark:text-blue-300"><Mic className="h-3.5 w-3.5" />Dettatura guidata</p>
          <h2 id="voice-journey-heading" className="mt-1 text-base font-semibold text-slate-950 dark:text-foreground">Detta l’intera tratta</h2>
          <p className="mt-1 max-w-2xl text-sm leading-6 text-slate-600 dark:text-muted-foreground">Ad esempio: “Da Roma Termini a Milano Centrale domani alle otto e trenta”. La trascrizione è automatica, ma non prende decisioni sul viaggio: prima dell’applicazione devi rivedere e confermare testo, stazioni, data e orario.</p>
        </div>
        <VoiceDictationButton label="Detta l’intera tratta" onTranscript={interpret} />
      </div>

      <div className="mt-4 flex gap-2">
        <Input ref={transcriptInputRef} id="voice-journey-transcript" aria-label="Testo della dettatura viaggio" aria-describedby={`voice-journey-description${catalogError ? " voice-journey-error" : ""}`} aria-invalid={catalogError} value={transcript} onChange={event => updateTranscript(event.target.value)} placeholder="Scrivi o detta una tratta completa" maxLength={300} />
        <Button type="button" variant="outline" className="shrink-0" onClick={() => interpret(transcript)} disabled={!transcript.trim() || isResolving}>
          <Sparkles className="mr-2 h-4 w-4" />{isResolving ? "Interpreto…" : "Interpreta"}
        </Button>
      </div>
      <p id="voice-journey-description" className="mt-2 text-xs leading-5 text-slate-600 dark:text-muted-foreground">Puoi modificare il testo prima di interpretarlo. Le stazioni ambigue richiedono sempre una scelta manuale.</p>

      {catalogError ? <p id="voice-journey-error" role="alert" className="mt-3 flex gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-950 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-100"><CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />Il catalogo non è disponibile. Il focus è tornato al testo: puoi correggerlo o usare i campi stazione manuali.</p> : null}

      {review ? (
        <div className="mt-4 space-y-3" aria-live="polite" aria-atomic="false">
          <div className="grid gap-3 md:grid-cols-2">
            <ResolutionPanel label="Partenza" resolution={review.origin} onChoose={station => setReview(current => current ? { ...current, origin: confirmVoiceStation(current.origin, station) } : current)} />
            <ResolutionPanel label="Arrivo" resolution={review.destination} onChoose={station => setReview(current => current ? { ...current, destination: confirmVoiceStation(current.destination, station) } : current)} />
          </div>
          <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">
            <span className="rounded-lg bg-background px-2.5 py-1.5 dark:bg-card"><Route className="mr-1 inline h-3.5 w-3.5" />Data: {review.date ?? "non pronunciata — invariata"}</span>
            <span className="rounded-lg bg-background px-2.5 py-1.5 dark:bg-card"><PencilLine className="mr-1 inline h-3.5 w-3.5" />Ora: {review.time ?? "non pronunciata — invariata"}</span>
          </div>
          {review.notices.length ? <ul className="space-y-1 text-xs leading-5 text-muted-foreground">{review.notices.map(notice => <li key={notice}>• {notice}</li>)}</ul> : null}
          <div className="flex flex-col gap-3 border-t border-blue-200 pt-3 sm:flex-row sm:items-center sm:justify-between dark:border-blue-900">
            <p className="text-xs leading-5 text-muted-foreground">L’app applica soltanto campi confermati da te o pronunciati; nessuna decisione sul viaggio è automatizzata e nessun dato viene inviato a un operatore ferroviario in questa fase.</p>
            <Button type="button" onClick={applyReview} disabled={!canApply} className="bg-blue-700 hover:bg-blue-800"><CheckCircle2 className="mr-2 h-4 w-4" />Applica alla pianificazione</Button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
