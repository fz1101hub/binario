export type RouteActionLabels = { pin: string; open: string; moveUp: string; moveDown: string; archive: string; restore: string; remove: string };

export function getRouteActionLabels(routeName: string, pinned: boolean): RouteActionLabels {
  return {
    pin: pinned ? `Rimuovi ${routeName} dalle tratte fissate` : `Fissa ${routeName}`,
    open: `Apri il dettaglio della tratta ${routeName}`,
    moveUp: `Sposta in alto ${routeName}`,
    moveDown: `Sposta in basso ${routeName}`,
    archive: `Archivia la tratta ${routeName}`,
    restore: `Ripristina la tratta ${routeName}`,
    remove: `Rimuovi la tratta ${routeName}`,
  };
}
