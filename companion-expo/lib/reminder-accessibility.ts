import { createTranslator, type CompanionLocale } from "@/lib/i18n";

export function deleteRoutePrompt(name: string, locale?: CompanionLocale) { const t = createTranslator(locale); return { title: t("reminder.deleteTitle"), message: t("reminder.deleteBody", { name }), cancel: t("common.cancel"), confirm: t("reminder.deleteConfirm") }; }
export function reminderFeedbackPoliteness(message: string) { return /non|errore|impossibile/i.test(message) ? "assertive" as const : "polite" as const; }
