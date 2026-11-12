import { ar } from "./locales/ar";
import { hi } from "./locales/hi";
import { id } from "./locales/id";
import { ja } from "./locales/ja";
import { ko } from "./locales/ko";
import { my } from "./locales/my";
import { pt } from "./locales/pt";
import { ru } from "./locales/ru";
import { th } from "./locales/th";
import { tr } from "./locales/tr";
import { vi } from "./locales/vi";
import { zh } from "./locales/zh";

/**
 * Alexa publishing locales: skill.json, the interaction models and the voice/APL responses
 * cover exactly these. Alexa offers no Indonesian, Korean, Burmese, Russian, Thai, Turkish,
 * Vietnamese or Chinese skill locale (docs/l10n/LOCALES.md).
 */
export const SUPPORTED_LOCALES = [
  "it-IT",
  "en-US",
  "en-GB",
  "en-IN",
  "de-DE",
  "fr-FR",
  "es-ES",
  "es-US",
  "ar-SA",
  "hi-IN",
  "ja-JP",
  "pt-BR",
] as const;

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

/** Web dashboard and OAuth consent pages: every Alexa locale plus the web-only languages. */
export const WEB_LOCALES = [
  ...SUPPORTED_LOCALES,
  "id-ID",
  "ko-KR",
  "my-MM",
  "ru-RU",
  "th-TH",
  "tr-TR",
  "vi-VN",
  "zh-Hans",
] as const;

export type WebLocale = (typeof WEB_LOCALES)[number];
type Language =
  | "it" | "en" | "de" | "fr" | "es"
  | "ar" | "hi" | "id" | "ja" | "ko" | "my" | "pt" | "ru" | "th" | "tr" | "vi" | "zh";
export type InterpolationValues = Record<string, string | number>;
/** "one" and "other" are mandatory; languages whose CLDR rules need more forms add them. */
export type PluralMessages = { one: string; other: string } & Partial<
  Record<"zero" | "two" | "few" | "many", string>
>;

export type LocaleMessages = {
  dashboard: {
    documentTitle: string;
    navigation: string;
    menu: string;
    signInTitle: string;
    signInDescription: string;
    signIn: string;
    signOut: string;
    accountDeletedTitle: string;
    accountDeletedDescription: string;
    productName: string;
    heroTitle: string;
    heroDescription: string;
    liveDataNotConnected: string;
    personalRoutes: string;
    personalRoutesDescription: string;
    routeName: string;
    routeNamePlaceholder: string;
    origin: string;
    originPlaceholder: string;
    destination: string;
    destinationPlaceholder: string;
    pinRoute: string;
    save: string;
    loadingRoutes: string;
    /** The routes or the Alexa status could not be read. */
    loadFailed: string;
    noRoutes: string;
    unpinRoute: string;
    pinNamedRoute: string;
    removeRoute: string;
    removeRouteConfirmTitle: string;
    removeRouteConfirmDescription: string;
    alexa: string;
    alexaDescription: string;
    alexaConfigured: string;
    alexaNotConfigured: string;
    accountStatus: string;
    accountLinked: string;
    accountNotLinked: string;
    unlinkAlexa: string;
    linkingInstructions: string;
    dataGuarantee: string;
    dataGuaranteeDescription: string;
    androidAssistant: string;
    androidAssistantDescription: string;
    activeRouteDeepLink: string;
    accountAndData: string;
    accountAndDataDescription: string;
    deleteAccount: string;
    deleteAccountConfirmTitle: string;
    deleteAccountConfirmDescription: string;
    deleteAccountConfirmationContract: string;
    cancel: string;
    deletePermanently: string;
    deleteFailed: string;
    routeInvalid: string;
    /** The account already keeps the most routes allowed (32). */
    routeLimitReached: string;
    routeSaveFailed: string;
    /** Pinning, removing a route or unlinking Alexa failed on the server. */
    actionFailed: string;
    notFoundTitle: string;
    notFoundDescription: string;
    notFoundMoved: string;
    goHome: string;
    unexpectedError: string;
    reloadPage: string;
  };
  accessibility: {
    close: string;
    loading: string;
    pagination: string;
    previousPage: string;
    nextPage: string;
    morePages: string;
    previousSlide: string;
    nextSlide: string;
    breadcrumb: string;
    more: string;
    mobileSidebar: string;
    mobileSidebarDescription: string;
  };
  consent: {
    title: string;
    signedInAs: string;
    description: string;
    /** The authorize URL is not secret: link only a request the user started from their own Alexa app just now. */
    ownRequestOnly: string;
    allow: string;
    deny: string;
    loginRequired: string;
    errorTitle: string;
    notConfigured: string;
    invalidRequest: string;
    invalidCsrf: string;
    returnDashboard: string;
  };
  voice: {
    cardTitle: string;
    accountLinkRequired: string;
    unsupported: string;
    help: string;
    goodbye: string;
    launch: string;
    noRoutes: string;
    routeSubject: string;
    trainSubject: string;
    liveDataUnavailable: string;
    routesSaved: string;
    moreRoutes: PluralMessages;
    error: string;
    /** Spoken when the user stays silent after the launch greeting. */
    launchReprompt: string;
    /** Sentence terminator after a spoken route list ("." / "।" / "。"; empty for Thai). */
    listTerminator: string;
    routeNotFound: string;
    routeAmbiguous: string;
    /** Asks which route; {example} names one inside a NextTrainsIntent sample of the locale. */
    routeChoice: string;
  };
  /** Echo Show (APL) labels; the spoken caveat stays voice.liveDataUnavailable. */
  screen: {
    homeTitle: string;
    departuresTitle: string;
    trainStatusTitle: string;
    myRoutesTitle: string;
    demoData: string;
    sourceNone: string;
    sourceDemo: string;
    checkedAt: string;
    noTimetable: string;
    trainNumber: string;
    trainNumberMissing: string;
    pinned: string;
    moreRoutes: PluralMessages;
    showRoute: string;
    nextTrainsAction: string;
    myRoutesAction: string;
    hintsTitle: string;
    hintNextTrains: string;
    hintTrainStatus: string;
    hintMyRoutes: string;
  };
  /**
   * Punctuality answers from the unofficial Binario statistics on ViaggiaTreno data, and their
   * Echo Show screen. Plurals select on {count}; {limit}, {days} and {trains} are the formatted
   * `minutes`, `observedDays` and `trainCount` (or `mixedLimit`).
   */
  punctuality: {
    /** {count} = percentage on time at the terminus; {trainNumber}, {limit}, {days}. */
    train: PluralMessages;
    /** {count} = percentage on time at the destination; {routeName}, {trains}, {days}, {limit}. */
    route: PluralMessages;
    /** {limit} of a route whose trains have different limits: 5 minutes for high speed, 15 for the others. */
    mixedLimit: string;
    meanDelay: PluralMessages;
    noMeanDelay: string;
    cancelled: PluralMessages;
    cancelledEveryDay: string;
    /** The attribution every answer ends with. */
    source: string;
    unavailable: string;
    trainUnknown: string;
    trainFewData: string;
    /** Several trains with the number, from different origins, have figures: none is given. */
    trainSharedNumber: string;
    /** Asks which train, naming one inside a TrainPunctualityIntent sample of the locale. */
    trainMissing: string;
    routeFewData: string;
    routeStationsUnknown: string;
    /** Asks which route; {example} names one inside a RoutePunctualityIntent sample of the locale. */
    routeChoice: string;
    minutes: PluralMessages;
    observedDays: PluralMessages;
    trainCount: PluralMessages;
    screenTitle: string;
    statisticsLabel: string;
    statisticsNotLive: string;
    statisticsUntil: string;
    onTimeLabel: string;
    meanDelayLabel: string;
    observedDaysLabel: string;
    trainsLabel: string;
    minutesValue: string;
    /** Accessibility label of a saved route on the punctuality "which route" screen. */
    showRoute: string;
    hint: string;
    /**
     * The most punctual train that usually leaves a saved route's origin in the next three hours:
     * {routeName} and {train}, a `mostPunctualTrain`.
     */
    mostPunctualBest: string;
    /** The next ones: {trains}, a list of `mostPunctualTrain`. */
    mostPunctualOthers: string;
    /**
     * One train of that answer, selected on the hour as {time} writes it, so an article can agree
     * with it («dell'1:05», «de la 1:05»); {trainNumber}, {time} (its usual departure) and
     * {onTime}, an `outOfTen`.
     */
    mostPunctualTrain: PluralMessages;
    /**
     * Said after the trains, and added to the screen's data line, outside Italian: the usual times
     * are Italian wall-clock times, not the listener's.
     */
    mostPunctualItalianTime: string;
    /** {count} = arrivals on time at the destination, in tenths (0–10). */
    outOfTen: PluralMessages;
    /** No train with enough observations leaves in the next three hours; {routeName}. */
    mostPunctualNone: string;
    /** Asks which route; {example} names one inside a MostPunctualTrainIntent sample of the locale. */
    mostPunctualRouteChoice: string;
    /** Accessibility label of a saved route on the most-punctual "which route" screen. */
    showRouteMostPunctual: string;
    /** Echo Show hint: a MostPunctualTrainIntent sample with the locale's invocation name. */
    mostPunctualHint: string;
  };
};

