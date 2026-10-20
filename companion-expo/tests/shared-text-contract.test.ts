import { describe, expect, it, vi } from "vitest";
import { binarioSharedTextReceivedEventName } from "@/lib/native-module-names";
import { maxIncomingSharedTextCharacters, normalizeIncomingSharedText, subscribeIncomingSharedText } from "@/lib/shared-text-contract";

function fakeEmitter() {
  const handlers = new Map<string, () => void>();
  const remove = vi.fn();
  const addListener = vi.fn((eventName: string, handler: () => void) => { handlers.set(eventName, handler); return { remove }; });
  return { handlers, remove, addListener, emit: () => handlers.get(binarioSharedTextReceivedEventName)?.() };
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const sharedRoute = "Roma Termini → Napoli Centrale · 02/01/2030 alle 09:30";

describe("testo condiviso in arrivo", () => {
  it("accetta al massimo la stessa soglia del receiver nativo", () => {
    expect(normalizeIncomingSharedText("a".repeat(maxIncomingSharedTextCharacters))).toHaveLength(maxIncomingSharedTextCharacters);
    expect(normalizeIncomingSharedText("a".repeat(maxIncomingSharedTextCharacters + 1))).toBeNull();
    expect(normalizeIncomingSharedText("  Roma → Napoli\r\n12/09/2026 alle 08:30 ")).toBe("Roma → Napoli\n12/09/2026 alle 08:30");
    expect(normalizeIncomingSharedText(42)).toBeNull();
    expect(normalizeIncomingSharedText("   ")).toBeNull();
  });

  it("consuma il testo in attesa all’avvio e di nuovo a ogni evento nativo, finché non viene fermato", async () => {
    const pending: Array<string | null> = [sharedRoute, null, "Milano Centrale → Torino Porta Nuova · 03/01/2030 alle 07:15"];
    const consume = vi.fn(async () => pending.shift() ?? null);
    const emitter = fakeEmitter();
    const onText = vi.fn();
    const stop = subscribeIncomingSharedText({ bridge: { consume }, addListener: emitter.addListener, onText });
    expect(emitter.addListener).toHaveBeenCalledWith(binarioSharedTextReceivedEventName, expect.any(Function));
    await flush();
    expect(onText).toHaveBeenCalledTimes(1);
    expect(onText).toHaveBeenLastCalledWith(sharedRoute);
    emitter.emit();
    await flush();
    expect(consume).toHaveBeenCalledTimes(2);
    expect(onText).toHaveBeenCalledTimes(1);
    emitter.emit();
    await flush();
    expect(consume).toHaveBeenCalledTimes(3);
    expect(onText).toHaveBeenLastCalledWith("Milano Centrale → Torino Porta Nuova · 03/01/2030 alle 07:15");
    stop();
    expect(emitter.remove).toHaveBeenCalledOnce();
    emitter.emit();
    await flush();
    expect(consume).toHaveBeenCalledTimes(3);
  });

  it("ignora testi oltre soglia, bridge assenti ed errori nativi senza perdere un testo già consumato", async () => {
    const onText = vi.fn();
    subscribeIncomingSharedText({ bridge: { consume: async () => "x".repeat(maxIncomingSharedTextCharacters + 1) }, addListener: fakeEmitter().addListener, onText });
    subscribeIncomingSharedText({ bridge: undefined, addListener: fakeEmitter().addListener, onText });
    subscribeIncomingSharedText({ bridge: {}, addListener: fakeEmitter().addListener, onText });
    subscribeIncomingSharedText({ bridge: { consume: () => { throw new Error("modulo non collegato"); } }, addListener: fakeEmitter().addListener, onText });
    subscribeIncomingSharedText({ bridge: { consume: async () => { throw new Error("errore nativo"); } }, addListener: fakeEmitter().addListener, onText });
    await flush();
    expect(onText).not.toHaveBeenCalled();
    let deliver!: (value: string) => void;
    const stop = subscribeIncomingSharedText({ bridge: { consume: () => new Promise<string>(resolve => { deliver = resolve; }) }, addListener: fakeEmitter().addListener, onText });
    await flush();
    stop();
    deliver(sharedRoute);
    await flush();
    expect(onText).toHaveBeenCalledTimes(1);
    expect(onText).toHaveBeenCalledWith(sharedRoute);
  });
});
