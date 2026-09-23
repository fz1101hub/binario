import express, { type Express } from "express";
import type { Server } from "node:http";
import { rateLimit } from "express-rate-limit";
import { createExpressMiddleware } from "@trpc/server/adapters/express";
import { registerOAuthRoutes } from "./oauth";
import { registerStorageProxy } from "./storageProxy";
import { appRouter } from "../routers";
import { createContext } from "./context";
import { serveStatic, setupVite } from "./vite";
import { registerAlexaRoutes } from "../voice/alexa";
import { registerAndroidDeepLinks } from "../voice/deepLinks";
import { securityHeaders } from "./securityHeaders";
import { ENV } from "./env";

// No endpoint accepts uploads: a small body limit bounds parser memory.
export const BODY_LIMIT = "1mb";
export const OAUTH_REQUESTS_PER_MINUTE = 30;
export const API_REQUESTS_PER_MINUTE = 300;

// Behind a reverse proxy the client address and protocol come from the
// forwarded headers; TRUST_PROXY overrides the production default of one hop.
export function trustProxySetting(value: string, isProduction = ENV.isProduction): boolean | number | string {
  if (!value) return isProduction ? 1 : false;
  if (value === "true") return true;
  if (value === "false") return false;
  const hops = Number(value);
  return Number.isInteger(hops) && hops >= 0 ? hops : value;
}

function limiter(limit: number) {
  return rateLimit({ windowMs: 60_000, limit, standardHeaders: "draft-8", legacyHeaders: false, message: { error: "too_many_requests" } });
}

export async function createApp(options: { server?: Server; serveClient?: boolean } = {}): Promise<Express> {
  const app = express();
  app.set("trust proxy", trustProxySetting(ENV.trustProxy));
  app.disable("x-powered-by");
  app.use(securityHeaders());
  app.get("/healthz", (_req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.json({ status: "ok" });
  });
  app.use("/api/alexa/oauth", limiter(OAUTH_REQUESTS_PER_MINUTE));
  app.use("/api/oauth", limiter(OAUTH_REQUESTS_PER_MINUTE));
  app.use("/api/trpc", limiter(API_REQUESTS_PER_MINUTE));
  // Alexa needs the untouched signed body, so its own verifier is registered
  // before the generic JSON parser used by tRPC and the dashboard.
  registerAlexaRoutes(app);
  registerAndroidDeepLinks(app);
  app.use(express.json({ limit: BODY_LIMIT }));
  app.use(express.urlencoded({ limit: BODY_LIMIT, extended: true }));
  registerStorageProxy(app);
  registerOAuthRoutes(app);
  app.use(
    "/api/trpc",
    createExpressMiddleware({
      router: appRouter,
      createContext,
    })
  );
  if (options.serveClient !== false) {
    // development mode uses Vite, production mode uses static files
    if (process.env.NODE_ENV === "development" && options.server) {
      await setupVite(app, options.server);
    } else {
      serveStatic(app);
    }
  }
  return app;
}
