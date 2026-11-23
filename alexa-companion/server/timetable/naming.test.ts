import { readFileSync } from "node:fs";
import path from "node:path";
import { messagesForLocale } from "@shared/i18n";
import { describe, expect, it } from "vitest";
import { PackRoles } from "@shared/generated/sharedData";
import { busLabel, legMode, lineLabel, operatorName, vehicleOf } from "./naming";
import { serviceLabel } from "../voice/timetableSpeech";

/**
 * contracts/shared-rules/v1/journey-leg-vehicles-v1.json: what a service of the timetable travels on,
 * as Android (JourneyLegVehiclesContractTest.kt) and the iPhone (SharedRulesContractTests.swift) read it.
 */
const contract = path.resolve(import.meta.dirname, "..", "..", "..", "contracts", "shared-rules", "v1", "journey-leg-vehicles-v1.json");
type LegVehicles = {
  scheduledCoachPacks: string[];
  legVehicles: Array<{ routeType: string; pack: string; operator: string | null; line: string | null; mode: string | null; vehicle: string | null; iosLabel: string | null; why: string }>;
};
const rules = JSON.parse(readFileSync(contract, "utf8")) as LegVehicles;

/** The iPhone's label keys and the skill's texts of the same vehicles. */
const LABEL_KEYS: Record<string, keyof ReturnType<typeof messagesForLocale>["timetable"]["vehicles"]> = {
  "result.vehicle.bus_trenitalia": "busTrenitalia",
  "result.vehicle.freccialink": "freccialink",
  "result.vehicle.italobus": "italobus",
  "result.vehicle.bus": "bus",
  "result.vehicle.replacement_bus": "replacementBus",
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
      const vehicle = vehicleOf(mode, item.operator, item.line);
      expect(vehicle, label).toBe(item.vehicle);
      // A bus is said with the label the iPhone shows for it; a train with its category and number.
      const said = serviceLabel("it-IT", { number: "1234", category: item.line ?? "", vehicle });
      if (item.iosLabel) expect(said.startsWith(messagesForLocale("it-IT").timetable.vehicles[LABEL_KEYS[item.iosLabel]]), label).toBe(true);
      else expect(said, label).toBe(item.line ? `${item.line} 1234` : "1234");
    }
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