const it: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion",
    navigation: "Alterna navigazione",
    menu: "Menu",
    signInTitle: "Accedi a Binario",
    signInDescription: "Questa dashboard gestisce solo le tue tratte e le impostazioni vocali personali.",
    signIn: "Accedi",
    signOut: "Esci",
    accountDeletedTitle: "Account eliminato",
    accountDeletedDescription: "Il profilo Binario Companion, le tratte e le credenziali Alexa associate sono stati cancellati dal database operativo. Una vecchia sessione non può ricreare l’account.",
    productName: "Binario Alexa Companion",
    heroTitle: "Collega Alexa alla tua app Binario.",
    heroDescription: "Questa è una superficie tecnica di collegamento: l’app smartphone resta il prodotto principale e la fonte delle tue tratte. Alexa accede ai dati personali solo dopo un consenso verificato.",
    liveDataNotConnected: "Dati live non collegati",
    personalRoutes: "Tratte personali",
    personalRoutesDescription: "Conservate nel tuo profilo Binario. Nessuna cronologia di viaggio o posizione viene registrata.",
    routeName: "Nome",
    routeNamePlaceholder: "Casa — lavoro",
    origin: "Origine",
    originPlaceholder: "Roma Termini",
    destination: "Destinazione",
    destinationPlaceholder: "Tivoli",
    pinRoute: "Fissa la tratta",
    save: "Salva",
    loadingRoutes: "Caricamento tratte…",
    loadFailed: "Caricamento non riuscito. Ricarica la pagina.",
    noRoutes: "Non hai ancora salvato tratte. Aggiungine una per preparare le future richieste vocali.",
    unpinRoute: "Rimuovi {routeName} dalle tratte fissate",
    pinNamedRoute: "Fissa {routeName}",
    removeRoute: "Rimuovi la tratta {routeName}",
    removeRouteConfirmTitle: "Rimuovere la tratta {routeName}?",
    removeRouteConfirmDescription: "Alexa non potrà più usarla per le risposte personali. L’operazione non può essere annullata.",
    alexa: "Alexa",
    alexaDescription: "Account linking OAuth 2.0 e webhook firmato.",
    alexaConfigured: "Configurazione server disponibile. Abilita la skill nell’app Alexa per avviare il collegamento.",
    alexaNotConfigured: "Non attivo. Manca la configurazione della console Alexa; il webhook rifiuta ogni richiesta.",
    accountStatus: "Stato account",
    accountLinked: "Collegato e verificato.",
    accountNotLinked: "Non collegato. Le risposte personali restano bloccate.",
    unlinkAlexa: "Scollega account Alexa",
    linkingInstructions: "Dopo aver configurato la skill, attivala nell’app Alexa: il collegamento si avvia solo dalla procedura OAuth ufficiale di Alexa.",
    dataGuarantee: "Garanzia sul dato ferroviario",
    dataGuaranteeDescription: "Finché non è configurata una fonte ferroviaria autorizzata, Binario risponde esattamente: “{liveDataUnavailable}”.",
    androidAssistant: "Google Assistant su Android",
    androidAssistantDescription: "Le App Actions possono aprire Binario sul telefono o su Wear OS. Non sostituiscono una Custom Skill conversazionale su Google Home.",
    activeRouteDeepLink: "Prova deep link tratta attiva",
    accountAndData: "Account e dati",
    accountAndDataDescription: "Lo scollegamento Alexa revoca soltanto l’accesso vocale. La cancellazione elimina invece il profilo Companion e i dati associati dal database operativo.",
    deleteAccount: "Elimina account e dati",
    deleteAccountConfirmTitle: "Eliminare definitivamente l’account?",
    deleteAccountConfirmDescription: "Verranno eliminate tratte, preferenze, dispositivi Companion e credenziali Alexa dal database operativo. L’operazione non può essere annullata; la retention di eventuali log e backup dipende dalla policy del titolare.",
    deleteAccountConfirmationContract: "Per sicurezza, il comando di conferma inviato al server resta “ELIMINA” in tutte le lingue.",
    cancel: "Annulla",
    deletePermanently: "Elimina definitivamente",
    deleteFailed: "Cancellazione non riuscita. Nessun dato è stato segnalato come eliminato: riprova.",
    routeInvalid: "Tratta non salvata: nome, origine e destinazione devono avere almeno 2 caratteri, e origine e destinazione devono essere diverse.",
    routeLimitReached: "Tratta non salvata: hai già il numero massimo di tratte. Rimuovine una per aggiungerne un’altra.",
    routeSaveFailed: "Salvataggio non riuscito. Riprova.",
    actionFailed: "Modifica non riuscita: non è cambiato nulla. Riprova.",
    notFoundTitle: "Pagina non trovata",
    notFoundDescription: "La pagina che stai cercando non esiste.",
    notFoundMoved: "Potrebbe essere stata spostata o eliminata.",
    goHome: "Vai alla home",
    unexpectedError: "Si è verificato un errore imprevisto.",
    reloadPage: "Ricarica pagina",
  },
  accessibility: {
    close: "Chiudi", loading: "Caricamento", pagination: "Paginazione", previousPage: "Vai alla pagina precedente", nextPage: "Vai alla pagina successiva", morePages: "Altre pagine", previousSlide: "Diapositiva precedente", nextSlide: "Diapositiva successiva", breadcrumb: "Percorso di navigazione", more: "Altro", mobileSidebar: "Barra laterale", mobileSidebarDescription: "Mostra la barra laterale mobile.",
  },
  consent: {
    title: "Collega Alexa a Binario",
    signedInAs: " come <strong>{userName}</strong>",
    description: "Stai per collegare Amazon Alexa al tuo account Binario{signedInAs}. Alexa potrà leggere i nomi delle tratte che hai salvato. Nessun dato in tempo reale viene condiviso.",
    ownRequestOnly: "Continua solo se hai avviato tu questo collegamento dall’app Alexa poco fa; altrimenti annulla.",
    allow: "Collega", deny: "Annulla", loginRequired: "Accedi prima alla dashboard Binario e ripeti il collegamento Alexa.",
    errorTitle: "Collegamento Alexa non riuscito",
    notConfigured: "L’integrazione Alexa non è configurata. Nessun account può essere collegato.",
    invalidRequest: "La richiesta di collegamento non è valida o non è più disponibile. Riavvia la procedura dall’app Alexa.",
    invalidCsrf: "La verifica di sicurezza è scaduta o non è valida. Nessun account è stato collegato; riavvia la procedura dall’app Alexa.",
    returnDashboard: "Torna alla dashboard",
  },
  voice: {
    cardTitle: "Binario",
    accountLinkRequired: "Per usare le informazioni personali, collega prima il tuo account Binario nell'app Alexa.",
    unsupported: "Non ho capito la richiesta. Puoi chiedere quanto è puntuale un treno o una tua tratta, qual è il treno più puntuale delle prossime tre ore oppure le tue tratte.",
    help: "Con Binario puoi chiedere i prossimi treni di una tratta salvata, lo stato di un treno, quanto è puntuale un treno o una tua tratta, il treno più puntuale delle prossime tre ore su una tua tratta, oppure l'elenco delle tue tratte. Cosa vuoi sapere?",
    goodbye: "A presto.",
    launch: "Ti diamo il benvenuto in Binario. Puoi chiedere quanto è puntuale un treno o una tua tratta, qual è il treno più puntuale delle prossime tre ore oppure le tue tratte.",
    noRoutes: "Non hai ancora salvato alcuna tratta in Binario.",
    routeSubject: "Per la tratta {routeName}", trainSubject: "Per il treno {trainNumber}",
    liveDataUnavailable: "dati non disponibili in tempo reale",
    routesSaved: "Le tue tratte salvate sono: {routes}{suffix}",
    moreRoutes: { one: ". C’è un’altra tratta nella dashboard Binario.", other: ". Ci sono altre {count} tratte nella dashboard Binario." },
    error: "Si è verificato un problema. Riprova più tardi.",
    launchReprompt: "Cosa vuoi sapere? Puoi chiedere quanto è puntuale un treno o una tua tratta, qual è il treno più puntuale delle prossime tre ore oppure le tue tratte.",
    listTerminator: ".",
    routeNotFound: "Non ho trovato la tratta {routeName}.",
    routeAmbiguous: "Ho trovato più tratte: {routes}.",
    routeChoice: "Quale tratta ti interessa? Puoi dire, per esempio: prossimi treni per {example}.",
  },
  screen: {
    homeTitle: "Cosa puoi chiedere",
    departuresTitle: "Partenze",
    trainStatusTitle: "Stato treno",
    myRoutesTitle: "Le mie tratte",
    demoData: "Catalogo dimostrativo locale — non live",
    sourceNone: "Fonte: nessun provider ferroviario in tempo reale collegato",
    sourceDemo: "Fonte: dati dimostrativi, non operativi",
    checkedAt: "Verificato alle {time}",
    noTimetable: "Nessun orario mostrato: Binario non inventa partenze, ritardi o binari.",
    trainNumber: "Treno {trainNumber}",
    trainNumberMissing: "Numero treno non indicato",
    pinned: "Fissata",
    moreRoutes: { one: "Un’altra tratta è nella dashboard Binario.", other: "Altre {count} tratte sono nella dashboard Binario." },
    showRoute: "Prossimi treni per {routeName}",
    nextTrainsAction: "Prossimi treni",
    myRoutesAction: "Le mie tratte",
    hintsTitle: "Prova a chiedere",
    hintNextTrains: "«Alexa, chiedi a Binario Treni i prossimi treni»",
    hintTrainStatus: "«Alexa, chiedi a Binario Treni lo stato del treno 9512»",
    hintMyRoutes: "«Alexa, chiedi a Binario Treni le mie tratte»",
  },
  punctuality: {
    train: { one: "Il treno {trainNumber} è arrivato al capolinea in orario, cioè entro {limit}, {count} volta su 100 in {days}.", many: "Il treno {trainNumber} è arrivato al capolinea in orario, cioè entro {limit}, {count} volte su 100 in {days}.", other: "Il treno {trainNumber} è arrivato al capolinea in orario, cioè entro {limit}, {count} volte su 100 in {days}." },
    route: { one: "Sulla tratta {routeName}, con {trains} in {days}, gli arrivi a destinazione sono stati in orario, cioè entro {limit}, {count} volta su 100.", many: "Sulla tratta {routeName}, con {trains} in {days}, gli arrivi a destinazione sono stati in orario, cioè entro {limit}, {count} volte su 100.", other: "Sulla tratta {routeName}, con {trains} in {days}, gli arrivi a destinazione sono stati in orario, cioè entro {limit}, {count} volte su 100." },
    mixedLimit: "5 minuti per Frecce, Italo, EuroCity ed EuroNight e 15 minuti per gli altri treni",
    meanDelay: { one: "Il ritardo medio è stato di {count} minuto.", many: "Il ritardo medio è stato di {count} minuti.", other: "Il ritardo medio è stato di {count} minuti." },
    noMeanDelay: "In media non c’è stato ritardo.",
    cancelled: { one: "È stato soppresso {count} volta.", many: "È stato soppresso {count} volte.", other: "È stato soppresso {count} volte." },
    cancelledEveryDay: "Il treno {trainNumber} è stato soppresso in ognuno dei {days}.",
    source: "Sono statistiche non ufficiali di Binario su dati ViaggiaTreno.",
    unavailable: "Le statistiche di puntualità di Binario non sono ancora disponibili.",
    trainUnknown: "Non ho statistiche di puntualità per il treno {trainNumber}.",
    trainFewData: "Per il treno {trainNumber} non ci sono ancora abbastanza osservazioni.",
    trainSharedNumber: "Con il numero {trainNumber} circolano più treni, da origini diverse, quindi non so di quale chiedi.",
    trainMissing: "Di quale treno vuoi sapere la puntualità? Puoi dire, per esempio: quanto è puntuale il treno 9558.",
    routeFewData: "Per la tratta {routeName} non ci sono ancora abbastanza osservazioni.",
    routeStationsUnknown: "Non riconosco le stazioni della tratta {routeName}: nella dashboard Binario indica origine e destinazione con il nome della stazione, per esempio Roma Termini.",
    routeChoice: "Quale tratta ti interessa? Puoi dire, per esempio: quanto è puntuale la tratta {example}.",
    minutes: { one: "{count} minuto", many: "{count} minuti", other: "{count} minuti" },
    observedDays: { one: "{count} giorno osservato", many: "{count} giorni osservati", other: "{count} giorni osservati" },
    trainCount: { one: "{count} treno", many: "{count} treni", other: "{count} treni" },
    screenTitle: "Puntualità",
    statisticsLabel: "Statistiche non ufficiali di Binario su dati ViaggiaTreno",
    statisticsNotLive: "Statistiche, non dati in tempo reale",
    statisticsUntil: "Dati fino al {date}",
    onTimeLabel: "In orario",
    meanDelayLabel: "Ritardo medio",
    observedDaysLabel: "Giorni osservati",
    trainsLabel: "Treni",
    minutesValue: "{count} min",
    showRoute: "Puntualità della tratta {routeName}",
    hint: "«Alexa, chiedi a Binario Treni quanto è puntuale il treno 9558»",
    mostPunctualBest: "Per la tratta {routeName}, fra i treni che di solito partono nelle prossime tre ore, il più puntuale è {train}.",
    mostPunctualItalianTime: "Gli orari sono in ora italiana.",
    mostPunctualOthers: "Poi {trains}.",
    mostPunctualTrain: { one: "il {trainNumber} dell'{time}, in orario {onTime}", many: "il {trainNumber} delle {time}, in orario {onTime}", other: "il {trainNumber} delle {time}, in orario {onTime}" },
    outOfTen: { one: "{count} volta su 10", many: "{count} volte su 10", other: "{count} volte su 10" },
    mostPunctualNone: "Per la tratta {routeName} nelle prossime tre ore non parte nessun treno con abbastanza osservazioni.",
    mostPunctualRouteChoice: "Quale tratta ti interessa? Puoi dire, per esempio: qual è il treno più puntuale per {example}.",
    showRouteMostPunctual: "Treno più puntuale per {routeName}",
    mostPunctualHint: "«Alexa, chiedi a Binario Treni qual è il treno più puntuale per casa lavoro»",
  },
};

