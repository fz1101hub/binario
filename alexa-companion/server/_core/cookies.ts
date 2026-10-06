import type { CookieOptions, Request } from "express";

export function isSecureRequest(req: Request) {
  // Express derives `protocol` from X-Forwarded-Proto only after the app has
  // accepted the direct peer through its validated `trust proxy` setting. Do
  // not inspect the header directly: a client can supply it on any HTTP hop.
  if (req.protocol === "https") return true;
  return false;
}

export function getSessionCookieOptions(
  _req: Request
): Pick<CookieOptions, "domain" | "httpOnly" | "path" | "sameSite" | "secure"> {
  // COOKIE_NAME carries the `__Host-` prefix, which browsers accept only with
  // Secure, Path=/ and no Domain. Secure is therefore unconditional: TLS ends
  // at the proxy in production, and browsers treat http://localhost as secure
  // during development.
  return {
    httpOnly: true,
    path: "/",
    sameSite: "lax",
    secure: true,
  };
}
