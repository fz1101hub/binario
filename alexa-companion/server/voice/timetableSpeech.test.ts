import { describe, expect, it } from "vitest";
import type { TimetableService } from "../timetable/repository";
import { departureRows, departureSentences } from "./timetableSpeech";

/** A programmed service at `time` («HH:mm», Rome): a train, or a city run boarded `walkMinutes` from the station at `stop`. */
function service(time: string, vehicle: TimetableService["vehicle"], line: string, walkMinutes = 0, stop: string | null = null): TimetableService {
  return {
    id: `${time}-${line}`,
    number: vehicle === "TRAIN" ? "12025" : line,
    hasTrainNumber: vehicle === "TRAIN",
    operatorName: "Operator",
    category: vehicle === "TRAIN" ? "REG" : line,
    destination: "Destination",
    walkMinutes,
    boardingStop: stop,
    departure: 0,
    time,
    timeZone: "Europe/Rome",
    vehicle,
    attribution: "Fixture",
  };
}

const plain = (value: string) => value;

describe("city runs in the programmed answer", () => {
  it("says trains and city runs together: «partenze», each with its article, the walk, the platform and the city note", () => {
    const services = [service("09:04", "TRAIN", "REG"), service("09:07", "METRO", "MM", 5, "Principe Metro"), service("09:09", "TRAM", "14", 3, "Piazza")];
    expect(departureSentences("it-IT", services, plain, "Genova Piazza Principe")).toEqual([
      "Secondo l'orario programmato le prossime partenze sono il REG 12025 delle 9:04, la Metro MM delle 9:07 e il Tram 14 delle 9:09.",
      "Si sale alla fermata Principe Metro, a 5 minuti a piedi da Genova Piazza Principe.",
      "Si sale alla fermata Piazza, a 3 minuti a piedi da Genova Piazza Principe.",
      "Il binario non è indicato e lo stato in tempo reale dei treni è nell'app Binario.",
      "Per le corse urbane Binario ha solo l'orario programmato.",
    ]);
  });

  it("says each stop once, and for a saved route only the minutes, each once", () => {
    const services = [service("09:05", "METRO", "M2", 6, "Centrale FS"), service("09:08", "METRO", "M2", 6, "Centrale FS"), service("09:09", "FUNICULAR", "", 6, "Other")];
    const route = departureSentences("it-IT", services, plain);
    expect(route).toEqual([
      "Secondo l'orario programmato le prossime corse sono la Metro M2 delle 9:05, la Metro M2 delle 9:08 e la Funicolare delle 9:09.",
      "Si sale a una fermata a 6 minuti a piedi dalla stazione di partenza.",
      "Per le corse urbane Binario ha solo l'orario programmato.",
    ]);
    expect(route.join(" ")).not.toMatch(/Centrale|Other/);
    expect(departureSentences("it-IT", services, plain, "Milano Centrale").filter(sentence => sentence.startsWith("Si sale"))).toHaveLength(2);
  });

  it("lists the walk on the Echo Show rows of city runs only", () => {
    expect(departureRows("it-IT", [service("09:04", "TRAIN", "REG"), service("09:07", "METRO", "MEX", 4, "Bologna Centrale FS")])).toEqual([
      { minuteOfDay: 544, train: "REG 12025" },
      { minuteOfDay: 547, train: "Metro MEX", walkMinutes: 4 },
    ]);
  });
});
