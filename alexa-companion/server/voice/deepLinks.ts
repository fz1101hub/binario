import type { Express, Request, Response } from "express";

function firstQueryValue(request: Request, key: string) {
  const value = request.query[key];
  return typeof value === "string" ? value.trim().slice(0, 120) : "";
}

function redirectToBinario(response: Response, path: string, params: Record<string, string>) {
  const destination = new URL(`binario://${path}`);
  Object.entries(params).forEach(([key, value]) => {
    if (value) destination.searchParams.set(key, value);
  });
  response.setHeader("Cache-Control", "no-store");
  return response.redirect(302, destination.toString());
}

/**
 * These universal endpoints carry no user data. The Android app owns the
 * actual navigation and must validate incoming values before presenting them.
 */
export function registerAndroidDeepLinks(app: Express) {
  app.get("/app/active-route", (_req, res) => redirectToBinario(res, "route/active", {}));
  app.get("/app/next-departures", (req, res) => redirectToBinario(res, "departures/next", { route: firstQueryValue(req, "route") }));
  app.get("/app/train-search", (req, res) => redirectToBinario(res, "train/search", { number: firstQueryValue(req, "number") }));
}
