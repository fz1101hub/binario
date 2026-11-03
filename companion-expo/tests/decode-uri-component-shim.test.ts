import { createRequire } from "node:module";
import { describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const decode = require("../shims/decode-uri-component") as (value: string) => string;

describe("decode-uri-component replacement (GHSA-qr28-fpjm-wq7f)", () => {
  it("decodes like decodeURIComponent and keeps malformed escapes", () => {
    expect(decode("%41%42")).toBe("AB");
    expect(decode("%E2%82%AC")).toBe("€");
    expect(decode("%")).toBe("%");
    expect(decode("a%20b%ZZ")).toBe("a b%ZZ");
    expect(decode("%C2%A3%E2")).toBe("£%E2");
    expect(decode("%f0%9f%98%80x%E0")).toBe("😀x%E0");
    expect(decode("%ED%A0%80")).toBe("%ED%A0%80");
    expect(decode("%FE%FF")).toBe("��");
  });

  it("rejects non-strings like the original", () => {
    expect(() => decode(1 as unknown as string)).toThrow(TypeError);
  });

  it("stays linear on long runs of invalid escapes", () => {
    const started = Date.now();
    decode("%FF".repeat(300_000));
    decode("%E0%A4".repeat(100_000) + "%");
    expect(Date.now() - started).toBeLessThan(5_000);
  });

  it("is the module query-string resolves", () => {
    const queryStringPath = require.resolve("query-string", { paths: [require.resolve("@react-navigation/core")] });
    const queryRequire = createRequire(queryStringPath);
    expect(queryRequire.resolve("decode-uri-component")).toContain("shims");
    const queryString = queryRequire(queryStringPath) as { parse(value: string): Record<string, string> };
    expect({ ...queryString.parse("a=%E2%82%AC&b=%FF&c=x%20y") }).toEqual({ a: "€", b: "%FF", c: "x y" });
  });
});