const en: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Toggle navigation", menu: "Menu", signInTitle: "Sign in to Binario", signInDescription: "This dashboard only manages your routes and personal voice settings.", signIn: "Sign in", signOut: "Sign out",
    accountDeletedTitle: "Account deleted", accountDeletedDescription: "The Binario Companion profile, routes, and associated Alexa credentials have been deleted from the operational database. An old session cannot recreate the account.", productName: "Binario Alexa Companion", heroTitle: "Link Alexa to your Binario app.", heroDescription: "This is a technical linking surface: the smartphone app remains the main product and the source of your routes. Alexa accesses personal data only after verified consent.", liveDataNotConnected: "Live data not connected", personalRoutes: "Personal routes", personalRoutesDescription: "Stored in your Binario profile. No travel history or location is recorded.", routeName: "Name", routeNamePlaceholder: "Home — work", origin: "Origin", originPlaceholder: "London Waterloo", destination: "Destination", destinationPlaceholder: "Reading", pinRoute: "Pin route", save: "Save", loadingRoutes: "Loading routes…", loadFailed: "Couldn't load. Reload the page.", noRoutes: "You have not saved any routes yet. Add one to prepare future voice requests.", unpinRoute: "Remove {routeName} from pinned routes", pinNamedRoute: "Pin {routeName}", removeRoute: "Remove route {routeName}", removeRouteConfirmTitle: "Remove the route {routeName}?", removeRouteConfirmDescription: "Alexa will no longer use it for personal answers. This can't be undone.", alexa: "Alexa", alexaDescription: "OAuth 2.0 account linking and signed webhook.", alexaConfigured: "Server configuration is available. Enable the skill in the Alexa app to start linking.", alexaNotConfigured: "Not active. Alexa console configuration is missing; the webhook rejects every request.", accountStatus: "Account status", accountLinked: "Linked and verified.", accountNotLinked: "Not linked. Personal responses remain blocked.", unlinkAlexa: "Unlink Alexa account", linkingInstructions: "After configuring the skill, enable it in the Alexa app: linking starts only from Alexa’s official OAuth flow.", dataGuarantee: "Rail data guarantee", dataGuaranteeDescription: "Until an authorised rail data source is configured, Binario responds exactly: “{liveDataUnavailable}”.", androidAssistant: "Google Assistant on Android", androidAssistantDescription: "App Actions can open Binario on the phone or Wear OS. They do not replace a conversational Custom Skill on Google Home.", activeRouteDeepLink: "Try active-route deep link", accountAndData: "Account and data", accountAndDataDescription: "Unlinking Alexa revokes voice access only. Deletion instead removes the Companion profile and associated data from the operational database.", deleteAccount: "Delete account and data", deleteAccountConfirmTitle: "Permanently delete the account?", deleteAccountConfirmDescription: "Routes, preferences, Companion devices, and Alexa credentials will be deleted from the operational database. This cannot be undone; retention of any logs and backups depends on the controller’s policy.", deleteAccountConfirmationContract: "For security, the confirmation command sent to the server remains “ELIMINA” in every language.", cancel: "Cancel", deletePermanently: "Delete permanently", deleteFailed: "Deletion failed. No data was marked as deleted: please try again.", routeInvalid: "Route not saved: name, origin and destination need at least 2 characters, and origin and destination must differ.", routeLimitReached: "Route not saved: you already have the maximum number of routes. Remove one to add another.", routeSaveFailed: "Saving failed. Please try again.", actionFailed: "The change failed: nothing was changed. Try again.", notFoundTitle: "Page not found", notFoundDescription: "The page you are looking for does not exist.", notFoundMoved: "It may have been moved or deleted.", goHome: "Go home", unexpectedError: "An unexpected error occurred.", reloadPage: "Reload page",
  },
  accessibility: { close: "Close", loading: "Loading", pagination: "Pagination", previousPage: "Go to previous page", nextPage: "Go to next page", morePages: "More pages", previousSlide: "Previous slide", nextSlide: "Next slide", breadcrumb: "Breadcrumb", more: "More", mobileSidebar: "Sidebar", mobileSidebarDescription: "Displays the mobile sidebar." },
  consent: { title: "Link Alexa to Binario", signedInAs: " as <strong>{userName}</strong>", description: "You are about to link Amazon Alexa to your Binario account{signedInAs}. Alexa will be able to read the names of routes you have saved. No real-time data is shared.", ownRequestOnly: "Continue only if you started this link from your own Alexa app just now; otherwise, cancel.", allow: "Link", deny: "Cancel", loginRequired: "Sign in to the Binario dashboard first, then repeat Alexa linking.", errorTitle: "Alexa linking failed", notConfigured: "The Alexa integration is not configured. No account can be linked.", invalidRequest: "The linking request is invalid or no longer available. Restart the process from the Alexa app.", invalidCsrf: "The security check has expired or is invalid. No account was linked; restart the process from the Alexa app.", returnDashboard: "Return to the dashboard" },
  voice: { cardTitle: "Binario", accountLinkRequired: "To use personal information, first link your Binario account in the Alexa app.", unsupported: "I didn't understand that request. You can ask how punctual a train or one of your routes is, for the most punctual train of the next three hours, or for your routes.", help: "With Binario, you can ask for the next trains on a saved route, a train's status, how punctual a train or one of your routes is, the most punctual train of the next three hours on one of your routes, or a list of your routes. What would you like to know?", goodbye: "See you soon.", launch: "Welcome to Binario. You can ask how punctual a train or one of your routes is, for the most punctual train of the next three hours, or for your routes.", noRoutes: "You have not saved any routes in Binario yet.", routeSubject: "For the route {routeName}", trainSubject: "For train {trainNumber}", liveDataUnavailable: "real-time data is not available", routesSaved: "Your saved routes are: {routes}{suffix}", moreRoutes: { one: ". There is one more route in the Binario dashboard.", other: ". There are {count} more routes in the Binario dashboard." }, error: "A problem occurred. Please try again later.", launchReprompt: "What would you like to know? You can ask how punctual a train or one of your routes is, for the most punctual train of the next three hours, or for your routes.", listTerminator: ".", routeNotFound: "I couldn't find the route {routeName}.", routeAmbiguous: "I found more than one route: {routes}.", routeChoice: "Which route would you like? You can say, for example: next trains for {example}." },
  screen: { homeTitle: "What you can ask", departuresTitle: "Departures", trainStatusTitle: "Train status", myRoutesTitle: "My routes", demoData: "Local demo catalogue — not live", sourceNone: "Source: no real-time rail provider connected", sourceDemo: "Source: demo data, not operational", checkedAt: "Checked at {time}", noTimetable: "No times shown: Binario never invents departures, delays or platforms.", trainNumber: "Train {trainNumber}", trainNumberMissing: "No train number given", pinned: "Pinned", moreRoutes: { one: "One more route is in the Binario dashboard.", other: "{count} more routes are in the Binario dashboard." }, showRoute: "Next trains for {routeName}", nextTrainsAction: "Next trains", myRoutesAction: "My routes", hintsTitle: "Try asking", hintNextTrains: "“Alexa, ask Binario Trains for the next trains”", hintTrainStatus: "“Alexa, ask Binario Trains for the status of train 9512”", hintMyRoutes: "“Alexa, ask Binario Trains for my routes”" },
  punctuality: {
    train: { one: "Train {trainNumber} reached its final stop on time, that is within {limit}, {count} time out of 100 over {days}.", other: "Train {trainNumber} reached its final stop on time, that is within {limit}, {count} times out of 100 over {days}." },
    route: { one: "On the route {routeName}, with {trains} over {days}, arrivals at the destination were on time, that is within {limit}, {count} time out of 100.", other: "On the route {routeName}, with {trains} over {days}, arrivals at the destination were on time, that is within {limit}, {count} times out of 100." },
    mixedLimit: "5 minutes for Frecce, Italo, EuroCity and EuroNight trains and 15 minutes for the other trains",
    meanDelay: { one: "The average delay was {count} minute.", other: "The average delay was {count} minutes." },
    noMeanDelay: "On average there was no delay.",
    cancelled: { one: "It was cancelled {count} time.", other: "It was cancelled {count} times." },
    cancelledEveryDay: "Train {trainNumber} was cancelled on each of the {days}.",
    source: "These are unofficial Binario statistics from ViaggiaTreno data.",
    unavailable: "Binario's punctuality statistics are not available yet.",
    trainUnknown: "I have no punctuality statistics for train {trainNumber}.",
    trainFewData: "There are not enough observations for train {trainNumber} yet.",
    trainSharedNumber: "Several trains run with the number {trainNumber}, from different origins, so I can't tell which one you mean.",
    trainMissing: "Which train would you like to know about? You can say, for example: how punctual is train 9558.",
    routeFewData: "There are not enough observations for the route {routeName} yet.",
    routeStationsUnknown: "I can't identify the stations of the route {routeName}: in the Binario dashboard, enter the origin and destination by station name, for example Roma Termini.",
    routeChoice: "Which route would you like? You can say, for example: how punctual is my route {example}.",
    minutes: { one: "{count} minute", other: "{count} minutes" },
    observedDays: { one: "{count} observed day", other: "{count} observed days" },
    trainCount: { one: "{count} train", other: "{count} trains" },
    screenTitle: "Punctuality",
    statisticsLabel: "Unofficial Binario statistics from ViaggiaTreno data",
    statisticsNotLive: "Statistics, not real-time data",
    statisticsUntil: "Data up to {date}",
    onTimeLabel: "On time",
    meanDelayLabel: "Average delay",
    observedDaysLabel: "Observed days",
    trainsLabel: "Trains",
    minutesValue: "{count} min",
    showRoute: "Punctuality of the route {routeName}",
    hint: "“Alexa, ask Binario Trains how punctual train 9558 is”",
    mostPunctualBest: "On the route {routeName}, of the trains that usually leave in the next three hours, the most punctual is {train}.",
    mostPunctualItalianTime: "Times are Italian time.",
    mostPunctualOthers: "Then {trains}.",
    mostPunctualTrain: { one: "train {trainNumber} at {time}, on time {onTime}", other: "train {trainNumber} at {time}, on time {onTime}" },
    outOfTen: { one: "{count} time out of 10", other: "{count} times out of 10" },
    mostPunctualNone: "On the route {routeName}, no train with enough observations leaves in the next three hours.",
    mostPunctualRouteChoice: "Which route would you like? You can say, for example: which is the most punctual train for {example}.",
    showRouteMostPunctual: "Most punctual train for {routeName}",
    mostPunctualHint: "“Alexa, ask Binario Trains which is the most punctual train for casa lavoro”",
  },
};

