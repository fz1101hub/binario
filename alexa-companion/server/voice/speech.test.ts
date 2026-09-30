import { describe, expect, it } from "vitest";
import { LIVE_DATA_UNAVAILABLE_SPEECH } from "./config";
import { escapeSsmlText, liveDataUnavailableSpeech } from "./speech";

describe("risposte vocali Binario senza feed live", () => {
  it("usa esattamente la frase obbligatoria per assenza dato live", () => {
    expect(liveDataUnavailableSpeech()).toBe(
      "dati non disponibili in tempo reale"
    );
    expect(LIVE_DATA_UNAVAILABLE_SPEECH).toBe(
      "dati non disponibili in tempo reale"
    );
  });

  it("può contestualizzare la frase senza alterarnee il testo", () => {
    expect(liveDataUnavailableSpeech("Per il treno 1234")).toContain(
      "dati non disponibili in tempo reale"
    );
  });

  it("rende testo i caratteri XML e il markup presenti in un valore dinamico", () => {
    expect(
      escapeSsmlText(
        `A & B < C > D "virgolette" 'apostrofi' <break time="1s"/>`
      )
    ).toBe(
      "A &amp; B &lt; C &gt; D &quot;virgolette&quot; &apos;apostrofi&apos; " +
        "&lt;break time=&quot;1s&quot;/&gt;"
    );
  });
});
