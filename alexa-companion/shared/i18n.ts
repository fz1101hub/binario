export const SUPPORTED_LOCALES = [
  "it-IT",
  "en-US",
  "en-GB",
  "de-DE",
  "fr-FR",
  "es-ES",
] as const;

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];
type Language = "it" | "en" | "de" | "fr" | "es";
export type InterpolationValues = Record<string, string | number>;
export type PluralMessages = { one: string; other: string };

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
    noRoutes: string;
    unpinRoute: string;
    pinNamedRoute: string;
    removeRoute: string;
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
    noRoutes: "Non hai ancora salvato tratte. Aggiungine una per preparare le future richieste vocali.",
    unpinRoute: "Rimuovi {routeName} dalle tratte fissate",
    pinNamedRoute: "Fissa {routeName}",
    removeRoute: "Rimuovi la tratta {routeName}",
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
    unsupported: "Non ho capito la richiesta. Puoi chiedere i prossimi treni, lo stato di un treno oppure le tue tratte.",
    help: "Con Binario puoi chiedere i prossimi treni di una tratta salvata, lo stato di un treno oppure l'elenco delle tue tratte. Cosa vuoi sapere?",
    goodbye: "A presto.",
    launch: "Benvenuta in Binario. Puoi chiedere i prossimi treni, lo stato di un treno o le tue tratte.",
    noRoutes: "Non hai ancora salvato alcuna tratta in Binario.",
    routeSubject: "Per la tratta {routeName}", trainSubject: "Per il treno {trainNumber}",
    liveDataUnavailable: "dati non disponibili in tempo reale",
    routesSaved: "Le tue tratte salvate sono: {routes}{suffix}",
    moreRoutes: { one: ". C’è un’altra tratta nella dashboard Binario.", other: ". Ci sono altre {count} tratte nella dashboard Binario." },
    error: "Si è verificato un problema. Riprova più tardi.",
  },
};

