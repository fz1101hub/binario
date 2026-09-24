import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

// Drizzle applies only migrations listed in meta/_journal.json. The canonical
// lineage preserves the historical WebDev chain 0000-0011 byte-for-byte, then
// adopts Alexa security changes additively in 0012 and 0013.
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

  it("il bridge 0012 adotta revoca code e generazione credenziale senza drop", () => {
    const sql = readFileSync(
      path.join(drizzleDir, "0012_alexa_credential_revocation_adoption.sql"),
      "utf8"
    );
    expect(sql).toContain(
      "ALTER TABLE `alexa_account_links` ADD COLUMN IF NOT EXISTS `credentialVersion`"
    );
    expect(sql).toContain(
      "ALTER TABLE `oauth_authorization_codes` ADD COLUMN IF NOT EXISTS `revokedAt`"
    );
    const executableSql = sql.replace(/^--.*$/gm, "");
    expect(executableSql).not.toMatch(/(?:^|;\s*)\s*(DROP|DELETE|TRUNCATE)\b/im);
  });

  it("0013 aggiunge e inizializza la famiglia dei refresh token senza eliminare dati", () => {
    const sql = readFileSync(
      path.join(drizzleDir, "0013_alexa_refresh_token_family.sql"),
      "utf8"
    );
    expect(sql).toContain("ADD COLUMN IF NOT EXISTS \`familyId\` varchar(64) NULL");
    expect(sql).toContain("CONCAT('legacy-', \`id\`)");
    expect(sql).toContain("MODIFY COLUMN \`familyId\` varchar(64) NOT NULL");
    expect(sql).toContain("oauth_refresh_tokens_family_idx");
    const executableSql = sql.replace(/^--.*$/gm, "");
    expect(executableSql).not.toMatch(/(?:^|;\s*)\s*(DROP|DELETE|TRUNCATE)\b/im);
  });

  it("mantiene i timestamp storici che Drizzle usa per riconoscere il database WebDev", () => {
    expect(journal.entries).toHaveLength(14);
    expect(journal.entries[2]).toMatchObject({
      tag: "0002_sweet_rachel_grey",
    });
    const journalWithTimes = JSON.parse(
      readFileSync(path.join(drizzleDir, "meta", "_journal.json"), "utf8")
    ) as { entries: Array<{ tag: string; when: number }> };
    expect(journalWithTimes.entries[2]?.when).toBe(1786648567821);
    expect(journalWithTimes.entries[11]?.when).toBe(1788377861523);
  });
});
