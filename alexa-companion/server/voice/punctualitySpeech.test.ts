import { formatMessage, messagesForLocale, SUPPORTED_LOCALES } from "@shared/i18n";
import { describe, expect, it } from "vitest";
import { mostPunctualDepartures, parsePunctualityStats, type PunctualDeparture } from "../punctuality/stats";
import { departuresDocument, ROMA_TERMINI, TIVOLI } from "../punctuality/testFixtures";
import { answerText, displayedTime, mostPunctualAnswer, spokenTime } from "./punctualitySpeech";
import { escapeSsmlText } from "./speech";

const plain = (value: string) => value;
/** Monday at 07:30: the 2157 at 8:12 (9 of 10), the 2161 at 9:12 (8 of 10), the 2165 at 10:12 (7 of 10). */
function mondayMorning(): PunctualDeparture[] {
  return mostPunctualDepartures(parsePunctualityStats(departuresDocument())!, [ROMA_TERMINI], [TIVOLI], { minuteOfDay: 450, isoWeekday: 1 })!;
}

describe("most punctual train answer", () => {
  it.each([
    [
      "it-IT",
      "Per la tratta Casa-Lavoro, fra i treni che di solito partono nelle prossime tre ore, il più puntuale è il 2157 delle 8:12, in orario 9 volte su 10. " +
        "Poi il 2161 delle 9:12, in orario 8 volte su 10 e il 2165 delle 10:12, in orario 7 volte su 10. " +
        "Sono statistiche non ufficiali di Binario su dati ViaggiaTreno.",
    ],
    [
      "en-US",
      "On the route Casa-Lavoro, of the trains that usually leave in the next three hours, the most punctual is train 2157 at 8:12 AM, on time 9 times out of 10. " +
        "Then train 2161 at 9:12 AM, on time 8 times out of 10 and train 2165 at 10:12 AM, on time 7 times out of 10. " +
        "These are unofficial Binario statistics from ViaggiaTreno data.",
    ],
    [
      "de-DE",
      "Auf der Strecke Casa-Lavoro ist von den Zügen, die üblicherweise in den nächsten drei Stunden abfahren, dieser am pünktlichsten: Zug 2157 um 8:12, 9-mal von 10 pünktlich. " +
        "Danach Zug 2161 um 9:12, 8-mal von 10 pünktlich und Zug 2165 um 10:12, 7-mal von 10 pünktlich. " +
        "Das sind inoffizielle Binario-Statistiken aus ViaggiaTreno-Daten.",
    ],
    [
      "fr-FR",
      "Sur le trajet Casa-Lavoro, parmi les trains qui partent habituellement dans les trois prochaines heures, le plus ponctuel est le 2157 de 8:12, à l’heure 9 fois sur 10. " +
        "Puis le 2161 de 9:12, à l’heure 8 fois sur 10 et le 2165 de 10:12, à l’heure 7 fois sur 10. " +
        "Ce sont des statistiques non officielles de Binario sur données ViaggiaTreno.",
    ],
    [
      "es-ES",
      "En la ruta Casa-Lavoro, de los trenes que suelen salir en las próximas tres horas, el más puntual es el 2157 de las 8:12, puntual 9 veces de cada 10. " +
        "Después, el 2161 de las 9:12, puntual 8 veces de cada 10 y el 2165 de las 10:12, puntual 7 veces de cada 10. " +
        "Son estadísticas no oficiales de Binario con datos de ViaggiaTreno.",
    ],
    [
      "pt-BR",
      "No trajeto Casa-Lavoro, entre os trens que costumam sair nas próximas três horas, o mais pontual é o 2157 das 8:12, no horário 9 vezes em 10. " +
        "Depois, o 2161 das 9:12, no horário 8 vezes em 10 e o 2165 das 10:12, no horário 7 vezes em 10. " +
        "São estatísticas não oficiais do Binario com dados do ViaggiaTreno.",
    ],
    [
      "ar-SA",
      "على المسار Casa-Lavoro، من بين القطارات التي تغادر عادةً خلال الساعات الثلاث القادمة، القطار الأكثر دقة في المواعيد هو القطار 2157 الساعة 8:12 ص، في الموعد 9 مرات من كل 10. " +
        "ثم القطار 2161 الساعة 9:12 ص، في الموعد 8 مرات من كل 10 والقطار 2165 الساعة 10:12 ص، في الموعد 7 مرات من كل 10. " +
        "هذه إحصاءات غير رسمية من بيناريو استنادًا إلى بيانات ViaggiaTreno.",
    ],
    [
      "hi-IN",
      "रूट Casa-Lavoro पर, अगले तीन घंटों में आम तौर पर छूटने वाली ट्रेनों में सबसे समयबद्ध यह है: 8:12 am वाली ट्रेन 2157, जो 10 में से 9 बार समय पर पहुँची। " +
        "उसके बाद 9:12 am वाली ट्रेन 2161, जो 10 में से 8 बार समय पर पहुँची और 10:12 am वाली ट्रेन 2165, जो 10 में से 7 बार समय पर पहुँची। " +
        "ये ViaggiaTreno डेटा पर आधारित बिनारियो के अनौपचारिक आँकड़े हैं।",
    ],
    [
      "ja-JP",
      "Casa-Lavoro では、これから 3 時間以内にふだん出発する電車のうち、一番時間に正確なのは 8:12 発の 2157 号、定刻どおり 10 回中 9 回です。" +
        "次は 9:12 発の 2161 号、定刻どおり 10 回中 8 回、10:12 発の 2165 号、定刻どおり 10 回中 7 回です。" +
        "これは ViaggiaTreno のデータに基づくビナリオの非公式統計です。",
    ],
  ] as const)("%s: names the best train and two more, with usual time and share out of ten, then the source", (locale, expected) => {
    const answer = mostPunctualAnswer(locale, "Casa-Lavoro", { kind: "trains", trains: mondayMorning() }, plain);
    // Some runtimes write a narrow no-break space before AM/PM: the answer has plain spaces.
    expect(answerText(locale, answer)).toBe(expected);
  });

  it.each(SUPPORTED_LOCALES)("%s: says every train, its time and share, attributes the statistics and lists them for the screen", locale => {
    const trains = mondayMorning();
    const answer = mostPunctualAnswer(locale, "Casa-Lavoro", { kind: "trains", trains }, plain);
    const text = answerText(locale, answer);
    expect(answer.attributed).toBe(true);
    expect(text.endsWith(messagesForLocale(locale).punctuality.source)).toBe(true);
    expect(text).toContain("Casa-Lavoro");
    for (const train of trains) {
      expect(text).toContain(train.number);
      expect(text).toContain(spokenTime(locale, train.minuteOfDay));
    }
    expect(text).not.toMatch(/[{}]|\u202f|\u00a0/);
    expect(text).not.toMatch(/Roma|Tivoli/);
    expect(answer.trains).toEqual([
      { trainNumber: "2157", minuteOfDay: 492, percent: 90 },
      { trainNumber: "2161", minuteOfDay: 552, percent: 80 },
      { trainNumber: "2165", minuteOfDay: 612, percent: 70 },
    ]);
  });

  it.each(SUPPORTED_LOCALES)("%s: says clearly when no train with enough observations leaves in the next three hours", locale => {
    const p = messagesForLocale(locale).punctuality;
    for (const outcome of [{ kind: "none" } as const, { kind: "trains", trains: [] } as const]) {
      const answer = mostPunctualAnswer(locale, "Casa-Lavoro", outcome, plain);
      expect(answer).toEqual({ sentences: [formatMessage(p.mostPunctualNone, { routeName: "Casa-Lavoro" })], attributed: false });
    }
    expect(mostPunctualAnswer(locale, "Casa-Lavoro", { kind: "stationsUnknown" }, plain).sentences).toEqual([
      formatMessage(p.routeStationsUnknown, { routeName: "Casa-Lavoro" }),
    ]);
  });

  it("speaks a single train without the «then» sentence", () => {
    const [best] = mondayMorning();
    expect(answerText("it-IT", mostPunctualAnswer("it-IT", "Mare", { kind: "trains", trains: [best] }, plain))).toBe(
      "Per la tratta Mare, fra i treni che di solito partono nelle prossime tre ore, il più puntuale è il 2157 delle 8:12, in orario 9 volte su 10. " +
        "Sono statistiche non ufficiali di Binario su dati ViaggiaTreno."
    );
  });

  it("makes the article agree with the hour as the locale writes it", () => {
    const [best] = mondayMorning();
    const at = (locale: (typeof SUPPORTED_LOCALES)[number], minuteOfDay: number, tenths = 1) =>
      mostPunctualAnswer(locale, "Mare", { kind: "trains", trains: [{ ...best, minuteOfDay, tenths }] }, plain).sentences[0];
    expect(at("it-IT", 65)).toContain("il 2157 dell'1:05, in orario 1 volta su 10");
    expect(at("it-IT", 13 * 60 + 5)).toContain("il 2157 delle 13:05");
    expect(at("it-IT", 5)).toContain("il 2157 delle 0:05");
    expect(at("es-ES", 65)).toContain("el 2157 de la 1:05, puntual 1 vez de cada 10");
    expect(at("es-ES", 13 * 60 + 5)).toContain("el 2157 de las 13:05");
    // 12-hour Spanish: 13:05 is «la 1:05 p.m.».
    expect(at("es-US", 13 * 60 + 5)).toContain("el 2157 de la 1:05 p.m.");
    expect(at("es-US", 21 * 60)).toContain("el 2157 de las 9:00 p.m.");
    expect(at("pt-BR", 65)).toContain("o 2157 da 1:05, no horário 1 vez em 10");
    expect(at("pt-BR", 21 * 60)).toContain("o 2157 das 21:00");
    expect(at("en-US", 0, 10)).toContain("train 2157 at 12:00 AM, on time 10 times out of 10");
    expect(at("ar-SA", 8 * 60 + 12, 2)).toContain("في الموعد 2 مرتين من كل 10");
  });

  it("escapes the route name and the file's train number for SSML, and keeps them plain for the card", () => {
    const [best] = mondayMorning();
    const trains = [{ ...best, number: "21<57>&" }];
    const ssml = answerText("it-IT", mostPunctualAnswer("it-IT", "Casa & <lavoro>", { kind: "trains", trains }, escapeSsmlText));
    expect(ssml).toContain("Casa &amp; &lt;lavoro&gt;");
    expect(ssml).toContain("il 21&lt;57&gt;&amp; delle 8:12");
    const card = answerText("it-IT", mostPunctualAnswer("it-IT", "Casa & <lavoro>", { kind: "trains", trains }, plain));
    expect(card).toContain("Casa & <lavoro>");
    expect(card).toContain("il 21<57>& delle 8:12");
  });

  it("writes the spoken time in Latin digits and the screen time in the locale's own", () => {
    expect(spokenTime("ar-SA", 8 * 60 + 12)).toBe("8:12 ص");
    expect(displayedTime("ar-SA", 8 * 60 + 12)).toMatch(/^[٠-٩]+:[٠-٩]+/);
    expect(spokenTime("en-US", 20 * 60 + 5)).toBe("8:05 PM");
    expect(spokenTime("it-IT", 20 * 60 + 5)).toBe("20:05");
    expect(displayedTime("it-IT", 20 * 60 + 5)).toBe("20:05");
  });
});