const en: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Toggle navigation", menu: "Menu", signInTitle: "Sign in to Binario", signInDescription: "This dashboard only manages your routes and personal voice settings.", signIn: "Sign in", signOut: "Sign out",
    accountDeletedTitle: "Account deleted", accountDeletedDescription: "The Binario Companion profile, routes, and associated Alexa credentials have been deleted from the operational database. An old session cannot recreate the account.", productName: "Binario Alexa Companion", heroTitle: "Link Alexa to your Binario app.", heroDescription: "This is a technical linking surface: the smartphone app remains the main product and the source of your routes. Alexa accesses personal data only after verified consent.", liveDataNotConnected: "Live data not connected", personalRoutes: "Personal routes", personalRoutesDescription: "Stored in your Binario profile. No travel history or location is recorded.", routeName: "Name", routeNamePlaceholder: "Home — work", origin: "Origin", originPlaceholder: "London Waterloo", destination: "Destination", destinationPlaceholder: "Reading", pinRoute: "Pin route", save: "Save", loadingRoutes: "Loading routes…", noRoutes: "You have not saved any routes yet. Add one to prepare future voice requests.", unpinRoute: "Remove {routeName} from pinned routes", pinNamedRoute: "Pin {routeName}", removeRoute: "Remove route {routeName}", alexa: "Alexa", alexaDescription: "OAuth 2.0 account linking and signed webhook.", alexaConfigured: "Server configuration is available. Enable the skill in the Alexa app to start linking.", alexaNotConfigured: "Not active. Alexa console configuration is missing; the webhook rejects every request.", accountStatus: "Account status", accountLinked: "Linked and verified.", accountNotLinked: "Not linked. Personal responses remain blocked.", unlinkAlexa: "Unlink Alexa account", linkingInstructions: "After configuring the skill, enable it in the Alexa app: linking starts only from Alexa’s official OAuth flow.", dataGuarantee: "Rail data guarantee", dataGuaranteeDescription: "Until an authorised rail data source is configured, Binario responds exactly: “{liveDataUnavailable}”.", androidAssistant: "Google Assistant on Android", androidAssistantDescription: "App Actions can open Binario on the phone or Wear OS. They do not replace a conversational Custom Skill on Google Home.", activeRouteDeepLink: "Try active-route deep link", accountAndData: "Account and data", accountAndDataDescription: "Unlinking Alexa revokes voice access only. Deletion instead removes the Companion profile and associated data from the operational database.", deleteAccount: "Delete account and data", deleteAccountConfirmTitle: "Permanently delete the account?", deleteAccountConfirmDescription: "Routes, preferences, Companion devices, and Alexa credentials will be deleted from the operational database. This cannot be undone; retention of any logs and backups depends on the controller’s policy.", deleteAccountConfirmationContract: "For security, the confirmation command sent to the server remains “ELIMINA” in every language.", cancel: "Cancel", deletePermanently: "Delete permanently", deleteFailed: "Deletion failed. No data was marked as deleted: please try again.", notFoundTitle: "Page not found", notFoundDescription: "The page you are looking for does not exist.", notFoundMoved: "It may have been moved or deleted.", goHome: "Go home", unexpectedError: "An unexpected error occurred.", reloadPage: "Reload page",
  },
  accessibility: { close: "Close", loading: "Loading", pagination: "Pagination", previousPage: "Go to previous page", nextPage: "Go to next page", morePages: "More pages", previousSlide: "Previous slide", nextSlide: "Next slide", breadcrumb: "Breadcrumb", more: "More", mobileSidebar: "Sidebar", mobileSidebarDescription: "Displays the mobile sidebar." },
  consent: { title: "Link Alexa to Binario", signedInAs: " as <strong>{userName}</strong>", description: "You are about to link Amazon Alexa to your Binario account{signedInAs}. Alexa will be able to read the names of routes you have saved. No real-time data is shared.", allow: "Link", deny: "Cancel", loginRequired: "Sign in to the Binario dashboard first, then repeat Alexa linking.", errorTitle: "Alexa linking failed", notConfigured: "The Alexa integration is not configured. No account can be linked.", invalidRequest: "The linking request is invalid or no longer available. Restart the process from the Alexa app.", invalidCsrf: "The security check has expired or is invalid. No account was linked; restart the process from the Alexa app.", returnDashboard: "Return to the dashboard" },
  voice: { cardTitle: "Binario", accountLinkRequired: "To use personal information, first link your Binario account in the Alexa app.", unsupported: "I didn't understand that request. You can ask for the next trains, a train's status, or your routes.", help: "With Binario, you can ask for the next trains on a saved route, a train's status, or a list of your routes. What would you like to know?", goodbye: "See you soon.", launch: "Welcome to Binario. You can ask for the next trains, a train's status, or your routes.", noRoutes: "You have not saved any routes in Binario yet.", routeSubject: "For the route {routeName}", trainSubject: "For train {trainNumber}", liveDataUnavailable: "real-time data is not available", routesSaved: "Your saved routes are: {routes}{suffix}", moreRoutes: { one: ". There is one more route in the Binario dashboard.", other: ". There are {count} more routes in the Binario dashboard." }, error: "A problem occurred. Please try again later." },
};

