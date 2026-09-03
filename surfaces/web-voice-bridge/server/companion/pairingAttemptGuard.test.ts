import { describe, expect, it } from "vitest";
import { PairingAttemptGuard } from "./pairingAttemptGuard";

describe("limite pairing companion", () => {
  it("blocca la stessa chiave dopo il limite e si resetta alla fine della finestra", () => {
    const guard = new PairingAttemptGuard(2, 100);
    expect(guard.allow("client", 0)).toBe(true);
    expect(guard.allow("client", 1)).toBe(true);
    expect(guard.allow("client", 2)).toBe(false);
    expect(guard.allow("client", 100)).toBe(true);
  });
});
