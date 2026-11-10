export type LocalJourneyArchiveDependencies = {
  listScheduledRouteIds: () => Promise<number[]>;
  cancelReminder: (routeId: number) => Promise<unknown>;
  clearRoutes: () => Promise<unknown>;
  /** Obbligatorio: gli itinerari a tappe referenziano le tratte e devono sparire insieme all’archivio. */
  clearItineraries: () => Promise<unknown>;
  /** Obbligatorio: file di esportazione e copie dei file importati rimasti nella cache dell’app. */
  clearCachedFiles: () => Promise<unknown>;
  /** Obbligatorio: i modelli di viaggio ripetono origine, destinazione e orario delle tratte abituali. */
  clearTemplates: () => Promise<unknown>;
  /** Obbligatorio: le stazioni recenti (codice, nome, ora d’uso). */
  clearRecentStations: () => Promise<unknown>;
};

export async function clearLocalJourneyArchive(dependencies: LocalJourneyArchiveDependencies) {
  if (typeof dependencies.clearItineraries !== "function") throw new TypeError("clearLocalJourneyArchive richiede clearItineraries: gli itinerari locali vanno cancellati insieme alle tratte.");
  if (typeof dependencies.clearCachedFiles !== "function") throw new TypeError("clearLocalJourneyArchive richiede clearCachedFiles: le copie in cache di esportazioni e importazioni contengono tratte.");
  if (typeof dependencies.clearTemplates !== "function" || typeof dependencies.clearRecentStations !== "function") throw new TypeError("clearLocalJourneyArchive richiede clearTemplates e clearRecentStations: modelli e stazioni recenti descrivono gli spostamenti abituali.");
  const scheduledIds = await dependencies.listScheduledRouteIds();
  await Promise.all(scheduledIds.map(routeId => dependencies.cancelReminder(routeId)));
  await dependencies.clearRoutes();
  await dependencies.clearItineraries();
  await dependencies.clearCachedFiles();
  await dependencies.clearTemplates();
  await dependencies.clearRecentStations();
  return { clearedReminders: scheduledIds.length };
}

export type LocalPlannedRouteDeletionDependencies = {
  routeId: number;
  cancelReminder: (routeId: number) => Promise<boolean>;
  removeRoute: (routeId: number) => Promise<boolean>;
  listRemainingRouteIds: () => Promise<number[]>;
  /** Obbligatorio: toglie la tratta eliminata dagli itinerari a tappe e scioglie i gruppi rimasti sotto due tappe. */
  reconcileItineraries: (routeIds: readonly number[]) => Promise<unknown>;
};

/** Annulla sempre il promemoria prima di rimuovere la tratta: nessuna notifica deve sopravvivere alla tratta che la motiva. */
export async function deleteLocalPlannedRoute({ routeId, cancelReminder, removeRoute, listRemainingRouteIds, reconcileItineraries }: LocalPlannedRouteDeletionDependencies) {
  const cancelledReminder = await cancelReminder(routeId);
  const removed = await removeRoute(routeId);
  if (removed) await reconcileItineraries(await listRemainingRouteIds());
  return { removed, cancelledReminder };
}