const de: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Navigation umschalten", menu: "Menü", signInTitle: "Bei Binario anmelden", signInDescription: "Dieses Dashboard verwaltet nur Ihre Strecken und persönlichen Spracheinstellungen.", signIn: "Anmelden", signOut: "Abmelden", accountDeletedTitle: "Konto gelöscht", accountDeletedDescription: "Das Binario-Companion-Profil, die Strecken und die zugehörigen Alexa-Zugangsdaten wurden aus der operativen Datenbank gelöscht. Eine alte Sitzung kann das Konto nicht erneut erstellen.", productName: "Binario Alexa Companion", heroTitle: "Alexa mit Ihrer Binario-App verknüpfen.", heroDescription: "Dies ist eine technische Verknüpfungsoberfläche: Die Smartphone-App bleibt das Hauptprodukt und die Quelle Ihrer Strecken. Alexa greift erst nach einer bestätigten Einwilligung auf personenbezogene Daten zu.", liveDataNotConnected: "Live-Daten nicht verbunden", personalRoutes: "Persönliche Strecken", personalRoutesDescription: "In Ihrem Binario-Profil gespeichert. Es werden weder Reiseverlauf noch Standort erfasst.", routeName: "Name", routeNamePlaceholder: "Zuhause — Arbeit", origin: "Start", originPlaceholder: "Berlin Hbf", destination: "Ziel", destinationPlaceholder: "Potsdam Hbf", pinRoute: "Strecke anheften", save: "Speichern", loadingRoutes: "Strecken werden geladen…", noRoutes: "Sie haben noch keine Strecken gespeichert. Fügen Sie eine hinzu, um spätere Sprachanfragen vorzubereiten.", unpinRoute: "{routeName} aus den angehefteten Strecken entfernen", pinNamedRoute: "{routeName} anheften", removeRoute: "Strecke {routeName} entfernen", alexa: "Alexa", alexaDescription: "OAuth-2.0-Kontoverknüpfung und signierter Webhook.", alexaConfigured: "Serverkonfiguration verfügbar. Aktivieren Sie den Skill in der Alexa-App, um die Verknüpfung zu starten.", alexaNotConfigured: "Nicht aktiv. Die Alexa-Console-Konfiguration fehlt; der Webhook weist jede Anfrage zurück.", accountStatus: "Kontostatus", accountLinked: "Verknüpft und bestätigt.", accountNotLinked: "Nicht verknüpft. Persönliche Antworten bleiben gesperrt.", unlinkAlexa: "Alexa-Konto trennen", linkingInstructions: "Aktivieren Sie den Skill nach der Konfiguration in der Alexa-App: Die Verknüpfung startet nur über den offiziellen OAuth-Ablauf von Alexa.", dataGuarantee: "Garantie für Bahndaten", dataGuaranteeDescription: "Solange keine autorisierte Bahndatenquelle konfiguriert ist, antwortet Binario genau: „{liveDataUnavailable}“.", androidAssistant: "Google Assistant auf Android", androidAssistantDescription: "App Actions können Binario auf dem Telefon oder Wear OS öffnen. Sie ersetzen keinen dialogorientierten Custom Skill auf Google Home.", activeRouteDeepLink: "Deep Link für aktive Strecke testen", accountAndData: "Konto und Daten", accountAndDataDescription: "Das Trennen von Alexa widerruft nur den Sprachzugriff. Das Löschen entfernt dagegen das Companion-Profil und die zugehörigen Daten aus der operativen Datenbank.", deleteAccount: "Konto und Daten löschen", deleteAccountConfirmTitle: "Konto endgültig löschen?", deleteAccountConfirmDescription: "Strecken, Einstellungen, Companion-Geräte und Alexa-Zugangsdaten werden aus der operativen Datenbank gelöscht. Der Vorgang kann nicht rückgängig gemacht werden; die Aufbewahrung möglicher Logs und Backups richtet sich nach der Richtlinie des Verantwortlichen.", deleteAccountConfirmationContract: "Aus Sicherheitsgründen bleibt der an den Server gesendete Bestätigungsbefehl in allen Sprachen „ELIMINA“.", cancel: "Abbrechen", deletePermanently: "Endgültig löschen", deleteFailed: "Löschen fehlgeschlagen. Es wurden keine Daten als gelöscht markiert: Bitte versuchen Sie es erneut.", notFoundTitle: "Seite nicht gefunden", notFoundDescription: "Die gesuchte Seite existiert nicht.", notFoundMoved: "Sie wurde möglicherweise verschoben oder gelöscht.", goHome: "Zur Startseite", unexpectedError: "Ein unerwarteter Fehler ist aufgetreten.", reloadPage: "Seite neu laden",
  },
  accessibility: { close: "Schließen", loading: "Laden", pagination: "Seitennavigation", previousPage: "Zur vorherigen Seite", nextPage: "Zur nächsten Seite", morePages: "Weitere Seiten", previousSlide: "Vorherige Folie", nextSlide: "Nächste Folie", breadcrumb: "Navigationspfad", more: "Mehr", mobileSidebar: "Seitenleiste", mobileSidebarDescription: "Zeigt die mobile Seitenleiste an." },
  consent: { title: "Alexa mit Binario verknüpfen", signedInAs: " als <strong>{userName}</strong>", description: "Sie sind dabei, Amazon Alexa mit Ihrem Binario-Konto{signedInAs} zu verknüpfen. Alexa kann die Namen Ihrer gespeicherten Strecken lesen. Es werden keine Echtzeitdaten weitergegeben.", allow: "Verknüpfen", deny: "Abbrechen", loginRequired: "Melden Sie sich zuerst im Binario-Dashboard an und wiederholen Sie dann die Alexa-Verknüpfung.", errorTitle: "Alexa-Verknüpfung fehlgeschlagen", notConfigured: "Die Alexa-Integration ist nicht konfiguriert. Es kann kein Konto verknüpft werden.", invalidRequest: "Die Verknüpfungsanfrage ist ungültig oder nicht mehr verfügbar. Starten Sie den Vorgang in der Alexa-App erneut.", invalidCsrf: "Die Sicherheitsprüfung ist abgelaufen oder ungültig. Es wurde kein Konto verknüpft; starten Sie den Vorgang in der Alexa-App erneut.", returnDashboard: "Zurück zum Dashboard" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Um persönliche Informationen zu verwenden, verknüpfen Sie zuerst Ihr Binario-Konto in der Alexa-App.", unsupported: "Ich habe diese Anfrage nicht verstanden. Sie können nach den nächsten Zügen, dem Status eines Zuges oder Ihren Strecken fragen.", help: "Mit Binario können Sie nach den nächsten Zügen einer gespeicherten Strecke, dem Status eines Zuges oder der Liste Ihrer Strecken fragen. Was möchten Sie wissen?", goodbye: "Bis bald.", launch: "Willkommen bei Binario. Sie können nach den nächsten Zügen, dem Status eines Zuges oder Ihren Strecken fragen.", noRoutes: "Sie haben noch keine Strecken in Binario gespeichert.", routeSubject: "Für die Strecke {routeName}", trainSubject: "Für Zug {trainNumber}", liveDataUnavailable: "Echtzeitdaten sind nicht verfügbar", routesSaved: "Ihre gespeicherten Strecken sind: {routes}{suffix}", moreRoutes: { one: ". Es gibt eine weitere Strecke im Binario-Dashboard.", other: ". Es gibt {count} weitere Strecken im Binario-Dashboard." }, error: "Ein Problem ist aufgetreten. Bitte versuchen Sie es später erneut." },
};

