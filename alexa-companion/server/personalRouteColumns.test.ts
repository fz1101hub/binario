import { drizzle } from "drizzle-orm/mysql2";
import { describe, expect, it } from "vitest";
import { railwayRoutes } from "../drizzle/schema";
import { personalRouteColumns } from "./db";

describe("colonne di una tratta personale inviate alla dashboard", () => {
  it("chiede al database solo i campi mostrati, mai le colonne storiche WebDev", () => {
    const { sql } = drizzle.mock().select(personalRouteColumns).from(railwayRoutes).toSQL();
    const columns = [...sql.slice(0, sql.indexOf(" from ")).matchAll(/`(\w+)`/g)].map(match => match[1]).sort();
    expect(columns).toEqual(["createdAt", "destination", "id", "isPinned", "name", "origin", "updatedAt"]);
    for (const legacy of ["personalNote", "accessibilityNote", "travelDate", "travelTime", "travelProfile", "personalTagsJson", "preDepartureChecklistJson", "plannedReminderMinutes", "userId"])
      expect(sql).not.toContain(legacy);
  });
});
