import { createTranslator, type CompanionLocale } from "@/lib/i18n";

export function deleteRoutePrompt(name: string, locale?: CompanionLocale) { const t = createTranslator(locale); return { title: t("reminder.deleteTitle"), message: t("reminder.deleteBody", { name }), cancel: t("common.cancel"), confirm: t("reminder.deleteConfirm") }; }

export type FeedbackSeverity = "info" | "error";
export type ScreenFeedback = { text: string; severity: FeedbackSeverity };

/** The live-region urgency comes from the severity each caller states, never from the words of a translated message. */
export function feedbackPoliteness(severity: FeedbackSeverity) { return severity === "error" ? "assertive" as const : "polite" as const; }

/**
 * Android reads a changed live region by itself, so announcing the same text as well reads it twice;
 * iOS has no live regions and needs the explicit announcement.
 */
export function shouldAnnounceFeedback(platform: string) { return platform === "ios"; }
