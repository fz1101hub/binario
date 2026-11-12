import type { NextFunction, Request, Response } from "express";
import { ENV } from "./env";

/**
 * Production pages (the built dashboard, the consent, legal and error pages the server writes)
 * load scripts, styles, images and data only from this origin: no script but the bundle's, no
 * plugin, no `<base>`. Inline styles stay allowed (the server pages carry a `<style>` block, the
 * components set `style` attributes); images may be `data:` URIs. `form-action` is left out on
 * purpose: the consent form posts here and ends in a redirect to Amazon's account-linking host.
 */
export const CONTENT_SECURITY_POLICY = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "object-src 'none'",
  "base-uri 'none'",
  "frame-ancestors 'none'",
].join("; ");

/** One year, this host only: the session cookie (`__Host-`) and the OAuth pages are HTTPS only. */
export const STRICT_TRANSPORT_SECURITY = "max-age=31536000";

/**
 * The headers of every response. The dashboard must never be framed: a framing page could
 * otherwise drive it through postMessage or clickjacking (frame-ancestors is the CSP form,
 * X-Frame-Options the legacy one). The content policy and HSTS apply in production only: the
 * Vite development server injects inline scripts and runs on plain HTTP.
 */
export function securityHeaderValues(production: boolean = ENV.isProduction): Readonly<Record<string, string>> {
  return {
    "Content-Security-Policy": production ? CONTENT_SECURITY_POLICY : "frame-ancestors 'none'",
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    ...(production ? { "Strict-Transport-Security": STRICT_TRANSPORT_SECURITY } : {}),
  };
}

export const SECURITY_HEADERS: Readonly<Record<string, string>> = securityHeaderValues();

export function applySecurityHeaders(res: Pick<Response, "setHeader">, headers: Readonly<Record<string, string>> = SECURITY_HEADERS) {
  for (const [name, value] of Object.entries(headers)) res.setHeader(name, value);
}

export function securityHeaders(headers: Readonly<Record<string, string>> = SECURITY_HEADERS) {
  return (_req: Request, res: Response, next: NextFunction) => {
    applySecurityHeaders(res, headers);
    next();
  };
}
