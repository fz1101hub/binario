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
