import {
  formatMessage,
  messagesForLocale,
  resolveLocale,
  type InterpolationValues,
  type LocaleMessages,
  type SupportedLocale,
} from "@shared/i18n";
import { createContext, useContext, useEffect, useMemo } from "react";

type LocaleContextValue = {
  locale: SupportedLocale;
  messages: LocaleMessages;
  format: (template: string, values?: InterpolationValues) => string;
};

const italianLocale = resolveLocale(undefined);
const LocaleContext = createContext<LocaleContextValue>({
  locale: italianLocale,
  messages: messagesForLocale(italianLocale),
  format: formatMessage,
});

export function LocaleProvider({ children }: { children: React.ReactNode }) {
  const locale = resolveLocale(
    typeof navigator === "undefined" ? undefined : navigator.languages
  );
  const messages = messagesForLocale(locale);
  const value = useMemo(
    () => ({ locale, messages, format: formatMessage }),
    [locale, messages]
  );

  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = messages.dashboard.documentTitle;
  }, [locale, messages.dashboard.documentTitle]);

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale() {
  return useContext(LocaleContext);
}
