function pad(value: number) { return String(value).padStart(2, "0"); }

export function toLocalIsoDate(date: Date) { return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`; }
export function toLocalTime(date: Date) { return `${pad(date.getHours())}:${pad(date.getMinutes())}`; }

export function fromLocalDateAndTime(dateText: string, timeText = "00:00") {
  const [year, month, day] = dateText.split("-").map(Number);
  const [hours, minutes] = timeText.split(":").map(Number);
  const result = new Date(year, (month || 1) - 1, day || 1, hours || 0, minutes || 0, 0, 0);
  return Number.isFinite(result.getTime()) ? result : new Date();
}

function deviceLocales() { return typeof Intl !== "undefined" ? Intl.DateTimeFormat().resolvedOptions().locale : "it-IT"; }

export function formatLocalJourneyDate(dateText: string, locales: string | string[] = deviceLocales()) {
  return new Intl.DateTimeFormat(locales, { dateStyle: "medium" }).format(fromLocalDateAndTime(dateText));
}

export function formatLocalJourneyTime(timeText: string, locales: string | string[] = deviceLocales()) {
  return new Intl.DateTimeFormat(locales, { hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(fromLocalDateAndTime("2000-01-01", timeText));
}

export function formatLocalJourneySchedule(dateText: string, timeText: string, locales: string | string[] = deviceLocales()) {
  return `${formatLocalJourneyDate(dateText, locales)} · ${formatLocalJourneyTime(timeText, locales)}`;
}
