export type LocalJourneyArchiveDependencies = {
  listScheduledRouteIds: () => Promise<number[]>;
  cancelReminder: (routeId: number) => Promise<unknown>;
  clearRoutes: () => Promise<unknown>;
  clearItineraries?: () => Promise<unknown>;
};

export async function clearLocalJourneyArchive(dependencies: LocalJourneyArchiveDependencies) {
  const scheduledIds = await dependencies.listScheduledRouteIds();
  await Promise.all(scheduledIds.map(routeId => dependencies.cancelReminder(routeId)));
  await dependencies.clearRoutes();
  await dependencies.clearItineraries?.();
  return { clearedReminders: scheduledIds.length };
}

export type LocalPlannedRouteDeletionDependencies = {
  routeId: number;
  cancelReminder: (routeId: number) => Promise<boolean>;
  removeRoute: (routeId: number) => Promise<boolean>;
};

/** Annulla sempre il promemoria prima di rimuovere la tratta: nessuna notifica deve sopravvivere alla tratta che la motiva. */
export async function deleteLocalPlannedRoute({ routeId, cancelReminder, removeRoute }: LocalPlannedRouteDeletionDependencies) {
  const cancelledReminder = await cancelReminder(routeId);
  const removed = await removeRoute(routeId);
  return { removed, cancelledReminder };
}
