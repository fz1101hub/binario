import { describe, expect, it } from "vitest";
import { databaseEngineProblem } from "./dbEngine";

describe("motore database per le migrazioni", () => {
  it.each(["11.4.2-MariaDB-ubu2404", "10.11.14-MariaDB-0ubuntu0.24.04.1", "8.0.11-TiDB-v7.5.1"])("accetta %s", version => {
    expect(databaseEngineProblem(version)).toBeNull();
  });

  it.each(["8.0.36", "8.4.0", "8.0.32-google"])("rifiuta MySQL %s prima di una migrazione che fallirebbe a metà", version => {
    expect(databaseEngineProblem(version)).toMatch(/MariaDB.*TiDB.*MySQL 8/);
  });
});
