import { createTranslator, type CompanionLocale } from "@/lib/i18n";

export function getCompanionGuidance(simpleMode: boolean, locale?: CompanionLocale) {
  const t = createTranslator(locale);
  return simpleMode
    ? { intro: t("simple.intro"), scheduledTitle: t("simple.title"), scheduledBody: t("simple.body") }
    : { intro: t("full.intro"), scheduledTitle: t("full.title"), scheduledBody: t("full.body") };
}
