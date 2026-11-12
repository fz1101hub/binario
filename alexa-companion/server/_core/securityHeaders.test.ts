import { describe, expect, it } from "vitest";
import { renderConsentErrorPage, renderConsentPage } from "../voice/consent";
import { PRIVACY_PAGE, TERMS_PAGE } from "../legal/pages";
import { applySecurityHeaders, CONTENT_SECURITY_POLICY, SECURITY_HEADERS, securityHeaders, securityHeaderValues } from "./securityHeaders";

function fakeResponse() {
  const headers = new Map<string, string>();
  return { headers, setHeader: (name: string, value: string) => { headers.set(name, value); } };
}

describe("security headers", () => {
  it("forbids framing through both CSP and X-Frame-Options", () => {
    const res = fakeResponse();
    applySecurityHeaders(res as unknown as Parameters<typeof applySecurityHeaders>[0]);
    expect(res.headers.get("Content-Security-Policy")).toBe("frame-ancestors 'none'");
    expect(res.headers.get("X-Frame-Options")).toBe("DENY");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
  });

  it("applies every header and continues the chain", () => {
    const res = fakeResponse();
    let nextCalled = false;
    securityHeaders()({} as never, res as never, () => { nextCalled = true; });
    expect(nextCalled).toBe(true);
    expect([...res.headers.keys()].sort()).toEqual(Object.keys(SECURITY_HEADERS).sort());
  });

  it("in production adds HSTS and a content policy that allows only this origin's scripts", () => {
    const production = securityHeaderValues(true);
    expect(production["Strict-Transport-Security"]).toBe("max-age=31536000");
    const directives = Object.fromEntries(
      production["Content-Security-Policy"].split("; ").map(directive => [directive.split(" ")[0], directive.split(" ").slice(1).join(" ")])
    );
    expect(directives).toEqual({
      "default-src": "'self'",
      "script-src": "'self'",
      "style-src": "'self' 'unsafe-inline'",
      "img-src": "'self' data:",
      "object-src": "'none'",
      "base-uri": "'none'",
      "frame-ancestors": "'none'",
    });
    // The consent form posts here and ends in a redirect to Amazon: form-action would block it.
    expect(CONTENT_SECURITY_POLICY).not.toContain("form-action");
    expect(production["X-Frame-Options"]).toBe("DENY");
  });

  it("outside production keeps only the framing ban: Vite injects inline scripts and serves plain HTTP", () => {
    const development = securityHeaderValues(false);
    expect(development["Content-Security-Policy"]).toBe("frame-ancestors 'none'");
    expect(development["Strict-Transport-Security"]).toBeUndefined();
  });

  it("serves consent, error and legal pages that the production policy lets render", () => {
    const pages = [
      renderConsentPage({
        locale: "it-IT",
        userName: "Mario",
        clientId: "alexa-client",
        redirectUri: "https://layla.amazon.com/api/skill/link/M1TEST",
        state: "opaque-state",
        scope: "routes.read",
        token: "a".repeat(64),
      }),
      renderConsentErrorPage("ar-SA", "invalidRequest"),
      PRIVACY_PAGE,
      TERMS_PAGE,
    ];
    for (const html of pages) {
      // No script at all, no inline handler, no external stylesheet, image or font.
      expect(html).not.toMatch(/<script|\son[a-z]+=|<link|<img|<iframe|<object|<base|url\(/i);
      // Every action and link stays on this origin.
      for (const [, target] of html.matchAll(/(?:action|href|src)="([^"]*)"/g)) expect(target).toMatch(/^\/(?!\/)/);
    }
    expect(pages[0]).toContain('<form method="post" action="/api/alexa/oauth/authorize">');
  });
});
