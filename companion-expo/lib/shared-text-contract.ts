import { binarioSharedTextReceivedEventName } from "@/lib/native-module-names";

export const maxIncomingSharedTextCharacters = 4_000;

/** Conserva le righe per l’anteprima, rifiuta payload non testuali, vuoti o oltre soglia. */
export function normalizeIncomingSharedText(value: unknown): string | null {
  if (typeof value !== "string" || value.length === 0 || value.length > maxIncomingSharedTextCharacters) return null;
  const normalized = value.replace(/\r\n?/g, "\n").replace(/\u0000/g, "").trim();
  return normalized.length > 0 && normalized.length <= maxIncomingSharedTextCharacters ? normalized : null;
}

export type IncomingSharedTextBridge = { consume?: () => Promise<unknown> } | undefined;
export type IncomingSharedTextSubscription = { remove: () => void };
export type IncomingSharedTextListenerOptions = {
  bridge: IncomingSharedTextBridge;
  addListener: (eventName: string, handler: () => void) => IncomingSharedTextSubscription;
  onText: (text: string) => void;
};

/**
 * Legge il testo in attesa all’avvio e di nuovo a ogni evento nativo. Il modulo Kotlin conserva il testo
 * nelle preferenze finché consume() non lo rimuove, quindi l’evento è solo un segnale: la sorgente resta consume().
 * Restituisce la funzione di pulizia; un testo già consumato viene comunque consegnato, per non perderlo.
 */
export function subscribeIncomingSharedText({ bridge, addListener, onText }: IncomingSharedTextListenerOptions) {
  let active = true;
  const consume = () => {
    if (!active) return;
    Promise.resolve().then(() => bridge?.consume?.()).then(value => { const text = normalizeIncomingSharedText(value); if (text !== null) onText(text); }).catch(() => undefined);
  };
  const subscription = addListener(binarioSharedTextReceivedEventName, consume);
  consume();
  return () => { active = false; subscription.remove(); };
}
