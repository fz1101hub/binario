import type { CookieOptions, Request } from "express";

export function isSecureRequest(req: Request) {
  // Express derives `protocol` from X-Forwarded-Proto only after the app has
  // accepted the direct peer through its validated `trust proxy` setting. Do
  // not inspect the header directly: a client can supply it on any HTTP hop.
  if (req.protocol === "https") return true;
  return false;
}

export function getSessionCookieOptions(
  req: Request
): Pick<CookieOptions, "domain" | "httpOnly" | "path" | "sameSite" | "secure"> {
  return {
    httpOnly: true,
    path: "/",
    sameSite: "lax",
    secure: isSecureRequest(req),
  };
}