const de: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Navigation umschalten", menu: "Menü", signInTitle: "Bei Binario anmelden", signInDescription: "Dieses Dashboard verwaltet nur Ihre Strecken und persönlichen Spracheinstellungen.", signIn: "Anmelden", signOut: "Abmelden", accountDeletedTitle: "Konto gelöscht", accountDeletedDescription: "Das Binario-Companion-Profil, die Strecken und die zugehörigen Alexa-Zugangsdaten wurden aus der operativen Datenbank gelöscht. Eine alte Sitzung kann das Konto nicht erneut erstellen.", productName: "Binario Alexa Companion", heroTitle: "Alexa mit Ihrer Binario-App verknüpfen.", heroDescription: "Dies ist eine technische Verknüpfungsoberfläche: Die Smartphone-App bleibt das Hauptprodukt und die Quelle Ihrer Strecken. Alexa greift erst nach einer bestätigten Einwilligung auf personenbezogene Daten zu.", liveDataNotConnected: "Live-Daten nicht verbunden", personalRoutes: "Persönliche Strecken", personalRoutesDescription: "In Ihrem Binario-Profil gespeichert. Es werden weder Reiseverlauf noch Standort erfasst.", routeName: "Name", routeNamePlaceholder: "Zuhause — Arbeit", origin: "Start", originPlaceholder: "Berlin Hbf", destination: "Ziel", destinationPlaceholder: "Potsdam Hbf", pinRoute: "Strecke anheften", save: "Speichern", loadingRoutes: "Strecken werden geladen…", loadFailed: "Laden fehlgeschlagen. Laden Sie die Seite neu.", noRoutes: "Sie haben noch keine Strecken gespeichert. Fügen Sie eine hinzu, um spätere Sprachanfragen vorzubereiten.", unpinRoute: "{routeName} aus den angehefteten Strecken entfernen", pinNamedRoute: "{routeName} anheften", removeRoute: "Strecke {routeName} entfernen", removeRouteConfirmTitle: "Strecke {routeName} entfernen?", removeRouteConfirmDescription: "Alexa verwendet sie dann nicht mehr für persönliche Antworten. Dies kann nicht rückgängig gemacht werden.", alexa: "Alexa", alexaDescription: "OAuth-2.0-Kontoverknüpfung und signierter Webhook.", alexaConfigured: "Serverkonfiguration verfügbar. Aktivieren Sie den Skill in der Alexa-App, um die Verknüpfung zu starten.", alexaNotConfigured: "Nicht aktiv. Die Alexa-Console-Konfiguration fehlt; der Webhook weist jede Anfrage zurück.", accountStatus: "Kontostatus", accountLinked: "Verknüpft und bestätigt.", accountNotLinked: "Nicht verknüpft. Persönliche Antworten bleiben gesperrt.", unlinkAlexa: "Alexa-Konto trennen", linkingInstructions: "Aktivieren Sie den Skill nach der Konfiguration in der Alexa-App: Die Verknüpfung startet nur über den offiziellen OAuth-Ablauf von Alexa.", dataGuarantee: "Garantie für Bahndaten", dataGuaranteeDescription: "Solange keine autorisierte Bahndatenquelle konfiguriert ist, antwortet Binario genau: „{liveDataUnavailable}“.", androidAssistant: "Google Assistant auf Android", androidAssistantDescription: "App Actions können Binario auf dem Telefon oder Wear OS öffnen. Sie ersetzen keinen dialogorientierten Custom Skill auf Google Home.", activeRouteDeepLink: "Deep Link für aktive Strecke testen", accountAndData: "Konto und Daten", accountAndDataDescription: "Das Trennen von Alexa widerruft nur den Sprachzugriff. Das Löschen entfernt dagegen das Companion-Profil und die zugehörigen Daten aus der operativen Datenbank.", deleteAccount: "Konto und Daten löschen", deleteAccountConfirmTitle: "Konto endgültig löschen?", deleteAccountConfirmDescription: "Strecken, Einstellungen, Companion-Geräte und Alexa-Zugangsdaten werden aus der operativen Datenbank gelöscht. Der Vorgang kann nicht rückgängig gemacht werden; die Aufbewahrung möglicher Logs und Backups richtet sich nach der Richtlinie des Verantwortlichen.", deleteAccountConfirmationContract: "Aus Sicherheitsgründen bleibt der an den Server gesendete Bestätigungsbefehl in allen Sprachen „ELIMINA“.", cancel: "Abbrechen", deletePermanently: "Endgültig löschen", deleteFailed: "Löschen fehlgeschlagen. Es wurden keine Daten als gelöscht markiert: Bitte versuchen Sie es erneut.", routeInvalid: "Strecke nicht gespeichert: Name, Start und Ziel brauchen mindestens 2 Zeichen, und Start und Ziel müssen sich unterscheiden.", routeLimitReached: "Strecke nicht gespeichert: Sie haben bereits die maximale Anzahl an Strecken. Entfernen Sie eine, um eine neue hinzuzufügen.", routeSaveFailed: "Speichern fehlgeschlagen. Bitte versuchen Sie es erneut.", actionFailed: "Änderung fehlgeschlagen: Es wurde nichts geändert. Versuchen Sie es erneut.", notFoundTitle: "Seite nicht gefunden", notFoundDescription: "Die gesuchte Seite existiert nicht.", notFoundMoved: "Sie wurde möglicherweise verschoben oder gelöscht.", goHome: "Zur Startseite", unexpectedError: "Ein unerwarteter Fehler ist aufgetreten.", reloadPage: "Seite neu laden",
  },
  accessibility: { close: "Schließen", loading: "Laden", pagination: "Seitennavigation", previousPage: "Zur vorherigen Seite", nextPage: "Zur nächsten Seite", morePages: "Weitere Seiten", previousSlide: "Vorherige Folie", nextSlide: "Nächste Folie", breadcrumb: "Navigationspfad", more: "Mehr", mobileSidebar: "Seitenleiste", mobileSidebarDescription: "Zeigt die mobile Seitenleiste an." },
  consent: { title: "Alexa mit Binario verknüpfen", signedInAs: " als <strong>{userName}</strong>", description: "Sie sind dabei, Amazon Alexa mit Ihrem Binario-Konto{signedInAs} zu verknüpfen. Alexa kann die Namen Ihrer gespeicherten Strecken lesen. Es werden keine Echtzeitdaten weitergegeben.", ownRequestOnly: "Fahren Sie nur fort, wenn Sie diese Verknüpfung gerade selbst in Ihrer Alexa-App gestartet haben; andernfalls brechen Sie ab.", allow: "Verknüpfen", deny: "Abbrechen", loginRequired: "Melden Sie sich zuerst im Binario-Dashboard an und wiederholen Sie dann die Alexa-Verknüpfung.", errorTitle: "Alexa-Verknüpfung fehlgeschlagen", notConfigured: "Die Alexa-Integration ist nicht konfiguriert. Es kann kein Konto verknüpft werden.", invalidRequest: "Die Verknüpfungsanfrage ist ungültig oder nicht mehr verfügbar. Starten Sie den Vorgang in der Alexa-App erneut.", invalidCsrf: "Die Sicherheitsprüfung ist abgelaufen oder ungültig. Es wurde kein Konto verknüpft; starten Sie den Vorgang in der Alexa-App erneut.", returnDashboard: "Zurück zum Dashboard" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Um persönliche Informationen zu verwenden, verknüpfen Sie zuerst Ihr Binario-Konto in der Alexa-App.", unsupported: "Ich habe diese Anfrage nicht verstanden. Sie können nach der Pünktlichkeit eines Zuges oder einer Ihrer Strecken, dem pünktlichsten Zug der nächsten drei Stunden oder Ihren Strecken fragen.", help: "Mit Binario können Sie nach den nächsten Zügen einer gespeicherten Strecke, dem Status eines Zuges, der Pünktlichkeit eines Zuges oder einer Ihrer Strecken, dem pünktlichsten Zug der nächsten drei Stunden auf einer Ihrer Strecken sowie nach der Liste Ihrer Strecken fragen. Was möchten Sie wissen?", goodbye: "Bis bald.", launch: "Willkommen bei Binario. Sie können nach der Pünktlichkeit eines Zuges oder einer Ihrer Strecken, dem pünktlichsten Zug der nächsten drei Stunden oder Ihren Strecken fragen.", noRoutes: "Sie haben noch keine Strecken in Binario gespeichert.", routeSubject: "Für die Strecke {routeName}", trainSubject: "Für Zug {trainNumber}", liveDataUnavailable: "Echtzeitdaten sind nicht verfügbar", routesSaved: "Ihre gespeicherten Strecken sind: {routes}{suffix}", moreRoutes: { one: ". Es gibt eine weitere Strecke im Binario-Dashboard.", other: ". Es gibt {count} weitere Strecken im Binario-Dashboard." }, error: "Ein Problem ist aufgetreten. Bitte versuchen Sie es später erneut.", launchReprompt: "Was möchten Sie wissen? Sie können nach der Pünktlichkeit eines Zuges oder einer Ihrer Strecken, dem pünktlichsten Zug der nächsten drei Stunden oder Ihren Strecken fragen.", listTerminator: ".", routeNotFound: "Ich habe die Strecke {routeName} nicht gefunden.", routeAmbiguous: "Ich habe mehrere Strecken gefunden: {routes}.", routeChoice: "Welche Strecke meinen Sie? Sagen Sie zum Beispiel: nächste Züge für {example}." },
  screen: { homeTitle: "Was Sie fragen können", departuresTitle: "Abfahrten", trainStatusTitle: "Zugstatus", myRoutesTitle: "Meine Strecken", demoData: "Lokaler Demokatalog – nicht live", sourceNone: "Quelle: kein Echtzeit-Bahnanbieter verbunden", sourceDemo: "Quelle: Demodaten, nicht betrieblich", checkedAt: "Geprüft um {time}", noTimetable: "Keine Zeiten angezeigt: Binario erfindet keine Abfahrten, Verspätungen oder Gleise.", trainNumber: "Zug {trainNumber}", trainNumberMissing: "Keine Zugnummer angegeben", pinned: "Angeheftet", moreRoutes: { one: "Eine weitere Strecke ist im Binario-Dashboard.", other: "{count} weitere Strecken sind im Binario-Dashboard." }, showRoute: "Nächste Züge für {routeName}", nextTrainsAction: "Nächste Züge", myRoutesAction: "Meine Strecken", hintsTitle: "Fragen Sie zum Beispiel", hintNextTrains: "„Alexa, frage Binario Züge nach den nächsten Zügen“", hintTrainStatus: "„Alexa, frage Binario Züge nach dem Status von Zug 9512“", hintMyRoutes: "„Alexa, frage Binario Züge nach meinen Strecken“" },
  punctuality: {
    train: { one: "Zug {trainNumber} kam an {days} {count}-mal von 100 pünktlich an der Endstation an, also mit einer Verspätung von höchstens {limit}.", other: "Zug {trainNumber} kam an {days} {count}-mal von 100 pünktlich an der Endstation an, also mit einer Verspätung von höchstens {limit}." },
    route: { one: "Auf der Strecke {routeName} waren die Ankünfte am Ziel {count}-mal von 100 pünktlich, also mit einer Verspätung von höchstens {limit}. Grundlage: {trains} an {days}.", other: "Auf der Strecke {routeName} waren die Ankünfte am Ziel {count}-mal von 100 pünktlich, also mit einer Verspätung von höchstens {limit}. Grundlage: {trains} an {days}." },
    mixedLimit: "5 Minuten bei Frecce, Italo, EuroCity und EuroNight und 15 Minuten bei den übrigen Zügen",
    meanDelay: { one: "Die durchschnittliche Verspätung betrug {count} Minute.", other: "Die durchschnittliche Verspätung betrug {count} Minuten." },
    noMeanDelay: "Im Durchschnitt gab es keine Verspätung.",
    cancelled: { one: "Er fiel {count}-mal aus.", other: "Er fiel {count}-mal aus." },
    cancelledEveryDay: "Zug {trainNumber} ist an allen {days} ausgefallen.",
    source: "Das sind inoffizielle Binario-Statistiken aus ViaggiaTreno-Daten.",
    unavailable: "Die Pünktlichkeitsstatistiken von Binario sind noch nicht verfügbar.",
    trainUnknown: "Für Zug {trainNumber} habe ich keine Pünktlichkeitsstatistik.",
    trainFewData: "Für Zug {trainNumber} gibt es noch nicht genug Beobachtungen.",
    trainSharedNumber: "Unter der Nummer {trainNumber} fahren mehrere Züge mit verschiedenen Startbahnhöfen, daher weiß ich nicht, welchen Sie meinen.",
    trainMissing: "Für welchen Zug möchten Sie die Pünktlichkeit wissen? Sagen Sie zum Beispiel: wie pünktlich ist Zug 9558.",
    routeFewData: "Für die Strecke {routeName} gibt es noch nicht genug Beobachtungen.",
    routeStationsUnknown: "Ich erkenne die Bahnhöfe der Strecke {routeName} nicht: Geben Sie im Binario-Dashboard Start und Ziel mit dem Bahnhofsnamen an, zum Beispiel Roma Termini.",
    routeChoice: "Welche Strecke meinen Sie? Sagen Sie zum Beispiel: wie pünktlich ist meine Strecke {example}.",
    minutes: { one: "{count} Minute", other: "{count} Minuten" },
    observedDays: { one: "{count} beobachteten Tag", other: "{count} beobachteten Tagen" },
    trainCount: { one: "{count} Zug", other: "{count} Züge" },
    screenTitle: "Pünktlichkeit",
    statisticsLabel: "Inoffizielle Binario-Statistik aus ViaggiaTreno-Daten",
    statisticsNotLive: "Statistik, keine Echtzeitdaten",
    statisticsUntil: "Daten bis zum {date}",
    onTimeLabel: "Pünktlich",
    meanDelayLabel: "Mittlere Verspätung",
    observedDaysLabel: "Beobachtete Tage",
    trainsLabel: "Züge",
    minutesValue: "{count} Min.",
    showRoute: "Pünktlichkeit der Strecke {routeName}",
    hint: "„Alexa, frage Binario Züge nach der Pünktlichkeit von Zug 9558“",
    mostPunctualBest: "Auf der Strecke {routeName} ist von den Zügen, die üblicherweise in den nächsten drei Stunden abfahren, dieser am pünktlichsten: {train}.",
    mostPunctualItalianTime: "Die Zeiten sind in italienischer Zeit angegeben.",
    mostPunctualOthers: "Danach {trains}.",
    mostPunctualTrain: { one: "Zug {trainNumber} um {time}, {onTime} pünktlich", other: "Zug {trainNumber} um {time}, {onTime} pünktlich" },
    outOfTen: { one: "{count}-mal von 10", other: "{count}-mal von 10" },
    mostPunctualNone: "Auf der Strecke {routeName} fährt in den nächsten drei Stunden kein Zug mit genug Beobachtungen ab.",
    mostPunctualRouteChoice: "Welche Strecke meinen Sie? Sagen Sie zum Beispiel: welcher ist der pünktlichste Zug für {example}.",
    showRouteMostPunctual: "Pünktlichster Zug für {routeName}",
    mostPunctualHint: "„Alexa, frage Binario Züge nach dem pünktlichsten Zug für casa lavoro“",
  },
};

