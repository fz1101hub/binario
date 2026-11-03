import express, { type Express } from "express";
import fs from "node:fs";
import path from "node:path";

/** Paths the dashboard client renders itself (client/src/App.tsx); anything else is a 404. */
export const CLIENT_ROUTES: ReadonlySet<string> = new Set(["/"]);

/**
 * Status for the index.html fallback: the SPA still renders its "not found" page, but an
 * unknown URL answers 404 instead of 200, so crawlers and Amazon's URL checks see a missing page.
 */
export function clientFallbackStatus(originalUrl: string) {
  return CLIENT_ROUTES.has(new URL(originalUrl, "http://binario.invalid").pathname) ? 200 : 404;
}

export function serveStatic(
  app: Express,
  distPath = process.env.NODE_ENV === "development"
    ? path.resolve(import.meta.dirname, "../..", "dist", "public")
    : path.resolve(import.meta.dirname, "public")
) {
  if (!fs.existsSync(distPath)) {
    console.error(
      `Could not find the build directory: ${distPath}, make sure to build the client first`
    );
  }

  app.use(express.static(distPath));

  // An unknown API path is an API 404, never the dashboard page.
  app.use("/api", (_req, res) => {
    res.status(404).json({ error: "not_found" });
  });

  // Fall through to index.html for client routes; other paths get it with a 404 status.
  app.use("*", (req, res) => {
    res.status(clientFallbackStatus(req.originalUrl)).sendFile(path.resolve(distPath, "index.html"));
  });
}
