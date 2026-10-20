import { describe, expect, it } from "vitest";
import { applySecurityHeaders, SECURITY_HEADERS, securityHeaders } from "./securityHeaders";

function fakeResponse() {
  const headers = new Map<string, string>();
  return { headers, setHeader: (name: string, value: string) => { headers.set(name, value); } };
}

describe("security headers", () => {
  it("forbids framing through both CSP and X-Frame-Options", () => {
    const res = fakeResponse();
    applySecurityHeaders(res);
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
});