const fr: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Basculer la navigation", menu: "Menu", signInTitle: "Se connecter à Binario", signInDescription: "Ce tableau de bord gère uniquement vos trajets et vos réglages vocaux personnels.", signIn: "Se connecter", signOut: "Se déconnecter", accountDeletedTitle: "Compte supprimé", accountDeletedDescription: "Le profil Binario Companion, les trajets et les identifiants Alexa associés ont été supprimés de la base de données opérationnelle. Une ancienne session ne peut pas recréer le compte.", productName: "Binario Alexa Companion", heroTitle: "Associez Alexa à votre application Binario.", heroDescription: "Il s’agit d’une surface technique d’association : l’application smartphone reste le produit principal et la source de vos trajets. Alexa n’accède aux données personnelles qu’après un consentement vérifié.", liveDataNotConnected: "Données en direct non connectées", personalRoutes: "Trajets personnels", personalRoutesDescription: "Conservés dans votre profil Binario. Aucun historique de voyage ni position n’est enregistré.", routeName: "Nom", routeNamePlaceholder: "Maison — travail", origin: "Départ", originPlaceholder: "Paris Gare du Nord", destination: "Destination", destinationPlaceholder: "Lille Flandres", pinRoute: "Épingler le trajet", save: "Enregistrer", loadingRoutes: "Chargement des trajets…", loadFailed: "Chargement impossible. Rechargez la page.", noRoutes: "Vous n’avez pas encore enregistré de trajets. Ajoutez-en un pour préparer de futures demandes vocales.", unpinRoute: "Retirer {routeName} des trajets épinglés", pinNamedRoute: "Épingler {routeName}", removeRoute: "Supprimer le trajet {routeName}", removeRouteConfirmTitle: "Supprimer le trajet {routeName} ?", removeRouteConfirmDescription: "Alexa ne l’utilisera plus pour les réponses personnelles. Cette action est irréversible.", alexa: "Alexa", alexaDescription: "Association de compte OAuth 2.0 et webhook signé.", alexaConfigured: "La configuration du serveur est disponible. Activez le skill dans l’application Alexa pour commencer l’association.", alexaNotConfigured: "Inactif. La configuration de la console Alexa est absente ; le webhook rejette toutes les demandes.", accountStatus: "État du compte", accountLinked: "Associé et vérifié.", accountNotLinked: "Non associé. Les réponses personnelles restent bloquées.", unlinkAlexa: "Dissocier le compte Alexa", linkingInstructions: "Après avoir configuré le skill, activez-le dans l’application Alexa : l’association ne démarre que depuis le flux OAuth officiel d’Alexa.", dataGuarantee: "Garantie sur les données ferroviaires", dataGuaranteeDescription: "Tant qu’une source de données ferroviaires autorisée n’est pas configurée, Binario répond exactement : « {liveDataUnavailable} ».", androidAssistant: "Google Assistant sur Android", androidAssistantDescription: "Les App Actions peuvent ouvrir Binario sur le téléphone ou Wear OS. Elles ne remplacent pas un Custom Skill conversationnel sur Google Home.", activeRouteDeepLink: "Essayer le lien profond du trajet actif", accountAndData: "Compte et données", accountAndDataDescription: "Dissocier Alexa révoque uniquement l’accès vocal. La suppression retire en revanche le profil Companion et les données associées de la base opérationnelle.", deleteAccount: "Supprimer le compte et les données", deleteAccountConfirmTitle: "Supprimer définitivement le compte ?", deleteAccountConfirmDescription: "Les trajets, préférences, appareils Companion et identifiants Alexa seront supprimés de la base de données opérationnelle. Cette opération est irréversible ; la conservation d’éventuels journaux et sauvegardes dépend de la politique du responsable.", deleteAccountConfirmationContract: "Pour des raisons de sécurité, la commande de confirmation envoyée au serveur reste « ELIMINA » dans toutes les langues.", cancel: "Annuler", deletePermanently: "Supprimer définitivement", deleteFailed: "La suppression a échoué. Aucune donnée n’a été signalée comme supprimée : réessayez.", routeInvalid: "Trajet non enregistré : le nom, le départ et la destination doivent comporter au moins 2 caractères, et le départ doit différer de la destination.", routeLimitReached: "Trajet non enregistré : vous avez déjà le nombre maximal de trajets. Supprimez-en un pour en ajouter un autre.", routeSaveFailed: "L’enregistrement a échoué. Réessayez.", actionFailed: "La modification a échoué : rien n’a changé. Réessayez.", notFoundTitle: "Page introuvable", notFoundDescription: "La page que vous recherchez n’existe pas.", notFoundMoved: "Elle a peut-être été déplacée ou supprimée.", goHome: "Aller à l’accueil", unexpectedError: "Une erreur inattendue s’est produite.", reloadPage: "Recharger la page",
  },
  accessibility: { close: "Fermer", loading: "Chargement", pagination: "Pagination", previousPage: "Aller à la page précédente", nextPage: "Aller à la page suivante", morePages: "Autres pages", previousSlide: "Diapositive précédente", nextSlide: "Diapositive suivante", breadcrumb: "Fil d’Ariane", more: "Plus", mobileSidebar: "Barre latérale", mobileSidebarDescription: "Affiche la barre latérale mobile." },
  consent: { title: "Associer Alexa à Binario", signedInAs: " en tant que <strong>{userName}</strong>", description: "Vous êtes sur le point d’associer Amazon Alexa à votre compte Binario{signedInAs}. Alexa pourra lire les noms des trajets que vous avez enregistrés. Aucune donnée en temps réel n’est partagée.", ownRequestOnly: "Ne continuez que si vous venez de lancer vous-même cette association depuis votre application Alexa ; sinon, annulez.", allow: "Associer", deny: "Annuler", loginRequired: "Connectez-vous d’abord au tableau de bord Binario, puis recommencez l’association Alexa.", errorTitle: "Échec de l’association Alexa", notConfigured: "L’intégration Alexa n’est pas configurée. Aucun compte ne peut être associé.", invalidRequest: "La demande d’association est invalide ou n’est plus disponible. Recommencez la procédure depuis l’application Alexa.", invalidCsrf: "La vérification de sécurité a expiré ou n’est pas valide. Aucun compte n’a été associé ; recommencez la procédure depuis l’application Alexa.", returnDashboard: "Retourner au tableau de bord" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Pour utiliser les informations personnelles, associez d’abord votre compte Binario dans l’application Alexa.", unsupported: "Je n’ai pas compris cette demande. Vous pouvez demander la ponctualité d’un train ou de l’un de vos trajets, le train le plus ponctuel des trois prochaines heures ou vos trajets.", help: "Avec Binario, vous pouvez demander les prochains trains d’un trajet enregistré, l’état d’un train, la ponctualité d’un train ou de l’un de vos trajets, le train le plus ponctuel des trois prochaines heures sur l’un de vos trajets, ou la liste de vos trajets. Que voulez-vous savoir ?", goodbye: "À bientôt.", launch: "Bienvenue dans Binario. Vous pouvez demander la ponctualité d’un train ou de l’un de vos trajets, le train le plus ponctuel des trois prochaines heures ou vos trajets.", noRoutes: "Vous n’avez encore enregistré aucun trajet dans Binario.", routeSubject: "Pour le trajet {routeName}", trainSubject: "Pour le train {trainNumber}", liveDataUnavailable: "les données en temps réel ne sont pas disponibles", routesSaved: "Vos trajets enregistrés sont : {routes}{suffix}", moreRoutes: { one: ". Il y a un autre trajet dans le tableau de bord Binario.", other: ". Il y a {count} autres trajets dans le tableau de bord Binario." }, error: "Un problème est survenu. Réessayez plus tard.", launchReprompt: "Que voulez-vous savoir ? Vous pouvez demander la ponctualité d’un train ou de l’un de vos trajets, le train le plus ponctuel des trois prochaines heures ou vos trajets.", listTerminator: ".", routeNotFound: "Je n’ai pas trouvé le trajet {routeName}.", routeAmbiguous: "J’ai trouvé plusieurs trajets : {routes}.", routeChoice: "Quel trajet voulez-vous ? Vous pouvez dire, par exemple : prochains trains pour {example}." },
  screen: { homeTitle: "Ce que vous pouvez demander", departuresTitle: "Départs", trainStatusTitle: "État du train", myRoutesTitle: "Mes trajets", demoData: "Catalogue de démonstration local — pas en direct", sourceNone: "Source : aucun fournisseur ferroviaire en temps réel connecté", sourceDemo: "Source : données de démonstration, non opérationnelles", checkedAt: "Vérifié à {time}", noTimetable: "Aucun horaire affiché : Binario n’invente jamais de départs, de retards ni de quais.", trainNumber: "Train {trainNumber}", trainNumberMissing: "Aucun numéro de train indiqué", pinned: "Épinglé", moreRoutes: { one: "Un autre trajet se trouve dans le tableau de bord Binario.", other: "{count} autres trajets se trouvent dans le tableau de bord Binario." }, showRoute: "Prochains trains pour {routeName}", nextTrainsAction: "Prochains trains", myRoutesAction: "Mes trajets", hintsTitle: "Essayez de demander", hintNextTrains: "« Alexa, demande à Binario Trains les prochains trains »", hintTrainStatus: "« Alexa, demande à Binario Trains le statut du train 9512 »", hintMyRoutes: "« Alexa, demande à Binario Trains mes trajets »" },
  punctuality: {
    train: { one: "Le train {trainNumber} est arrivé à l’heure à son terminus, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100 en {days}.", many: "Le train {trainNumber} est arrivé à l’heure à son terminus, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100 en {days}.", other: "Le train {trainNumber} est arrivé à l’heure à son terminus, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100 en {days}." },
    route: { one: "Sur le trajet {routeName}, avec {trains} en {days}, les arrivées à destination ont été à l’heure, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100.", many: "Sur le trajet {routeName}, avec {trains} en {days}, les arrivées à destination ont été à l’heure, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100.", other: "Sur le trajet {routeName}, avec {trains} en {days}, les arrivées à destination ont été à l’heure, c’est-à-dire avec un retard d’au plus {limit}, {count} fois sur 100." },
    mixedLimit: "5 minutes pour les Frecce, Italo, EuroCity et EuroNight et 15 minutes pour les autres trains",
    meanDelay: { one: "Le retard moyen a été de {count} minute.", many: "Le retard moyen a été de {count} minutes.", other: "Le retard moyen a été de {count} minutes." },
    noMeanDelay: "En moyenne, il n’y a pas eu de retard.",
    cancelled: { one: "Il a été supprimé {count} fois.", many: "Il a été supprimé {count} fois.", other: "Il a été supprimé {count} fois." },
    cancelledEveryDay: "Le train {trainNumber} a été supprimé chacun des {days}.",
    source: "Ce sont des statistiques non officielles de Binario sur données ViaggiaTreno.",
    unavailable: "Les statistiques de ponctualité de Binario ne sont pas encore disponibles.",
    trainUnknown: "Je n’ai pas de statistiques de ponctualité pour le train {trainNumber}.",
    trainFewData: "Il n’y a pas encore assez d’observations pour le train {trainNumber}.",
    trainSharedNumber: "Plusieurs trains circulent sous le numéro {trainNumber}, depuis des origines différentes : je ne sais donc pas lequel vous voulez dire.",
    trainMissing: "De quel train voulez-vous connaître la ponctualité ? Vous pouvez dire, par exemple : ponctualité du train 9558.",
    routeFewData: "Il n’y a pas encore assez d’observations pour le trajet {routeName}.",
    routeStationsUnknown: "Je ne reconnais pas les gares du trajet {routeName} : dans le tableau de bord Binario, indiquez le départ et la destination par le nom de la gare, par exemple Roma Termini.",
    routeChoice: "Quel trajet voulez-vous ? Vous pouvez dire, par exemple : ponctualité de mon trajet {example}.",
    minutes: { one: "{count} minute", many: "{count} minutes", other: "{count} minutes" },
    observedDays: { one: "{count} jour observé", many: "{count} jours observés", other: "{count} jours observés" },
    trainCount: { one: "{count} train", many: "{count} trains", other: "{count} trains" },
    screenTitle: "Ponctualité",
    statisticsLabel: "Statistiques non officielles de Binario sur données ViaggiaTreno",
    statisticsNotLive: "Statistiques, pas de données en temps réel",
    statisticsUntil: "Données jusqu’au {date}",
    onTimeLabel: "À l’heure",
    meanDelayLabel: "Retard moyen",
    observedDaysLabel: "Jours observés",
    trainsLabel: "Trains",
    minutesValue: "{count} min",
    showRoute: "Ponctualité du trajet {routeName}",
    hint: "« Alexa, demande à Binario Trains la ponctualité du train 9558 »",
    mostPunctualBest: "Sur le trajet {routeName}, parmi les trains qui partent habituellement dans les trois prochaines heures, le plus ponctuel est {train}.",
    mostPunctualItalianTime: "Les horaires sont à l’heure italienne.",
    mostPunctualOthers: "Puis {trains}.",
    mostPunctualTrain: { one: "le {trainNumber} de {time}, à l’heure {onTime}", many: "le {trainNumber} de {time}, à l’heure {onTime}", other: "le {trainNumber} de {time}, à l’heure {onTime}" },
    outOfTen: { one: "{count} fois sur 10", many: "{count} fois sur 10", other: "{count} fois sur 10" },
    mostPunctualNone: "Sur le trajet {routeName}, aucun train avec assez d’observations ne part dans les trois prochaines heures.",
    mostPunctualRouteChoice: "Quel trajet voulez-vous ? Vous pouvez dire, par exemple : quel est le train le plus ponctuel pour {example}.",
    showRouteMostPunctual: "Train le plus ponctuel pour {routeName}",
    mostPunctualHint: "« Alexa, demande à Binario Trains quel est le train le plus ponctuel pour casa lavoro »",
  },
};

