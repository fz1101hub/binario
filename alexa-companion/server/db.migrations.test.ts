import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

// drizzle-kit applies only the migrations listed in meta/_journal.json: a SQL
// file without a journal entry (the 0002 orphan found by the audit) is never
// applied on a fresh database. This test keeps the three artefacts in sync.
const drizzleDir = path.resolve(import.meta.dirname, "..", "drizzle");
const journal = JSON.parse(
  readFileSync(path.join(drizzleDir, "meta", "_journal.json"), "utf8")
) as {
  entries: Array<{ idx: number; tag: string }>;
};

describe("catena delle migrazioni drizzle", () => {
  const sqlTags = readdirSync(drizzleDir)
    .filter(name => name.endsWith(".sql"))
    .map(name => name.replace(/\.sql$/, ""))
    .sort();
  const journalTags = journal.entries.map(entry => entry.tag).sort();

  it("ogni file SQL ha una voce nel journal e viceversa", () => {
    expect(journalTags).toEqual(sqlTags);
  });

  it("gli indici del journal sono consecutivi e ogni voce ha il proprio snapshot", () => {
    journal.entries.forEach((entry, position) => {
      expect(entry.idx).toBe(position);
      expect(entry.tag.startsWith(String(position).padStart(4, "0"))).toBe(
        true
      );
      expect(
        existsSync(
          path.join(
            drizzleDir,
            "meta",
            `${String(position).padStart(4, "0")}_snapshot.json`
          )
        )
      ).toBe(true);
    });
  });

  it("gli snapshot formano una catena prevId → id senza interruzioni", () => {
    let previousId: string | undefined;
    journal.entries.forEach((_entry, position) => {
      const snapshot = JSON.parse(
        readFileSync(
          path.join(
            drizzleDir,
            "meta",
            `${String(position).padStart(4, "0")}_snapshot.json`
          ),
          "utf8"
        )
      ) as { id: string; prevId: string };
      if (previousId) expect(snapshot.prevId).toBe(previousId);
      previousId = snapshot.id;
    });
  });

  it("la migrazione binario_profiles è applicabile: crea la tabella e il vincolo unico per utente", () => {
    const sql = readFileSync(
      path.join(drizzleDir, "0002_sweet_rachel_grey.sql"),
      "utf8"
    );
    expect(sql).toContain("CREATE TABLE `binario_profiles`");
    expect(sql).toContain("binario_profiles_user_unique");
  });

  it("la migrazione OAuth aggiunge revoca code e generazione credenziale", () => {
    const sql = readFileSync(
      path.join(drizzleDir, "0003_light_tattoo.sql"),
      "utf8"
    );
    expect(sql).toContain(
      "ALTER TABLE `alexa_account_links` ADD `credentialVersion` int DEFAULT 1 NOT NULL"
    );
    expect(sql).toContain(
      "ALTER TABLE `oauth_authorization_codes` ADD `revokedAt` timestamp"
    );
  });
});
