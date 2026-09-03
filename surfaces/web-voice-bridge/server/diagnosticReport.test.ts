import { describe, expect, it } from "vitest";
import { createWebDiagnosticReport } from "../shared/diagnosticReport";

describe("diagnostica web minimizzata", () => {
  it("non serializza dati personali o ferroviari", () => {
    const report = createWebDiagnosticReport(new Date("2026-09-02T00:00:00.000Z"));
    expect(report).toMatchObject({ product: "Binario", surface: "web", providerState: "unconfigured", realtimeDeliveryEnabled: false });
    expect(report.excludedFields).toContain("token");
    expect(JSON.stringify(report)).not.toMatch(/mario\.rossi@example\.it|Roma Termini|51\.5074|eyJ[a-zA-Z0-9_-]+/);
  });
});