const fr: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Basculer la navigation", menu: "Menu", signInTitle: "Se connecter à Binario", signInDescription: "Ce tableau de bord gère uniquement vos trajets et vos réglages vocaux personnels.", signIn: "Se connecter", signOut: "Se déconnecter", accountDeletedTitle: "Compte supprimé", accountDeletedDescription: "Le profil Binario Companion, les trajets et les identifiants Alexa associés ont été supprimés de la base de données opérationnelle. Une ancienne session ne peut pas recréer le compte.", productName: "Binario Alexa Companion", heroTitle: "Associez Alexa à votre application Binario.", heroDescription: "Il s’agit d’une surface technique d’association : l’application smartphone reste le produit principal et la source de vos trajets. Alexa n’accède aux données personnelles qu’après un consentement vérifié.", liveDataNotConnected: "Données en direct non connectées", personalRoutes: "Trajets personnels", personalRoutesDescription: "Conservés dans votre profil Binario. Aucun historique de voyage ni position n’est enregistré.", routeName: "Nom", routeNamePlaceholder: "Maison — travail", origin: "Départ", originPlaceholder: "Paris Gare du Nord", destination: "Destination", destinationPlaceholder: "Lille Flandres", pinRoute: "Épingler le trajet", save: "Enregistrer", loadingRoutes: "Chargement des trajets…", noRoutes: "Vous n’avez pas encore enregistré de trajets. Ajoutez-en un pour préparer de futures demandes vocales.", unpinRoute: "Retirer {routeName} des trajets épinglés", pinNamedRoute: "Épingler {routeName}", removeRoute: "Supprimer le trajet {routeName}", alexa: "Alexa", alexaDescription: "Association de compte OAuth 2.0 et webhook signé.", alexaConfigured: "La configuration du serveur est disponible. Activez le skill dans l’application Alexa pour commencer l’association.", alexaNotConfigured: "Inactif. La configuration de la console Alexa est absente ; le webhook rejette toutes les demandes.", accountStatus: "État du compte", accountLinked: "Associé et vérifié.", accountNotLinked: "Non associé. Les réponses personnelles restent bloquées.", unlinkAlexa: "Dissocier le compte Alexa", linkingInstructions: "Après avoir configuré le skill, activez-le dans l’application Alexa : l’association ne démarre que depuis le flux OAuth officiel d’Alexa.", dataGuarantee: "Garantie sur les données ferroviaires", dataGuaranteeDescription: "Tant qu’une source de données ferroviaires autorisée n’est pas configurée, Binario répond exactement : « {liveDataUnavailable} ».", androidAssistant: "Google Assistant sur Android", androidAssistantDescription: "Les App Actions peuvent ouvrir Binario sur le téléphone ou Wear OS. Elles ne remplacent pas un Custom Skill conversationnel sur Google Home.", activeRouteDeepLink: "Essayer le lien profond du trajet actif", accountAndData: "Compte et données", accountAndDataDescription: "Dissocier Alexa révoque uniquement l’accès vocal. La suppression retire en revanche le profil Companion et les données associées de la base opérationnelle.", deleteAccount: "Supprimer le compte et les données", deleteAccountConfirmTitle: "Supprimer définitivement le compte ?", deleteAccountConfirmDescription: "Les trajets, préférences, appareils Companion et identifiants Alexa seront supprimés de la base de données opérationnelle. Cette opération est irréversible ; la conservation d’éventuels journaux et sauvegardes dépend de la politique du responsable.", deleteAccountConfirmationContract: "Pour des raisons de sécurité, la commande de confirmation envoyée au serveur reste « ELIMINA » dans toutes les langues.", cancel: "Annuler", deletePermanently: "Supprimer définitivement", deleteFailed: "La suppression a échoué. Aucune donnée n’a été signalée comme supprimée : réessayez.", notFoundTitle: "Page introuvable", notFoundDescription: "La page que vous recherchez n’existe pas.", notFoundMoved: "Elle a peut-être été déplacée ou supprimée.", goHome: "Aller à l’accueil", unexpectedError: "Une erreur inattendue s’est produite.", reloadPage: "Recharger la page",
  },
  accessibility: { close: "Fermer", loading: "Chargement", pagination: "Pagination", previousPage: "Aller à la page précédente", nextPage: "Aller à la page suivante", morePages: "Autres pages", previousSlide: "Diapositive précédente", nextSlide: "Diapositive suivante", breadcrumb: "Fil d’Ariane", more: "Plus", mobileSidebar: "Barre latérale", mobileSidebarDescription: "Affiche la barre latérale mobile." },
  consent: { title: "Associer Alexa à Binario", signedInAs: " en tant que <strong>{userName}</strong>", description: "Vous êtes sur le point d’associer Amazon Alexa à votre compte Binario{signedInAs}. Alexa pourra lire les noms des trajets que vous avez enregistrés. Aucune donnée en temps réel n’est partagée.", allow: "Associer", deny: "Annuler", loginRequired: "Connectez-vous d’abord au tableau de bord Binario, puis recommencez l’association Alexa.", errorTitle: "Échec de l’association Alexa", notConfigured: "L’intégration Alexa n’est pas configurée. Aucun compte ne peut être associé.", invalidRequest: "La demande d’association est invalide ou n’est plus disponible. Recommencez la procédure depuis l’application Alexa.", invalidCsrf: "La vérification de sécurité a expiré ou n’est pas valide. Aucun compte n’a été associé ; recommencez la procédure depuis l’application Alexa.", returnDashboard: "Retourner au tableau de bord" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Pour utiliser les informations personnelles, associez d’abord votre compte Binario dans l’application Alexa.", unsupported: "Je n’ai pas compris cette demande. Vous pouvez demander les prochains trains, l’état d’un train ou vos trajets.", help: "Avec Binario, vous pouvez demander les prochains trains d’un trajet enregistré, l’état d’un train ou la liste de vos trajets. Que voulez-vous savoir ?", goodbye: "À bientôt.", launch: "Bienvenue dans Binario. Vous pouvez demander les prochains trains, l’état d’un train ou vos trajets.", noRoutes: "Vous n’avez encore enregistré aucun trajet dans Binario.", routeSubject: "Pour le trajet {routeName}", trainSubject: "Pour le train {trainNumber}", liveDataUnavailable: "les données en temps réel ne sont pas disponibles", routesSaved: "Vos trajets enregistrés sont : {routes}{suffix}", moreRoutes: { one: ". Il y a un autre trajet dans le tableau de bord Binario.", other: ". Il y a {count} autres trajets dans le tableau de bord Binario." }, error: "Un problème est survenu. Réessayez plus tard." },
};

