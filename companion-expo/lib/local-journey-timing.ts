import AsyncStorage from "@react-native-async-storage/async-storage";

export const localJourneyReminderOptions = [0, 10, 15, 30, 60] as const;
export type LocalJourneyReminderPreference = (typeof localJourneyReminderOptions)[number];
const storageKey = "binario.local-journey-timing.v1";

export async function loadLocalJourneyTimingPreferences(): Promise<{ preferredReminderMinutes: LocalJourneyReminderPreference }> {
  try {
    const value: unknown = JSON.parse((await AsyncStorage.getItem(storageKey)) ?? "{}");
    const preferred = (value as { preferredReminderMinutes?: unknown }).preferredReminderMinutes;
    return { preferredReminderMinutes: localJourneyReminderOptions.includes(preferred as LocalJourneyReminderPreference) ? preferred as LocalJourneyReminderPreference : 15 };
  } catch { return { preferredReminderMinutes: 15 }; }
}

export async function saveLocalJourneyTimingPreferences(preferredReminderMinutes: LocalJourneyReminderPreference) {
  if (!localJourneyReminderOptions.includes(preferredReminderMinutes)) return false;
  await AsyncStorage.setItem(storageKey, JSON.stringify({ preferredReminderMinutes }));
  return true;
}