const es: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Alternar navegación", menu: "Menú", signInTitle: "Iniciar sesión en Binario", signInDescription: "Este panel solo gestiona tus rutas y ajustes de voz personales.", signIn: "Iniciar sesión", signOut: "Cerrar sesión", accountDeletedTitle: "Cuenta eliminada", accountDeletedDescription: "El perfil de Binario Companion, las rutas y las credenciales de Alexa asociadas se han eliminado de la base de datos operativa. Una sesión antigua no puede recrear la cuenta.", productName: "Binario Alexa Companion", heroTitle: "Vincula Alexa a tu aplicación Binario.", heroDescription: "Esta es una superficie técnica de vinculación: la aplicación móvil sigue siendo el producto principal y la fuente de tus rutas. Alexa accede a los datos personales solo después de un consentimiento verificado.", liveDataNotConnected: "Datos en directo no conectados", personalRoutes: "Rutas personales", personalRoutesDescription: "Guardadas en tu perfil de Binario. No se registra ningún historial de viaje ni ubicación.", routeName: "Nombre", routeNamePlaceholder: "Casa — trabajo", origin: "Origen", originPlaceholder: "Madrid Puerta de Atocha", destination: "Destino", destinationPlaceholder: "Toledo", pinRoute: "Fijar ruta", save: "Guardar", loadingRoutes: "Cargando rutas…", loadFailed: "No se pudo cargar. Vuelve a cargar la página.", noRoutes: "Aún no has guardado rutas. Añade una para preparar futuras solicitudes de voz.", unpinRoute: "Quitar {routeName} de las rutas fijadas", pinNamedRoute: "Fijar {routeName}", removeRoute: "Eliminar la ruta {routeName}", removeRouteConfirmTitle: "¿Eliminar la ruta {routeName}?", removeRouteConfirmDescription: "Alexa dejará de usarla para las respuestas personales. Esta acción no se puede deshacer.", alexa: "Alexa", alexaDescription: "Vinculación de cuenta OAuth 2.0 y webhook firmado.", alexaConfigured: "La configuración del servidor está disponible. Activa la skill en la aplicación Alexa para iniciar la vinculación.", alexaNotConfigured: "No activo. Falta la configuración de la consola Alexa; el webhook rechaza todas las solicitudes.", accountStatus: "Estado de la cuenta", accountLinked: "Vinculada y verificada.", accountNotLinked: "No vinculada. Las respuestas personales permanecen bloqueadas.", unlinkAlexa: "Desvincular cuenta de Alexa", linkingInstructions: "Después de configurar la skill, actívala en la aplicación Alexa: la vinculación solo se inicia desde el flujo OAuth oficial de Alexa.", dataGuarantee: "Garantía de datos ferroviarios", dataGuaranteeDescription: "Hasta que se configure una fuente ferroviaria autorizada, Binario responde exactamente: «{liveDataUnavailable}».", androidAssistant: "Google Assistant en Android", androidAssistantDescription: "Las App Actions pueden abrir Binario en el teléfono o en Wear OS. No sustituyen una Custom Skill conversacional en Google Home.", activeRouteDeepLink: "Probar enlace profundo de ruta activa", accountAndData: "Cuenta y datos", accountAndDataDescription: "Desvincular Alexa revoca solo el acceso por voz. En cambio, eliminar borra el perfil Companion y los datos asociados de la base de datos operativa.", deleteAccount: "Eliminar cuenta y datos", deleteAccountConfirmTitle: "¿Eliminar la cuenta definitivamente?", deleteAccountConfirmDescription: "Las rutas, preferencias, dispositivos Companion y credenciales de Alexa se eliminarán de la base de datos operativa. La operación no se puede deshacer; la retención de posibles registros y copias de seguridad depende de la política del responsable.", deleteAccountConfirmationContract: "Por seguridad, el comando de confirmación enviado al servidor sigue siendo «ELIMINA» en todos los idiomas.", cancel: "Cancelar", deletePermanently: "Eliminar definitivamente", deleteFailed: "No se pudo eliminar. No se marcó ningún dato como eliminado: inténtalo de nuevo.", routeInvalid: "Ruta no guardada: el nombre, el origen y el destino necesitan al menos 2 caracteres, y el origen y el destino deben ser distintos.", routeLimitReached: "Ruta no guardada: ya tienes el número máximo de rutas. Elimina una para añadir otra.", routeSaveFailed: "No se pudo guardar. Inténtalo de nuevo.", actionFailed: "El cambio no se aplicó: no se modificó nada. Inténtalo de nuevo.", notFoundTitle: "Página no encontrada", notFoundDescription: "La página que buscas no existe.", notFoundMoved: "Puede que se haya movido o eliminado.", goHome: "Ir al inicio", unexpectedError: "Se produjo un error inesperado.", reloadPage: "Recargar página",
  },
  accessibility: { close: "Cerrar", loading: "Cargando", pagination: "Paginación", previousPage: "Ir a la página anterior", nextPage: "Ir a la página siguiente", morePages: "Más páginas", previousSlide: "Diapositiva anterior", nextSlide: "Diapositiva siguiente", breadcrumb: "Migas de pan", more: "Más", mobileSidebar: "Barra lateral", mobileSidebarDescription: "Muestra la barra lateral móvil." },
  consent: { title: "Vincular Alexa con Binario", signedInAs: " como <strong>{userName}</strong>", description: "Estás a punto de vincular Amazon Alexa con tu cuenta de Binario{signedInAs}. Alexa podrá leer los nombres de las rutas que has guardado. No se comparten datos en tiempo real.", ownRequestOnly: "Continúa solo si acabas de iniciar tú esta vinculación desde tu aplicación Alexa; si no, cancela.", allow: "Vincular", deny: "Cancelar", loginRequired: "Inicia sesión primero en el panel de Binario y vuelve a intentar la vinculación de Alexa.", errorTitle: "No se pudo vincular Alexa", notConfigured: "La integración de Alexa no está configurada. No se puede vincular ninguna cuenta.", invalidRequest: "La solicitud de vinculación no es válida o ya no está disponible. Reinicia el proceso desde la aplicación Alexa.", invalidCsrf: "La comprobación de seguridad ha caducado o no es válida. No se vinculó ninguna cuenta; reinicia el proceso desde la aplicación Alexa.", returnDashboard: "Volver al panel" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Para usar información personal, primero vincula tu cuenta de Binario en la aplicación Alexa.", unsupported: "No he entendido esa solicitud. Puedes pedir la puntualidad de un tren o de una de tus rutas, el tren más puntual de las próximas tres horas o tus rutas.", help: "Con Binario puedes pedir los próximos trenes de una ruta guardada, el estado de un tren, la puntualidad de un tren o de una de tus rutas, el tren más puntual de las próximas tres horas en una de tus rutas, o la lista de tus rutas. ¿Qué quieres saber?", goodbye: "Hasta pronto.", launch: "Te damos la bienvenida a Binario. Puedes pedir la puntualidad de un tren o de una de tus rutas, el tren más puntual de las próximas tres horas o tus rutas.", noRoutes: "Aún no has guardado ninguna ruta en Binario.", routeSubject: "Para la ruta {routeName}", trainSubject: "Para el tren {trainNumber}", liveDataUnavailable: "los datos en tiempo real no están disponibles", routesSaved: "Tus rutas guardadas son: {routes}{suffix}", moreRoutes: { one: ". Hay otra ruta en el panel de Binario.", other: ". Hay {count} rutas más en el panel de Binario." }, error: "Se produjo un problema. Inténtalo de nuevo más tarde.", launchReprompt: "¿Qué quieres saber? Puedes pedir la puntualidad de un tren o de una de tus rutas, el tren más puntual de las próximas tres horas o tus rutas.", listTerminator: ".", routeNotFound: "No he encontrado la ruta {routeName}.", routeAmbiguous: "He encontrado varias rutas: {routes}.", routeChoice: "¿Qué ruta quieres? Puedes decir, por ejemplo: próximos trenes para {example}." },
  screen: { homeTitle: "Qué puedes preguntar", departuresTitle: "Salidas", trainStatusTitle: "Estado del tren", myRoutesTitle: "Mis rutas", demoData: "Catálogo de demostración local — no en directo", sourceNone: "Fuente: ningún proveedor ferroviario en tiempo real conectado", sourceDemo: "Fuente: datos de demostración, no operativos", checkedAt: "Comprobado a las {time}", noTimetable: "No se muestran horarios: Binario nunca inventa salidas, retrasos ni andenes.", trainNumber: "Tren {trainNumber}", trainNumberMissing: "No se ha indicado número de tren", pinned: "Fijada", moreRoutes: { one: "Hay otra ruta en el panel de Binario.", other: "Hay {count} rutas más en el panel de Binario." }, showRoute: "Próximos trenes para {routeName}", nextTrainsAction: "Próximos trenes", myRoutesAction: "Mis rutas", hintsTitle: "Prueba a preguntar", hintNextTrains: "«Alexa, pregunta a Binario Trenes por los próximos trenes»", hintTrainStatus: "«Alexa, pregunta a Binario Trenes por el estado del tren 9512»", hintMyRoutes: "«Alexa, pregunta a Binario Trenes por mis rutas»" },
  punctuality: {
    train: { one: "El tren {trainNumber} llegó puntual a su destino final, es decir, con un retraso máximo de {limit}, {count} vez de cada 100 en {days}.", many: "El tren {trainNumber} llegó puntual a su destino final, es decir, con un retraso máximo de {limit}, {count} veces de cada 100 en {days}.", other: "El tren {trainNumber} llegó puntual a su destino final, es decir, con un retraso máximo de {limit}, {count} veces de cada 100 en {days}." },
    route: { one: "En la ruta {routeName}, con {trains} en {days}, las llegadas al destino fueron puntuales, es decir, con un retraso máximo de {limit}, {count} vez de cada 100.", many: "En la ruta {routeName}, con {trains} en {days}, las llegadas al destino fueron puntuales, es decir, con un retraso máximo de {limit}, {count} veces de cada 100.", other: "En la ruta {routeName}, con {trains} en {days}, las llegadas al destino fueron puntuales, es decir, con un retraso máximo de {limit}, {count} veces de cada 100." },
    mixedLimit: "5 minutos en los Frecce, Italo, EuroCity y EuroNight y de 15 minutos en los demás trenes",
    meanDelay: { one: "El retraso medio fue de {count} minuto.", many: "El retraso medio fue de {count} minutos.", other: "El retraso medio fue de {count} minutos." },
    noMeanDelay: "En promedio no hubo retraso.",
    cancelled: { one: "Se canceló {count} vez.", many: "Se canceló {count} veces.", other: "Se canceló {count} veces." },
    cancelledEveryDay: "El tren {trainNumber} se canceló en cada uno de los {days}.",
    source: "Son estadísticas no oficiales de Binario con datos de ViaggiaTreno.",
    unavailable: "Las estadísticas de puntualidad de Binario aún no están disponibles.",
    trainUnknown: "No tengo estadísticas de puntualidad del tren {trainNumber}.",
    trainFewData: "Todavía no hay suficientes observaciones del tren {trainNumber}.",
    trainSharedNumber: "Varios trenes circulan con el número {trainNumber}, desde orígenes distintos, así que no sé a cuál te refieres.",
    trainMissing: "¿De qué tren quieres saber la puntualidad? Puedes decir, por ejemplo: puntualidad del tren 9558.",
    routeFewData: "Todavía no hay suficientes observaciones de la ruta {routeName}.",
    routeStationsUnknown: "No reconozco las estaciones de la ruta {routeName}: en el panel de Binario, indica el origen y el destino con el nombre de la estación, por ejemplo Roma Termini.",
    routeChoice: "¿Qué ruta quieres? Puedes decir, por ejemplo: puntualidad de mi ruta {example}.",
    minutes: { one: "{count} minuto", many: "{count} minutos", other: "{count} minutos" },
    observedDays: { one: "{count} día observado", many: "{count} días observados", other: "{count} días observados" },
    trainCount: { one: "{count} tren", many: "{count} trenes", other: "{count} trenes" },
    screenTitle: "Puntualidad",
    statisticsLabel: "Estadísticas no oficiales de Binario con datos de ViaggiaTreno",
    statisticsNotLive: "Estadísticas, no datos en tiempo real",
    statisticsUntil: "Datos hasta el {date}",
    onTimeLabel: "Puntual",
    meanDelayLabel: "Retraso medio",
    observedDaysLabel: "Días observados",
    trainsLabel: "Trenes",
    minutesValue: "{count} min",
    showRoute: "Puntualidad de la ruta {routeName}",
    hint: "«Alexa, pregunta a Binario Trenes por la puntualidad del tren 9558»",
    mostPunctualBest: "En la ruta {routeName}, de los trenes que suelen salir en las próximas tres horas, el más puntual es {train}.",
    mostPunctualItalianTime: "Los horarios están en hora italiana.",
    mostPunctualOthers: "Después, {trains}.",
    mostPunctualTrain: { one: "el {trainNumber} de la {time}, puntual {onTime}", many: "el {trainNumber} de las {time}, puntual {onTime}", other: "el {trainNumber} de las {time}, puntual {onTime}" },
    outOfTen: { one: "{count} vez de cada 10", many: "{count} veces de cada 10", other: "{count} veces de cada 10" },
    mostPunctualNone: "En la ruta {routeName} no sale ningún tren con suficientes observaciones en las próximas tres horas.",
    mostPunctualRouteChoice: "¿Qué ruta quieres? Puedes decir, por ejemplo: cuál es el tren más puntual para {example}.",
    showRouteMostPunctual: "Tren más puntual para {routeName}",
    mostPunctualHint: "«Alexa, pregunta a Binario Trenes cuál es el tren más puntual para casa lavoro»",
  },
};

