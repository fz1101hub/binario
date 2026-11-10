import type { TranslationKey } from "@/lib/i18n";

/**
 * The home-screen widget, the Wear OS tile and Android's deferred alarms exist only in the Android build:
 * the iOS build has no widget or watch target, and App Review rejects text that names other platforms.
 */
export function hasAndroidSurfaces(platform: string) {
  return platform === "android";
}

const textWithoutAndroidSurfaces = {
  "alerts.reminderScheduled": "alerts.reminderScheduledIos",
  "alerts.agendaSimple": "alerts.agendaSimpleIos",
  "alerts.agendaFull": "alerts.agendaFullIos",
  "alerts.reminderHint": "alerts.reminderHintIos",
  "hub.intro": "hub.introIos",
  "hub.noJourney": "hub.noJourneyIos",
  "guide.expired.body": "guide.expired.bodyIos",
  "guide.unavailable.body": "guide.unavailable.bodyIos",
  "home.planExpired": "home.planExpiredIos",
} as const satisfies Partial<Record<TranslationKey, TranslationKey>>;

export type PlatformDependentTextKey = keyof typeof textWithoutAndroidSurfaces;

/** The Android text, or its variant that mentions neither Android, Wear OS, the widget nor the watch. */
export function platformTextKey(key: PlatformDependentTextKey, platform: string): TranslationKey {
  return hasAndroidSurfaces(platform) ? key : textWithoutAndroidSurfaces[key];
}
