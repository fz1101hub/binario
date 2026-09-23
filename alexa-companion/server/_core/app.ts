import express, { type Express, type RequestHandler } from "express";
import type { Server } from "node:http";
import { rateLimit } from "express-rate-limit";
import { createExpressMiddleware } from "@trpc/server/adapters/express";
import { registerOAuthRoutes } from "./oauth";
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
// Alexa requests reach the ASK SDK raw-body parser and signature verifier.
// Bound that work separately from dashboard OAuth and tRPC traffic.
export const ALEXA_WEBHOOK_REQUESTS_PER_MINUTE = 60;

const TRUST_PROXY_NAMED_PRESETS = new Set([
  "loopback",
  "linklocal",
  "uniquelocal",
]);

// Behind a reverse proxy the client address and protocol come from the
// forwarded headers; TRUST_PROXY overrides the production default of one hop.
export function trustProxySetting(
  value: string,
  isProduction = ENV.isProduction
): boolean | number | string {
  if (!value) return isProduction ? 1 : false;
  // `true` trusts every peer and lets any client control X-Forwarded-*.
  // Deployments must name a bounded hop count or a proxy-addr preset instead.
  if (value === "true")
    throw new Error("TRUST_PROXY=true is unsafe; use a hop count or approved preset");
  if (value === "false") return false;
  if (/^(0|[1-9]\d*)$/.test(value)) return Number(value);
  if (TRUST_PROXY_NAMED_PRESETS.has(value)) return value;
  throw new Error(
    "TRUST_PROXY must be false, a non-negative hop count, or loopback/linklocal/uniquelocal"
  );
}

function sameOriginRequest(req: Parameters<RequestHandler>[0]) {
  if (req.get("sec-fetch-site") === "cross-site") return false;
  const origin = req.get("origin");
  // Non-browser API clients do not send Origin. JSON remains non-formable in
  // browsers without a CORS preflight, while every form-compatible content
  // type is rejected below.
  if (!origin) return true;
  try {
    return new URL(origin).origin === `${req.protocol}://${req.get("host")}`;
  } catch {
    return false;
  }
}

/**
 * Browser dashboard mutations use JSON via the tRPC client. Rejecting every
 * other unsafe request form closes the form/multipart CSRF primitive even if a
 * future cookie attribute is accidentally weakened. API clients must also
 * supply a same-origin Origin header when they send one.
 */
export const trpcMutationCsrfGate: RequestHandler = (req, res, next) => {
  if (req.method !== "POST") return next();
  if (!req.is("application/json") || !sameOriginRequest(req)) {
    res.status(415).json({ error: "json_same_origin_required" });
    return;
  }
  next();
};

function limiter(limit: number) {
  return rateLimit({
    windowMs: 60_000,
    limit,
    standardHeaders: "draft-8",
    legacyHeaders: false,
    message: { error: "too_many_requests" },
  });
}

export async function createApp(
  options: { server?: Server; serveClient?: boolean } = {}
): Promise<Express> {
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
  app.use("/api/trpc", trpcMutationCsrfGate);
  app.use("/api/alexa/webhook", limiter(ALEXA_WEBHOOK_REQUESTS_PER_MINUTE));
  // Alexa needs the untouched signed body, so its own verifier is registered
  // before the generic JSON parser used by tRPC and the dashboard.
  registerAlexaRoutes(app);
  registerAndroidDeepLinks(app);
  app.use(express.json({ limit: BODY_LIMIT }));
  app.use(express.urlencoded({ limit: BODY_LIMIT, extended: true }));
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