export const LOCALE_MESSAGES: Record<Language, LocaleMessages> = {
  it, en, de, fr, es, ar, hi, id, ja, ko, my, pt, ru, th, tr, vi, zh,
};

function localeMap<T extends string>(locales: readonly T[]): Record<string, T> {
  return Object.fromEntries(locales.map(locale => [locale.toLowerCase(), locale])) as Record<string, T>;
}

const exactLocaleMap = localeMap(SUPPORTED_LOCALES);
const exactWebLocaleMap = localeMap(WEB_LOCALES);

const languageDefaultLocale: Partial<Record<Language, SupportedLocale>> = {
  it: "it-IT",
  en: "en-US",
  de: "de-DE",
  fr: "fr-FR",
  es: "es-ES",
  ar: "ar-SA",
  hi: "hi-IN",
  ja: "ja-JP",
  // Neutral Brazilian Portuguese also serves pt-PT and the other Portuguese variants.
  pt: "pt-BR",
};

const languageDefaultWebLocale: Record<Language, WebLocale> = {
  ...(languageDefaultLocale as Record<"it" | "en" | "de" | "fr" | "es" | "ar" | "hi" | "ja" | "pt", SupportedLocale>),
  id: "id-ID",
  ko: "ko-KR",
  my: "my-MM",
  ru: "ru-RU",
  th: "th-TH",
  tr: "tr-TR",
  vi: "vi-VN",
  zh: "zh-Hans",
};

