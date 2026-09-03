import { trpc } from "@/lib/trpc";
import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { getDictationMessage, normalizeDictationMimeType, type DictationStatus } from "@shared/dictationPresentation";
import { Loader2, Mic, Square, Volume2 } from "lucide-react";
import React, { useRef, useState } from "react";

const maxAudioBytes = 16 * 1024 * 1024;
const dictationConsentKey = "binario-dictation-consent-session-v1";

function supportedMimeType() {
  if (typeof MediaRecorder === "undefined") return "";
  return ["audio/webm;codecs=opus", "audio/webm", "audio/ogg", "audio/mp4"].find(type => MediaRecorder.isTypeSupported(type)) ?? "";
}

function toBase64(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error);
    reader.onload = () => resolve(String(reader.result).split(",")[1] ?? "");
    reader.readAsDataURL(blob);
  });
}

export function VoiceDictationButton({
  label,
  onTranscript,
}: {
  label: string;
  onTranscript: (text: string) => void;
}) {
  const [status, setStatus] = useState<DictationStatus>("idle");
  const [consentOpen, setConsentOpen] = useState(false);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const transcribe = trpc.dictation.transcribe.useMutation({
    onSuccess: result => {
      if (result.text) onTranscript(result.text);
      setStatus("idle");
    },
    onError: () => setStatus("error"),
  });

  function releaseStream() {
    streamRef.current?.getTracks().forEach(track => track.stop());
    streamRef.current = null;
  }

  async function start() {
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setStatus("unsupported");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = supportedMimeType();
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      streamRef.current = stream;
      recorderRef.current = recorder;
      chunksRef.current = [];
      recorder.ondataavailable = event => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = async () => {
        releaseStream();
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || "audio/webm" });
        if (blob.size === 0) {
          setStatus("error");
          return;
        }
        if (blob.size > maxAudioBytes) {
          setStatus("too-large");
          return;
        }
        setStatus("processing");
        try {
          const base64 = await toBase64(blob);
          const normalizedMimeType = normalizeDictationMimeType(blob.type || "audio/webm");
          if (!normalizedMimeType) {
            setStatus("error");
            return;
          }
          transcribe.mutate({ audioBase64: base64, mimeType: normalizedMimeType });
        } catch {
          setStatus("error");
        }
      };
      recorder.start();
      setStatus("recording");
    } catch (error) {
      setStatus(error instanceof DOMException && error.name === "NotAllowedError" ? "denied" : "error");
    }
  }

  function requestStart() {
    try {
      if (sessionStorage.getItem(dictationConsentKey) === "accepted") {
        void start();
        return;
      }
    } catch { /* Il consenso verrà chiesto per questa registrazione. */ }
    setConsentOpen(true);
  }

  function acceptConsent() {
    try { sessionStorage.setItem(dictationConsentKey, "accepted"); } catch { /* Consenso valido per l’azione corrente. */ }
    setConsentOpen(false);
    void start();
  }

  function stop() {
    if (recorderRef.current?.state === "recording") recorderRef.current.stop();
  }

  const isBusy = status === "processing";
  const recording = status === "recording";
  const message = getDictationMessage(status);

  return (
    <div className="flex items-center gap-2">
      <Button type="button" variant={recording ? "default" : "outline"} size="icon" className={`h-9 w-9 shrink-0 rounded-full ${recording ? "bg-red-600 hover:bg-red-700" : "border-blue-200 text-blue-700 hover:bg-blue-50"}`} onClick={recording ? stop : requestStart} disabled={isBusy} aria-label={recording ? `Termina: ${label}` : label} aria-pressed={recording}>
        {isBusy ? <Loader2 className="h-4 w-4 animate-spin" /> : recording ? <Square className="h-3.5 w-3.5 fill-current" /> : <Mic className="h-4 w-4" />}
      </Button>
      <p role="status" className={`text-xs leading-4 ${status === "error" || status === "denied" || status === "unsupported" || status === "too-large" ? "text-amber-800" : "text-muted-foreground"}`}>
        {status === "idle" ? <span className="sr-only">{message}</span> : <><Volume2 className="mr-1 inline h-3.5 w-3.5" />{message}</>}
      </p>
      <AlertDialog open={consentOpen} onOpenChange={setConsentOpen}><AlertDialogContent><AlertDialogHeader><AlertDialogTitle>Prima di iniziare la dettatura</AlertDialogTitle><AlertDialogDescription>Con la conferma Binario chiederà il permesso del microfono e invierà questa sola registrazione alla trascrizione automatica. Il testo ottenuto resta modificabile e non decide il viaggio. L’audio non è salvato nel profilo; la conservazione temporanea dell’archivio tecnico sottostante non è attestata da Binario.</AlertDialogDescription></AlertDialogHeader><AlertDialogFooter><AlertDialogCancel>Annulla</AlertDialogCancel><AlertDialogAction onClick={acceptConsent}>Registra e trascrivi</AlertDialogAction></AlertDialogFooter></AlertDialogContent></AlertDialog>
    </div>
  );
}
