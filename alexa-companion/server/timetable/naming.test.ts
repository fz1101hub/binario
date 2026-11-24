import { readFileSync } from "node:fs";
import path from "node:path";
import { messagesForLocale } from "@shared/i18n";
import { describe, expect, it } from "vitest";
import { PackRoles } from "@shared/generated/sharedData";
import { busLabel, isUrban, legMode, lineLabel, operatorName, urbanLabel, urbanLine, urbanLineRepeatsMode, vehicleOf, type TimetableLegMode } from "./naming";
import { serviceLabel } from "../voice/timetableSpeech";

/**
 * contracts/shared-rules/v1/journey-leg-vehicles-v1.json: what a service of the timetable travels on,
 * as Android (JourneyLegVehiclesContractTest.kt) and the iPhone (SharedRulesContractTests.swift) read it.
 */
const contract = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-rules", "v1", "journey-leg-vehicles-v1.json");
type LegVehicles = {
  scheduledCoachPacks: string[];
  legVehicles: Array<{ routeType: string; pack: string; operator: string | null; line: string | null; mode: string | null; vehicle: string | null; iosLabel: string | null; why: string }>;
  urbanLines: Array<{ mode: string; published: string; line: string | null; why: string }>;
  urbanLabels: Array<{ label: string; line: string; repeatsMode: boolean; shown: string; why: string }>;
};
const rules = JSON.parse(readFileSync(contract, "utf8")) as LegVehicles;

/** The iPhone's label keys and the skill's texts of the same vehicles. */
const LABEL_KEYS: Record<string, keyof ReturnType<typeof messagesForLocale>["timetable"]["vehicles"]> = {
  "result.vehicle.bus_trenitalia": "busTrenitalia",
  "result.vehicle.freccialink": "freccialink",
  "result.vehicle.italobus": "italobus",
  "result.vehicle.bus": "bus",
  "result.vehicle.replacement_bus": "replacementBus",
  "result.vehicle.metro": "metro",
  "result.vehicle.tram": "tram",
  "result.vehicle.funicular": "funicular",
};

describe("timetable vehicles, shared with Android and the iPhone", () => {
  it("gives every shared leg its mode and vehicle", () => {
    expect(new Set(rules.scheduledCoachPacks)).toEqual(PackRoles.SCHEDULED_COACH_PACKS);
    expect(rules.legVehicles.length).toBeGreaterThan(0);
    for (const item of rules.legVehicles) {
      const label = `${item.routeType} ${item.pack} ${item.operator ?? "-"} ${item.line ?? "-"}: ${item.why}`;
      const mode = legMode(Number(item.routeType), item.pack);
      expect(mode, label).toBe(item.mode);
      if (!mode) continue;
      // A city line (a stop a walk from a station, `urban_walk`) is a metro, tram or funicular run.
      if (PackRoles.URBAN_PACKS.has(item.pack)) expect(isUrban(mode), label).toBe(true);
      const vehicle = vehicleOf(mode, item.operator, item.line);
      expect(vehicle, label).toBe(item.vehicle);
      // A bus or a city line is said with the label the iPhone shows for it; a train with its category and number.
      const said = serviceLabel("it-IT", { number: isUrban(vehicle) ? item.line ?? "" : "1234", category: item.line ?? "", vehicle });
      if (item.iosLabel) expect(said.startsWith(messagesForLocale("it-IT").timetable.vehicles[LABEL_KEYS[item.iosLabel]]), label).toBe(true);
      else expect(said, label).toBe(item.line ? `${item.line} 1234` : "1234");
      // A city run has a line, never a number.
      if (isUrban(vehicle)) expect(said, label).not.toContain("1234");
    }
  });

  it("names city lines and their labels as the apps do", () => {
    expect(rules.urbanLines.length).toBeGreaterThan(0);
    for (const item of rules.urbanLines)
      expect(urbanLine(item.published, item.mode as TimetableLegMode), `${item.mode} ${item.published}: ${item.why}`).toBe(item.line);
    expect(rules.urbanLabels.length).toBeGreaterThan(0);
    for (const item of rules.urbanLabels) {
      const label = `${item.label} ${item.line}: ${item.why}`;
      expect(urbanLineRepeatsMode(item.line, item.label), label).toBe(item.repeatsMode);
      expect(urbanLabel(item.label, item.line), label).toBe(item.shown);
    }
    expect(serviceLabel("it-IT", { number: "MEX", category: "MEX", vehicle: "METRO" })).toBe("Metro MEX");
    expect(serviceLabel("en-US", { number: "14", category: "14", vehicle: "TRAM" })).toBe("Tram 14");
    expect(serviceLabel("it-IT", { number: "funicolare", category: "funicolare", vehicle: "FUNICULAR" })).toBe("Funicolare");
  });

  it("names lines and operators as the apps do", () => {
    expect(lineLabel("0083_FR")).toBe("FR");
    expect(lineLabel("0083_BUSX1")).toBe("0083_BUSX1");
    expect(operatorName("Italo - Nuovo Trasporto Viaggiatori", "italo-gtfs")).toBe("Italo");
    expect(operatorName("SBB CFF FFS", "ch-otd-gtfs")).toBe("SBB");
    expect(operatorName("", "lombardia-trenord-gtfs")).toBe("Trenord");
    expect(busLabel("ItaloBus", "ITALOBUS", "ItaloBus")).toBe("ItaloBus");
    expect(busLabel("Bus sostitutivo", "REPLACEMENT_BUS", "S11")).toBe("Bus sostitutivo S11");
    expect(serviceLabel("it-IT", { number: "RE_80 - 25508", category: "RE80", vehicle: "TRAIN" })).toBe("RE80 25508");
  });
});
