import { describe, expect, it } from "vitest";
import { createLocalJourneyItinerary, deriveItineraryChangeSummary, parseGuidedTextJourneyImports, parseLocalJourneyItineraries } from "../shared/localJourneyItineraries";

describe("itinerari locali Binario", () => {
  it("mantiene soltanto itinerari con almeno due segmenti e schema noto", () => {
    const valid = createLocalJourneyItinerary({ id: "it-1", label: "Roma Milano", routeIds: ["a", "b", "b"], createdAt: "2026-09-03T10:00:00.000Z" });
    expect(valid?.routeIds).toEqual(["a", "b"]);
    expect(createLocalJourneyItinerary({ id: "it-2", label: "Uno", routeIds: ["a"], createdAt: "2026-09-03T10:00:00.000Z" })).toBeNull();
    expect(parseLocalJourneyItineraries('{"schemaVersion":1}')).toEqual([]);
  });

  it("ordina i segmenti e mostra solo la prossima tratta programmata non completata", () => {
    const itinerary = createLocalJourneyItinerary({ id: "it-1", label: "Roma Milano", routeIds: ["late", "early"], createdAt: "2026-09-03T10:00:00.000Z" })!;
    const result = deriveItineraryChangeSummary(itinerary, [
      { id: "late", origin: "Bologna", destination: "Milano", travelDate: "2026-09-12", travelTime: "11:00", checklist: [{ completed: false }] },
      { id: "early", origin: "Roma", destination: "Bologna", travelDate: "2026-09-12", travelTime: "08:00", checklist: [{ completed: true }, { completed: false }] },
    ], new Date("2026-09-12T07:00:00"));
    expect(result.segments.map(segment => segment.id)).toEqual(["early", "late"]);
    expect(result.nextSegment?.id).toBe("early");
    expect(result.followingSegment?.id).toBe("late");
    expect(result.remainingChecklistItems).toBe(1);
  });
});

describe("importazione guidata da testo", () => {
  it("estrae soltanto tratte complete, formattate e deduplicate", () => {
    expect(parseGuidedTextJourneyImports("Roma Termini → Bologna Centrale · 12/09/2026 alle 08:30\nDa Bologna Centrale a Milano Centrale 2026-09-12 11:05\nRoma Termini → Bologna Centrale · 12/09/2026 alle 08:30")).toEqual([
      { origin: "Roma Termini", destination: "Bologna Centrale", travelDate: "2026-09-12", travelTime: "08:30" },
      { origin: "Bologna Centrale", destination: "Milano Centrale", travelDate: "2026-09-12", travelTime: "11:05" },
    ]);
  });

  it("rifiuta testo incompleto o non strutturato senza inventare valori", () => {
    expect(parseGuidedTextJourneyImports("Roma Termini verso Milano domani mattina")).toEqual([]);
    expect(parseGuidedTextJourneyImports("Roma Termini → Roma Termini · 12/09/2026 alle 08:30")).toEqual([]);
  });
});