function languageForLocale(locale: WebLocale): Language {
  return locale.slice(0, 2) as Language;
}

/** Traditional-script Chinese preferences are not served Simplified Chinese silently. */
function isTraditionalChinese(normalized: string) {
  const subtags = normalized.split("-");
  return (
    subtags[0] === "zh" &&
    !subtags.includes("hans") &&
    (subtags.includes("hant") || subtags.some(tag => tag === "tw" || tag === "hk" || tag === "mo"))
  );
}

type LocaleCandidate = { locale: string; quality: number; order: number };

function candidates(value: string | readonly string[] | undefined): string[] {
  const values = typeof value === "string" ? [value] : value ?? [];
  let order = 0;
  const parsed = values.flatMap<LocaleCandidate>(item =>
    item.split(",").flatMap<LocaleCandidate>(part => {
      const [rawLocale, ...parameters] = part.trim().split(";");
      const locale = rawLocale?.trim();
      const rawQuality = parameters
        .map(parameter => parameter.trim())
        .find(parameter => /^q\s*=/i.test(parameter))
        ?.split("=", 2)[1]
        ?.trim();
      const quality = rawQuality === undefined ? 1 : Number(rawQuality);
      const candidate = { locale: locale ?? "", quality, order: order++ };
      return locale && locale !== "*" && Number.isFinite(quality) && quality > 0 && quality <= 1
        ? [candidate]
        : [];
    })
  );
  return parsed
    .sort((left, right) => right.quality - left.quality || left.order - right.order)
    .map(candidate => candidate.locale);
}

function resolveFrom<T extends string>(
  value: string | readonly string[] | undefined,
  exact: Record<string, T>,
  byLanguage: Partial<Record<Language, T>>,
  fallback: T
): T {
  for (const candidate of candidates(value)) {
    const normalized = candidate.replaceAll("_", "-").toLowerCase();
    if (Object.hasOwn(exact, normalized)) return exact[normalized];
    if (isTraditionalChinese(normalized)) continue;
    const language = normalized.split("-", 1)[0] as Language;
    const locale = Object.hasOwn(byLanguage, language) ? byLanguage[language] : undefined;
    if (locale) return locale;
  }
  return fallback;
}

/**
 * Resolves an Alexa request locale to a skill locale. Italian is the safe fallback; Alexa only
 * sends locales published in skill.json, so the fallback covers malformed input.
 */
export function resolveLocale(
  value: string | readonly string[] | undefined,
  fallback: SupportedLocale = "it-IT"
): SupportedLocale {
  return resolveFrom(value, exactLocaleMap, languageDefaultLocale, fallback);
}

/** Resolves browser navigator languages or an HTTP Accept-Language header. Italian is the safe fallback. */
export function resolveWebLocale(
  value: string | readonly string[] | undefined,
  fallback: WebLocale = "it-IT"
): WebLocale {
  return resolveFrom(value, exactWebLocaleMap, languageDefaultWebLocale, fallback);
}

export function messagesForLocale(locale: WebLocale): LocaleMessages {
  return LOCALE_MESSAGES[languageForLocale(locale)];
}

/** Arabic is the only right-to-left language served. */
export function textDirection(locale: WebLocale): "ltr" | "rtl" {
  return languageForLocale(locale) === "ar" ? "rtl" : "ltr";
}

/** Interpolates explicitly named values only; unknown placeholders remain visible to expose dictionary mistakes. */
export function formatMessage(template: string, values: InterpolationValues = {}): string {
  return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (match, key: string) =>
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
  );
}

/** Selects singular/plural wording using the locale's native plural rules, then interpolates the count. */
export function formatPlural(
  locale: WebLocale,
  count: number,
  messages: PluralMessages
): string {
  const category = new Intl.PluralRules(locale).select(count);
  const extra = category === "one" || category === "other" ? undefined : messages[category];
  return formatMessage(extra ?? (category === "one" ? messages.one : messages.other), { count });
}

const LIST_SEPARATOR_FALLBACK: Partial<Record<Language, string>> = { ja: "、", zh: "、", ar: "، ", my: "၊ " };

/**
 * Joins spoken or displayed items the way the locale writes a list ("A, B e C", "A、B、C",
 * "A وB وC"). Falls back to a per-language separator where Intl.ListFormat is unavailable.
 */
export function formatList(locale: WebLocale, items: readonly string[]): string {
  try {
    if (typeof Intl.ListFormat === "function")
      return new Intl.ListFormat(locale, { type: "conjunction", style: "long" }).format(items);
  } catch {
    // Fall through to the separator.
  }
  return items.join(LIST_SEPARATOR_FALLBACK[languageForLocale(locale)] ?? ", ");
}

/** Sets the document language, and the direction for right-to-left locales only. */
export function localizeHtmlLang(template: string, locale: WebLocale): string {
  const direction = textDirection(locale) === "rtl" ? ' dir="rtl"' : "";
  return template.replace(/<html\s+lang="[^"]*"(?:\s+dir="[^"]*")?/i, `<html lang="${locale}"${direction}`);
}
