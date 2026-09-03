import { describe, expect, it } from "vitest";
import { confirmVoiceStation, createVoiceJourneyReview, parseItalianJourneyDictation } from "../shared/voiceJourney";
import type { CanonicalStation } from "../shared/stationCatalog";

const catalog: CanonicalStation[] = [
  { stationId: "roma", name: "Roma Termini", code: "S00001", aliases: ["Roma Termini"], confidence: "Alta", trenitaliaLocationId: null },
  { stationId: "milano", name: "Milano Centrale", code: "S00002", aliases: ["Milano C.le"], confidence: "Alta", trenitaliaLocationId: null },
  { stationId: "tivoli", name: "Tivoli", code: "S00003", aliases: [], confidence: "Alta", trenitaliaLocationId: null },
];
const referenceDate = new Date(2026, 8, 1, 9, 0, 0);

describe("dettatura completa del viaggio", () => {
  it("interpreta tratta, domani e orario numerico senza inventare valori", () => {
    expect(parseItalianJourneyDictation("Da Roma Termini a Milano Centrale domani alle 08:30", referenceDate)).toMatchObject({
      originPhrase: "Roma Termini", destinationPhrase: "Milano Centrale", date: "2026-09-02", time: "08:30",
    });
  });

  it("riconosce data testuale e orario pronunciato", () => {
    expect(parseItalianJourneyDictation("parto da Tivoli verso Roma Termini il 3 settembre alle otto e trenta", referenceDate)).toMatchObject({
      originPhrase: "Tivoli", destinationPhrase: "Roma Termini", date: "2026-09-03", time: "08:30",
    });
  });

  it("mantiene null i valori non pronunciati e avvisa l’utente", () => {
    const parsed = parseItalianJourneyDictation("Roma Termini", referenceDate);
    expect(parsed).toMatchObject({ originPhrase: null, destinationPhrase: null, date: null, time: null });
    expect(parsed.notices).toHaveLength(3);
  });

  it("conferma solo una stazione esatta e richiede revisione per un suggerimento", () => {
    const review = createVoiceJourneyReview("Da Roma Termini a Milano", catalog, referenceDate);
    expect(review.origin).toMatchObject({ status: "confirmed", station: { code: "S00001" } });
    expect(review.destination).toMatchObject({ status: "needs-review", station: null, candidates: [{ code: "S00002" }] });
    expect(confirmVoiceStation(review.destination, catalog[1]!)).toMatchObject({ status: "confirmed", station: { code: "S00002" } });
  });

  it("mantiene la revisione quando più stazioni sono plausibili", () => {
    const ambiguousCatalog = [...catalog, { stationId: "milano-pg", name: "Milano Porta Garibaldi", code: "S00004", aliases: ["Milano Garibaldi"], confidence: "Alta", trenitaliaLocationId: null }];
    const review = createVoiceJourneyReview("Da Roma Termini a Milano", ambiguousCatalog, referenceDate);

    expect(review.destination.status).toBe("needs-review");
    expect(review.destination.station).toBeNull();
    expect(review.destination.candidates.map(candidate => candidate.code)).toEqual(expect.arrayContaining(["S00002", "S00004"]));
  });
});
