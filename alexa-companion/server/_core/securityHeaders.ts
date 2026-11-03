import type { NextFunction, Request, Response } from "express";

export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  // The dashboard must never be framed: a framing page could otherwise drive it
  // through postMessage or clickjacking. frame-ancestors is the CSP form,
  // X-Frame-Options the legacy one.
  "Content-Security-Policy": "frame-ancestors 'none'",
  "X-Frame-Options": "DENY",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
};

export function applySecurityHeaders(res: Pick<Response, "setHeader">) {
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) res.setHeader(name, value);
}

export function securityHeaders() {
  return (_req: Request, res: Response, next: NextFunction) => {
    applySecurityHeaders(res);
    next();
  };
}
