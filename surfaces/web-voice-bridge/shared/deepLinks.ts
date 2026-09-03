const stationCodePattern = /^[A-Z0-9_-]{1,64}$/;

export type BinarioDeepLink =
  | { kind: "route"; routeId: number }
  | { kind: "station"; name: string; code: string };

export function getRouteDetailPath(routeId: number) {
  if (!Number.isSafeInteger(routeId) || routeId < 1) throw new Error("Identificativo tratta non valido");
  return `/viaggi/${routeId}`;
}

export function parseBinarioInternalPath(value: string): BinarioDeepLink | null {
  if (!value.startsWith("/")) return null;
  try {
    const url = new URL(value, "https://binario.invalid");
    if (url.origin !== "https://binario.invalid" || url.hash) return null;
    const routeMatch = /^\/viaggi\/(\d+)$/.exec(url.pathname);
    if (routeMatch && !url.search) {
      const routeId = Number(routeMatch[1]);
      return Number.isSafeInteger(routeId) && routeId > 0 ? { kind: "route", routeId } : null;
    }
    if (url.pathname !== "/stazioni") return null;
    const name = url.searchParams.get("name")?.trim().replace(/\s+/g, " ") ?? "";
    const code = url.searchParams.get("code")?.trim().toUpperCase() ?? "";
    if (!name || name.length > 120 || !stationCodePattern.test(code) || Array.from(url.searchParams.keys()).some(key => key !== "name" && key !== "code")) return null;
    return { kind: "station", name, code };
  } catch { return null; }
}