const es: LocaleMessages = {
  dashboard: {
    documentTitle: "Binario Alexa Companion", navigation: "Alternar navegación", menu: "Menú", signInTitle: "Iniciar sesión en Binario", signInDescription: "Este panel solo gestiona tus rutas y ajustes de voz personales.", signIn: "Iniciar sesión", signOut: "Cerrar sesión", accountDeletedTitle: "Cuenta eliminada", accountDeletedDescription: "El perfil de Binario Companion, las rutas y las credenciales de Alexa asociadas se han eliminado de la base de datos operativa. Una sesión antigua no puede recrear la cuenta.", productName: "Binario Alexa Companion", heroTitle: "Vincula Alexa a tu aplicación Binario.", heroDescription: "Esta es una superficie técnica de vinculación: la aplicación móvil sigue siendo el producto principal y la fuente de tus rutas. Alexa accede a los datos personales solo después de un consentimiento verificado.", liveDataNotConnected: "Datos en directo no conectados", personalRoutes: "Rutas personales", personalRoutesDescription: "Guardadas en tu perfil de Binario. No se registra ningún historial de viaje ni ubicación.", routeName: "Nombre", routeNamePlaceholder: "Casa — trabajo", origin: "Origen", originPlaceholder: "Madrid Puerta de Atocha", destination: "Destino", destinationPlaceholder: "Toledo", pinRoute: "Fijar ruta", save: "Guardar", loadingRoutes: "Cargando rutas…", noRoutes: "Aún no has guardado rutas. Añade una para preparar futuras solicitudes de voz.", unpinRoute: "Quitar {routeName} de las rutas fijadas", pinNamedRoute: "Fijar {routeName}", removeRoute: "Eliminar la ruta {routeName}", alexa: "Alexa", alexaDescription: "Vinculación de cuenta OAuth 2.0 y webhook firmado.", alexaConfigured: "La configuración del servidor está disponible. Activa la skill en la aplicación Alexa para iniciar la vinculación.", alexaNotConfigured: "No activo. Falta la configuración de la consola Alexa; el webhook rechaza todas las solicitudes.", accountStatus: "Estado de la cuenta", accountLinked: "Vinculada y verificada.", accountNotLinked: "No vinculada. Las respuestas personales permanecen bloqueadas.", unlinkAlexa: "Desvincular cuenta de Alexa", linkingInstructions: "Después de configurar la skill, actívala en la aplicación Alexa: la vinculación solo se inicia desde el flujo OAuth oficial de Alexa.", dataGuarantee: "Garantía de datos ferroviarios", dataGuaranteeDescription: "Hasta que se configure una fuente ferroviaria autorizada, Binario responde exactamente: «{liveDataUnavailable}».", androidAssistant: "Google Assistant en Android", androidAssistantDescription: "Las App Actions pueden abrir Binario en el teléfono o en Wear OS. No sustituyen una Custom Skill conversacional en Google Home.", activeRouteDeepLink: "Probar enlace profundo de ruta activa", accountAndData: "Cuenta y datos", accountAndDataDescription: "Desvincular Alexa revoca solo el acceso por voz. En cambio, eliminar borra el perfil Companion y los datos asociados de la base de datos operativa.", deleteAccount: "Eliminar cuenta y datos", deleteAccountConfirmTitle: "¿Eliminar la cuenta definitivamente?", deleteAccountConfirmDescription: "Las rutas, preferencias, dispositivos Companion y credenciales de Alexa se eliminarán de la base de datos operativa. La operación no se puede deshacer; la retención de posibles registros y copias de seguridad depende de la política del responsable.", deleteAccountConfirmationContract: "Por seguridad, el comando de confirmación enviado al servidor sigue siendo «ELIMINA» en todos los idiomas.", cancel: "Cancelar", deletePermanently: "Eliminar definitivamente", deleteFailed: "No se pudo eliminar. No se marcó ningún dato como eliminado: inténtalo de nuevo.", notFoundTitle: "Página no encontrada", notFoundDescription: "La página que buscas no existe.", notFoundMoved: "Puede que se haya movido o eliminado.", goHome: "Ir al inicio", unexpectedError: "Se produjo un error inesperado.", reloadPage: "Recargar página",
  },
  accessibility: { close: "Cerrar", loading: "Cargando", pagination: "Paginación", previousPage: "Ir a la página anterior", nextPage: "Ir a la página siguiente", morePages: "Más páginas", previousSlide: "Diapositiva anterior", nextSlide: "Diapositiva siguiente", breadcrumb: "Migas de pan", more: "Más", mobileSidebar: "Barra lateral", mobileSidebarDescription: "Muestra la barra lateral móvil." },
  consent: { title: "Vincular Alexa con Binario", signedInAs: " como <strong>{userName}</strong>", description: "Estás a punto de vincular Amazon Alexa con tu cuenta de Binario{signedInAs}. Alexa podrá leer los nombres de las rutas que has guardado. No se comparten datos en tiempo real.", allow: "Vincular", deny: "Cancelar", loginRequired: "Inicia sesión primero en el panel de Binario y vuelve a intentar la vinculación de Alexa.", errorTitle: "No se pudo vincular Alexa", notConfigured: "La integración de Alexa no está configurada. No se puede vincular ninguna cuenta.", invalidRequest: "La solicitud de vinculación no es válida o ya no está disponible. Reinicia el proceso desde la aplicación Alexa.", invalidCsrf: "La comprobación de seguridad ha caducado o no es válida. No se vinculó ninguna cuenta; reinicia el proceso desde la aplicación Alexa.", returnDashboard: "Volver al panel" },
  voice: { cardTitle: "Binario", accountLinkRequired: "Para usar información personal, primero vincula tu cuenta de Binario en la aplicación Alexa.", unsupported: "No he entendido esa solicitud. Puedes pedir los próximos trenes, el estado de un tren o tus rutas.", help: "Con Binario puedes pedir los próximos trenes de una ruta guardada, el estado de un tren o la lista de tus rutas. ¿Qué quieres saber?", goodbye: "Hasta pronto.", launch: "Te damos la bienvenida a Binario. Puedes pedir los próximos trenes, el estado de un tren o tus rutas.", noRoutes: "Aún no has guardado ninguna ruta en Binario.", routeSubject: "Para la ruta {routeName}", trainSubject: "Para el tren {trainNumber}", liveDataUnavailable: "los datos en tiempo real no están disponibles", routesSaved: "Tus rutas guardadas son: {routes}{suffix}", moreRoutes: { one: ". Hay otra ruta en el panel de Binario.", other: ". Hay {count} rutas más en el panel de Binario." }, error: "Se produjo un problema. Inténtalo de nuevo más tarde." },
};

