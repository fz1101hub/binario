import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import { feedbackPoliteness, shouldAnnounceFeedback } from "@/lib/reminder-accessibility";

describe("urgenza dei messaggi dell’Agenda", () => {
  it("dipende dalla gravità dichiarata, non dalle parole del messaggio tradotto", () => {
    expect(feedbackPoliteness("error")).toBe("assertive");
    expect(feedbackPoliteness("info")).toBe("polite");
    const agenda = readFileSync(path.resolve(import.meta.dirname, "..", "app", "(tabs)", "alerts.tsx"), "utf8");
    expect(agenda).toContain("accessibilityLiveRegion={feedbackPoliteness(feedback.severity)}");
    expect(agenda).not.toMatch(/Politeness\(message\)/);
    // Errors are declared as errors in every language.
    for (const key of ["alerts.readError", "alerts.deleteError", "alerts.reminderUpdateError", "alerts.permissionDenied"]) {
      expect(agenda, key).toContain(`setError(t("${key}"))`);
    }
    for (const key of ["alerts.deleteError", "alerts.routeUpdateError", "alerts.duplicateRoute"]) {
      expect(agenda, key).toContain(`setError(t(archiveRefusalMessageKey(await localRoutesArchiveStatus(), "${key}")))`);
    }
    expect(agenda).toContain('setMessage(t("alerts.reminderScheduled"))');
  });

  it("annuncia esplicitamente solo su iOS, dove non esistono live region", () => {
    expect(shouldAnnounceFeedback("ios")).toBe(true);
    expect(shouldAnnounceFeedback("android")).toBe(false);
    expect(shouldAnnounceFeedback("web")).toBe(false);
  });
});
