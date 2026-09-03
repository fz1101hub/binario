// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

const trpcMocks = vi.hoisted(() => ({
  mutate: vi.fn(),
  useMutation: vi.fn(() => ({ mutate: trpcMocks.mutate, isPending: false })),
}));

vi.mock("@/lib/trpc", () => ({ trpc: { dictation: { transcribe: { useMutation: trpcMocks.useMutation } } } }));

import { VoiceDictationButton } from "../client/src/components/VoiceDictationButton";

type RecorderOptions = { denied?: boolean; empty?: boolean; tooLarge?: boolean };

function installRecorder(options: RecorderOptions = {}) {
  const stopTrack = vi.fn();
  const getUserMedia = options.denied
    ? vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError"))
    : vi.fn().mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] });
  Object.defineProperty(navigator, "mediaDevices", { value: { getUserMedia }, configurable: true });
  class FakeMediaRecorder {
    static isTypeSupported() { return true; }
    state: RecordingState = "inactive";
    mimeType = "audio/webm";
    ondataavailable: ((event: BlobEvent) => void) | null = null;
    onstop: (() => void) | null = null;
    start() { this.state = "recording"; }
    stop() {
      this.state = "inactive";
      const content: BlobPart = options.tooLarge ? new ArrayBuffer(16 * 1024 * 1024 + 1) : options.empty ? "" : "clip";
      this.ondataavailable?.({ data: new Blob([content], { type: this.mimeType }) } as BlobEvent);
      this.onstop?.();
    }
  }
  vi.stubGlobal("MediaRecorder", FakeMediaRecorder);
  return { getUserMedia, stopTrack };
}

function acceptDictationConsent(label: string) {
  fireEvent.click(screen.getByRole("button", { name: label }));
  fireEvent.click(screen.getByRole("button", { name: "Registra e trascrivi" }));
}

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
  trpcMocks.mutate.mockReset();
  trpcMocks.useMutation.mockClear();
});

describe("VoiceDictationButton", () => {
  it("informa l’utente e conserva l’inserimento manuale se il browser non supporta il microfono", () => {
    vi.stubGlobal("MediaRecorder", undefined);
    Object.defineProperty(navigator, "mediaDevices", { value: undefined, configurable: true });
    render(<VoiceDictationButton label="Detta partenza" onTranscript={vi.fn()} />);
    acceptDictationConsent("Detta partenza");
    expect(screen.getByRole("status").textContent).toContain("non è supportata");
    expect(trpcMocks.mutate).not.toHaveBeenCalled();
  });

  it("avvia e termina la registrazione solo dopo consenso prima di inviare l’audio alla trascrizione", async () => {
    const { getUserMedia, stopTrack } = installRecorder();
    render(<VoiceDictationButton label="Detta partenza" onTranscript={vi.fn()} />);
    acceptDictationConsent("Detta partenza");
    await waitFor(() => expect(screen.getByRole("button", { name: "Termina: Detta partenza" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Termina: Detta partenza" }));
    await waitFor(() => expect(trpcMocks.mutate).toHaveBeenCalledWith(expect.objectContaining({ mimeType: "audio/webm" })));
    expect(getUserMedia).toHaveBeenCalledWith({ audio: true });
    expect(stopTrack).toHaveBeenCalledOnce();
  });

  it("comunica un consenso microfono negato senza inviare audio", async () => {
    installRecorder({ denied: true });
    render(<VoiceDictationButton label="Detta arrivo" onTranscript={vi.fn()} />);
    acceptDictationConsent("Detta arrivo");
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Microfono non autorizzato"));
    expect(trpcMocks.mutate).not.toHaveBeenCalled();
  });

  it("segnala registrazione vuota o oltre il limite senza inviare la trascrizione", async () => {
    installRecorder({ empty: true });
    render(<VoiceDictationButton label="Detta stazione" onTranscript={vi.fn()} />);
    acceptDictationConsent("Detta stazione");
    await waitFor(() => expect(screen.getByRole("button", { name: "Termina: Detta stazione" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Termina: Detta stazione" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("Trascrizione non riuscita"));
    expect(trpcMocks.mutate).not.toHaveBeenCalled();
  });

  it("rifiuta un audio oltre 16 MB prima dell’invio", async () => {
    installRecorder({ tooLarge: true });
    render(<VoiceDictationButton label="Detta stazione" onTranscript={vi.fn()} />);
    acceptDictationConsent("Detta stazione");
    await waitFor(() => expect(screen.getByRole("button", { name: "Termina: Detta stazione" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Termina: Detta stazione" }));
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("troppo lunga"));
    expect(trpcMocks.mutate).not.toHaveBeenCalled();
  });

  it("richiede la conferma prima di chiedere il microfono", () => {
    const { getUserMedia } = installRecorder();
    render(<VoiceDictationButton label="Detta partenza" onTranscript={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Detta partenza" }));
    expect(screen.getByRole("heading", { name: "Prima di iniziare la dettatura" })).toBeTruthy();
    expect(getUserMedia).not.toHaveBeenCalled();
  });
});