export const LOCALE_MESSAGES: Record<Language, LocaleMessages> = { it, en, de, fr, es };

const exactLocaleMap: Record<string, SupportedLocale> = Object.fromEntries(
  SUPPORTED_LOCALES.map(locale => [locale.toLowerCase(), locale])
) as Record<string, SupportedLocale>;

const languageDefaultLocale: Record<Language, SupportedLocale> = {
  it: "it-IT",
  en: "en-US",
  de: "de-DE",
  fr: "fr-FR",
  es: "es-ES",
};

function languageForLocale(locale: SupportedLocale): Language {
  return locale.slice(0, 2) as Language;
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

/** Resolves browser navigator languages or an HTTP Accept-Language header. Italian is the safe fallback. */
export function resolveLocale(
  value: string | readonly string[] | undefined,
  fallback: SupportedLocale = "it-IT"
): SupportedLocale {
  for (const candidate of candidates(value)) {
    const normalized = candidate.replaceAll("_", "-").toLowerCase();
    const exact = exactLocaleMap[normalized];
    if (exact) return exact;
    const language = normalized.split("-", 1)[0] as Language;
    if (language in languageDefaultLocale) return languageDefaultLocale[language];
  }
  return fallback;
}

export function messagesForLocale(locale: SupportedLocale): LocaleMessages {
  return LOCALE_MESSAGES[languageForLocale(locale)];
}

/** Interpolates explicitly named values only; unknown placeholders remain visible to expose dictionary mistakes. */
export function formatMessage(template: string, values: InterpolationValues = {}): string {
  return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (match, key: string) =>
    Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match
  );
}

/** Selects singular/plural wording using the locale's native plural rules, then interpolates the count. */
export function formatPlural(
  locale: SupportedLocale,
  count: number,
  messages: PluralMessages
): string {
  const category = new Intl.PluralRules(locale).select(count);
  return formatMessage(category === "one" ? messages.one : messages.other, { count });
}

export function localizeHtmlLang(template: string, locale: SupportedLocale): string {
  return template.replace(/<html\s+lang="[^"]*"/i, `<html lang="${locale}"`);
}
