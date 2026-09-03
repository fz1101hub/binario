import { describe, expect, it } from "vitest";
import { getRouteActionLabels } from "./routeAccessibility";

describe("etichette accessibili delle azioni tratta", () => {
  it("mantiene l’oggetto della tratta in tutte le azioni e distingue fissa/rimuovi fissa", () => {
    expect(getRouteActionLabels("Roma Ostiense → Roma San Pietro", false)).toMatchObject({ pin: "Fissa Roma Ostiense → Roma San Pietro", open: "Apri il dettaglio della tratta Roma Ostiense → Roma San Pietro", remove: "Rimuovi la tratta Roma Ostiense → Roma San Pietro" });
    expect(getRouteActionLabels("Roma Ostiense → Roma San Pietro", true).pin).toBe("Rimuovi Roma Ostiense → Roma San Pietro dalle tratte fissate");
  });
});
