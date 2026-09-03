export type LocalDictationDraft = { origin: string; destination: string; travelDate: string; travelTime: string };
export function parseLocalDictation(input: string, now = new Date()): LocalDictationDraft {
  const text = input.trim().replace(/\s+/g, " ");
  const route = /(?:da\s+)(.+?)(?:\s+(?:a|verso)\s+)(.+?)(?=(?:\s+(?:domani|oggi|il\s+\d{1,2}[/.\-]\d{1,2}(?:[/.\-]\d{2,4})?|alle\s+\d{1,2}(?::\d{2})?))|$)/i.exec(text);
  const time = /(?:alle\s+)([01]?\d|2[0-3])(?::([0-5]\d))?/i.exec(text);
  const date = /\b(domani|oggi)\b/i.exec(text);
  const day = new Date(now); if (date?.[1].toLowerCase() === "domani") day.setDate(day.getDate() + 1);
  const travelDate = date ? `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, "0")}-${String(day.getDate()).padStart(2, "0")}` : "";
  return { origin: route?.[1]?.trim() ?? "", destination: route?.[2]?.trim() ?? "", travelDate, travelTime: time ? `${time[1].padStart(2, "0")}:${time[2] ?? "00"}` : "" };
}
