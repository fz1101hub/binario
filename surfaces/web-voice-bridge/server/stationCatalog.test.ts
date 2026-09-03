import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { StationBoardDetails } from "../client/src/pages/StationBoard";
import { getStationBoardContext, getStationBoardPath, parseCanonicalStationCsv, searchCanonicalStations } from "../shared/stationCatalog";

const catalog = parseCanonicalStationCsv(`station_id,station_name,station_code,name_variants,confidence,trenitalia_location_id
S00001,Roma Termini,S00001,Roma Termini | ROMA TERMINI,Alta,830000001
S00002,Roma Tiburtina,S00002,Roma Tiburtina | TIBURTINA,Alta,830000002`);

describe("catalogo stazioni canonico", () => {
  it("preserva il codice e gli alias del catalogo curato", () => {
    expect(catalog[0]).toMatchObject({
      name: "Roma Termini",
      code: "S00001",
      aliases: ["ROMA TERMINI"],
      trenitaliaLocationId: "830000001",
    });
  });

  it("gestisce note CSV multilinea senza creare record fantasma o perdere la stazione seguente", () => {
    const multiline = parseCanonicalStationCsv(`station_id,station_name,station_code,manual_review_note,name_variants
S00001,Roma Ostiense,S00001,"Nota verificata su due righe
senza trasformarla in una stazione",OSTIENSE
S00002,Roma San Pietro,S00002,,SAN PIETRO`);
    expect(multiline).toHaveLength(2);
    expect(multiline.map(station => station.name)).toEqual(["Roma Ostiense", "Roma San Pietro"]);
  });

  it("trova una stazione sia per nome sia per alias, senza inventare risultati", () => {
    expect(searchCanonicalStations(catalog, "Roma Ter")[0]?.name).toBe("Roma Termini");
    expect(searchCanonicalStations(catalog, "Tiburtina")[0]?.name).toBe("Roma Tiburtina");
    expect(searchCanonicalStations(catalog, "Non esiste")).toEqual([]);
  });

  it("mantiene nome e codice nel passaggio dalla home al tabellone", () => {
    const path = getStationBoardPath(catalog[0]!);
    expect(path).toBe("/stazioni?name=Roma%20Termini&code=S00001");
    expect(getStationBoardContext(path.split("?")[1] ?? "")).toEqual({ name: "Roma Termini", code: "S00001" });
  });

  it("renderizza il dettaglio stazione dal percorso generato dalla home", () => {
    const path = getStationBoardPath(catalog[0]!);
    const context = getStationBoardContext(path.split("?")[1] ?? "");
    const markup = renderToStaticMarkup(createElement(StationBoardDetails, { stationName: context.name, stationCode: context.code, onBack: () => undefined }));

    expect(markup).toContain("Roma Termini");
    expect(markup).toContain("Codice stazione S00001.");
    expect(markup).toContain("Partenze non ancora disponibili");
  });
});
