import { describe, expect, it } from "vitest";

describe("identità pubblica dell’app", () => {
  it("usa Binario come titolo pubblico", () => {
    expect(process.env.VITE_APP_TITLE).toBe("Binario");
  });
});
