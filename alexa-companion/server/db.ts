import { and, count, desc, eq, gt, isNull, lt, ne, or, sql } from "drizzle-orm";
import { drizzle } from "drizzle-orm/mysql2";
import { createPool, type Pool } from "mysql2";
import { randomUUID } from "node:crypto";
import {
  alexaAccountLinks,
  binarioProfiles,
  InsertUser,
  oauthAuthorizationCodes,
  oauthRefreshTokens,
  railwayRoutes,
  users,
} from "../drizzle/schema";
import { ENV } from "./_core/env";
import { describeErrorForLog } from "./_core/logRedaction";
import {
  MAX_PERSONAL_ROUTES,
  normalizePersonalRouteDraft,
  RouteLimitError,
  type PersonalRouteDraft,
} from "./routes/contract";
import {
  toRemoteProfileDto,
  type RemoteProfileDraft,
} from "./profile/contract";
import {
  synchronizeRemoteProfile,
  type RemoteProfileStore,
} from "./profile/service";

let _db: ReturnType<typeof drizzle> | null = null;

/** Run on every new connection of the pool before any query of the service. */
export const UTC_SESSION_STATEMENT = "SET time_zone = '+00:00'";

/**
 * The connection pool, with every session in UTC. drizzle writes a `Date` as a UTC wall-clock
 * string and reads one back as UTC, so a session in the server's own zone (`SYSTEM`, Europe/Rome)
 * would store instants shifted by the offset, read `DEFAULT now()` an hour or two off and, in strict
 * mode, refuse every write that falls in the hour skipped when the clocks go forward. The statement
 * is queued on the connection before the query that asked for it.
 */
export function createDatabasePool(url: string): Pool {
  const pool = createPool({ uri: url });
  pool.on("connection", connection => {
    connection.query(UTC_SESSION_STATEMENT, error => {
      if (error) console.warn("[Database] Unable to set the session time zone to UTC:", describeErrorForLog(error));
    });
  });
  return pool;
}

/** Ends the pool, if one was opened (graceful shutdown); a later use opens a new one. */
export async function closeDb(): Promise<void> {
  const db = _db;
  _db = null;
  if (!db) return;
  await new Promise<void>((resolve, reject) => db.$client.end(error => (error ? reject(error) : resolve())));
}

// Lazily create the drizzle instance so local tooling can run without a DB.
export async function getDb() {
  if (!_db && process.env.DATABASE_URL) {
    try {
      _db = drizzle({ client: createDatabasePool(process.env.DATABASE_URL) });
    } catch (error) {
      console.warn("[Database] Failed to connect:", describeErrorForLog(error));
      _db = null;
    }
  }
  return _db;
}

export async function upsertUser(user: InsertUser): Promise<void> {
  if (!user.openId) {
    throw new Error("User openId is required for upsert");
  }

  const db = await getDb();
  if (!db) {
    console.warn("[Database] Cannot upsert user: database not available");
    return;
  }

  try {
    const values: InsertUser = {
      openId: user.openId,
    };
    const updateSet: Record<string, unknown> = {};

    const textFields = ["name", "email", "loginMethod"] as const;
    type TextField = (typeof textFields)[number];

    const assignNullable = (field: TextField) => {
      const value = user[field];
      if (value === undefined) return;
      const normalized = value ?? null;
      values[field] = normalized;
      updateSet[field] = normalized;
    };

    textFields.forEach(assignNullable);

    if (user.lastSignedIn !== undefined) {
      values.lastSignedIn = user.lastSignedIn;
      updateSet.lastSignedIn = user.lastSignedIn;
    }
    if (user.role !== undefined) {
      values.role = user.role;
      updateSet.role = user.role;
    } else if (user.openId === ENV.ownerOpenId) {
      values.role = "admin";
      updateSet.role = "admin";
    }

    if (!values.lastSignedIn) {
      values.lastSignedIn = new Date();
    }

    if (Object.keys(updateSet).length === 0) {
      updateSet.lastSignedIn = new Date();
    }

    await db.insert(users).values(values).onDuplicateKeyUpdate({
      set: updateSet,
    });
  } catch (error) {
    // The failed statement carries openId, name and email as parameters.
    console.error("[Database] Failed to upsert user:", describeErrorForLog(error));
    throw error;
  }
}

export async function getUserByOpenId(openId: string) {
  const db = await getDb();
  if (!db) {
    console.warn("[Database] Cannot get user: database not available");
    return undefined;
  }

  const result = await db
    .select()
    .from(users)
    .where(eq(users.openId, openId))
    .limit(1);

  return result.length > 0 ? result[0] : undefined;
}

export async function touchExistingUserSession(userId: number): Promise<boolean> {
  const db = await getDb();
  if (!db) return false;
  const result = await db
    .update(users)
    .set({ lastSignedIn: new Date() })
    .where(eq(users.id, userId));
  return Number(result[0].affectedRows) === 1;
}

/**
 * The fields of a personal route the dashboard shows. Never `select()` the whole
 * row: a database adopted from the historical WebDev lineage (0000-0011) may
 * still hold columns no code writes any more (personal and accessibility notes,
 * travel dates and times, profile, tags, checklist), and they must not reach the
 * browser.
 */
export const personalRouteColumns = {
  id: railwayRoutes.id,
  origin: railwayRoutes.origin,
  destination: railwayRoutes.destination,
  name: railwayRoutes.name,
  isPinned: railwayRoutes.isPinned,
  createdAt: railwayRoutes.createdAt,
  updatedAt: railwayRoutes.updatedAt,
};

export async function listPersonalRoutes(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  return db
    .select(personalRouteColumns)
    .from(railwayRoutes)
    // Routes archived in the historical WebDev dashboard stay archived: the
    // adopted 0000-0011 lineage keeps `archivedAt`, and showing those rows
    // again would resurrect choices the user deliberately removed.
    .where(and(eq(railwayRoutes.userId, userId), isNull(railwayRoutes.archivedAt)))
    .orderBy(desc(railwayRoutes.isPinned), desc(railwayRoutes.updatedAt))
    .limit(MAX_PERSONAL_ROUTES);
}

/**
 * Saves a route, refusing it with {@link RouteLimitError} when the account already keeps
 * {@link MAX_PERSONAL_ROUTES} (archived legacy rows, which the user cannot see, are not counted).
 * The user's row is locked while counting, so parallel requests cannot pass the limit together.
 */
export async function createPersonalRoute(
  userId: number,
  input: PersonalRouteDraft
) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");

  const values = { userId, ...normalizePersonalRouteDraft(input) };
  const id = await db.transaction(async tx => {
    await tx.select({ id: users.id }).from(users).where(eq(users.id, userId)).limit(1).for("update");
    const [saved] = await tx
      .select({ routes: count() })
      .from(railwayRoutes)
      .where(and(eq(railwayRoutes.userId, userId), isNull(railwayRoutes.archivedAt)));
    if (Number(saved?.routes ?? 0) >= MAX_PERSONAL_ROUTES) throw new RouteLimitError(`Al massimo ${MAX_PERSONAL_ROUTES} tratte`);
    const result = await tx.insert(railwayRoutes).values(values);
    return Number(result[0].insertId);
  });
  const created = await db
    .select(personalRouteColumns)
    .from(railwayRoutes)
    .where(and(eq(railwayRoutes.id, id), eq(railwayRoutes.userId, userId)))
    .limit(1);
  return created[0]!;
}

export async function setPersonalRoutePinned(
  userId: number,
  routeId: number,
  isPinned: boolean
) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db
    .update(railwayRoutes)
    .set({ isPinned })
    .where(
      and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId))
    );
}

export async function removePersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db
    .delete(railwayRoutes)
    .where(
      and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId))
    );
}

export async function getRemoteProfile(userId: number) {
  const record = await getStoredRemoteProfile(userId);
  return record ? toRemoteProfileDto(record) : null;
}

async function getStoredRemoteProfile(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio profilo non disponibile");
  const rows = await db
    .select()
    .from(binarioProfiles)
    .where(eq(binarioProfiles.userId, userId))
    .limit(1);
  return rows[0] ?? null;
}

export async function upsertRemoteProfile(
  userId: number,
  input: RemoteProfileDraft
) {
  const db = await getDb();
  if (!db) throw new Error("Archivio profilo non disponibile");
  const store: RemoteProfileStore = {
    load: async id => {
      const rows = await db
        .select()
        .from(binarioProfiles)
        .where(eq(binarioProfiles.userId, id))
        .limit(1);
      return rows[0] ?? null;
    },
    insert: async (id, revision, values) => {
      await db
        .insert(binarioProfiles)
        .values({ userId: id, revision, ...values });
    },
    compareAndSet: async (id, expectedRevision, nextRevision, values) => {
      const update = await db
        .update(binarioProfiles)
        .set({ ...values, revision: nextRevision })
        .where(
          and(
            eq(binarioProfiles.userId, id),
            eq(binarioProfiles.revision, expectedRevision)
          )
        );
      return Number(update[0].affectedRows) === 1;
    },
  };
  return synchronizeRemoteProfile(store, userId, input);
}

export async function getAlexaLinkStatus(userId: number) {
  const db = await getDb();
  if (!db) return null;
  const result = await db
    .select()
    .from(alexaAccountLinks)
    .where(eq(alexaAccountLinks.userId, userId))
    .limit(1);
  return result[0] ?? null;
}

export async function listPersonalRoutesForAlexa(
  userId: number,
  credentialVersion: number
) {
  const db = await getDb();
  if (!db) throw new Error("Database not available");
  return db
    .select({
      id: railwayRoutes.id,
      userId: railwayRoutes.userId,
      origin: railwayRoutes.origin,
      destination: railwayRoutes.destination,
      name: railwayRoutes.name,
      isPinned: railwayRoutes.isPinned,
      createdAt: railwayRoutes.createdAt,
      updatedAt: railwayRoutes.updatedAt,
    })
    .from(railwayRoutes)
    .innerJoin(
      alexaAccountLinks,
      and(
        eq(alexaAccountLinks.userId, railwayRoutes.userId),
        isNull(alexaAccountLinks.revokedAt),
        eq(alexaAccountLinks.credentialVersion, credentialVersion)
      )
    )
    .where(and(eq(railwayRoutes.userId, userId), isNull(railwayRoutes.archivedAt)))
    .orderBy(desc(railwayRoutes.isPinned), desc(railwayRoutes.updatedAt))
    .limit(MAX_PERSONAL_ROUTES);
}

/**
 * Authorization codes and refresh tokens are deleted this many days after they expired, were
 * revoked or (codes) were consumed: until then a replayed old token is still recognised and revokes
 * its family; afterwards it is simply unknown.
 */
export const OAUTH_RETENTION_DAYS = 30;
const OAUTH_PURGE_INTERVAL_MS = 60 * 60 * 1000;
let lastOAuthPurgeAt = Number.NEGATIVE_INFINITY;

/** The instant before which a finished OAuth artefact is deleted. */
export function oauthRetentionCutoff(now: Date): Date {
  return new Date(now.getTime() - OAUTH_RETENTION_DAYS * 86_400_000);
}

/** Deletes the authorization codes and refresh tokens finished before {@link oauthRetentionCutoff}. */
export async function purgeFinishedOAuthArtifacts(now: Date = new Date()) {
  const db = await getDb();
  if (!db) return;
  const cutoff = oauthRetentionCutoff(now);
  await db
    .delete(oauthAuthorizationCodes)
    .where(
      or(
        lt(oauthAuthorizationCodes.expiresAt, cutoff),
        lt(oauthAuthorizationCodes.consumedAt, cutoff),
        lt(oauthAuthorizationCodes.revokedAt, cutoff)
      )
    );
  await db
    .delete(oauthRefreshTokens)
    .where(or(lt(oauthRefreshTokens.expiresAt, cutoff), lt(oauthRefreshTokens.revokedAt, cutoff)));
}

/**
 * Runs {@link purgeFinishedOAuthArtifacts} in the background at most once an hour per process,
 * after a token exchange or rotation has committed (every active link rotates hourly): a failure is
 * logged by error class and never costs the exchange.
 */
function purgeFinishedOAuthArtifactsSometimes(now: Date) {
  if (now.getTime() - lastOAuthPurgeAt < OAUTH_PURGE_INTERVAL_MS) return;
  lastOAuthPurgeAt = now.getTime();
  void purgeFinishedOAuthArtifacts(now).catch(error =>
    console.warn("[Database] OAuth purge failed:", describeErrorForLog(error))
  );
}

/**
 * Records a verified request of an Alexa account (the HMAC digest of its user id) on the user's
 * link and binds the link to it: the first request after a link records the account, every later
 * one must come from the same account. False, changing nothing, when the link is already bound to
 * another Alexa account; a new link (exchangeAuthorizationCode) clears the binding.
 */
export async function recordAlexaVerification(
  userId: number,
  alexaUserHash: string
): Promise<boolean> {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const now = new Date();
  // The same Alexa account may be re-linked by another Binario user: detach the
  // hash from anyone else first, so the unique index cannot make the upsert
  // resurrect a different user's (possibly revoked) row. A revocation is only
  // lifted by exchangeAuthorizationCode, never by a routine verification.
  return db.transaction(async tx => {
    const [bound] = await tx
      .select({ alexaUserHash: alexaAccountLinks.alexaUserHash })
      .from(alexaAccountLinks)
      .where(eq(alexaAccountLinks.userId, userId))
      .limit(1)
      .for("update");
    if (bound?.alexaUserHash && bound.alexaUserHash !== alexaUserHash) return false;
    await tx
      .update(alexaAccountLinks)
      .set({
        alexaUserHash: null,
        revokedAt: now,
        credentialVersion: sql`${alexaAccountLinks.credentialVersion} + 1`,
      })
      .where(
        and(
          eq(alexaAccountLinks.alexaUserHash, alexaUserHash),
          ne(alexaAccountLinks.userId, userId)
        )
      );
    await tx
      .insert(alexaAccountLinks)
      .values({ userId, alexaUserHash, linkedAt: now, lastVerifiedAt: now })
      .onDuplicateKeyUpdate({
        set: { alexaUserHash, lastVerifiedAt: now },
      });
    return true;
  });
}

export async function createAuthorizationCode(input: {
  userId: number;
  codeHash: string;
  clientId: string;
  redirectUri: string;
  scope: string;
  expiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  await db.insert(oauthAuthorizationCodes).values(input);
}

export async function exchangeAuthorizationCode(input: {
  codeHash: string;
  clientId: string;
  redirectUri: string;
  refreshTokenHash: string;
  refreshTokenExpiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const exchanged = await db.transaction(async tx => {
    const now = new Date();
    const codes = await tx
      .select()
      .from(oauthAuthorizationCodes)
      .where(
        and(
          eq(oauthAuthorizationCodes.codeHash, input.codeHash),
          eq(oauthAuthorizationCodes.clientId, input.clientId),
          eq(oauthAuthorizationCodes.redirectUri, input.redirectUri),
          isNull(oauthAuthorizationCodes.consumedAt),
          isNull(oauthAuthorizationCodes.revokedAt),
          gt(oauthAuthorizationCodes.expiresAt, now)
        )
      )
      .limit(1)
      .for("update");
    const code = codes[0];
    if (!code) return null;
    const update = await tx
      .update(oauthAuthorizationCodes)
      .set({ consumedAt: now })
      .where(
        and(
          eq(oauthAuthorizationCodes.id, code.id),
          isNull(oauthAuthorizationCodes.consumedAt),
          isNull(oauthAuthorizationCodes.revokedAt)
        )
      );
    if (Number(update[0].affectedRows) !== 1) return null;
    await tx
      .insert(alexaAccountLinks)
      .values({ userId: code.userId, linkedAt: now, lastVerifiedAt: now })
      .onDuplicateKeyUpdate({
        set: {
          linkedAt: now,
          lastVerifiedAt: now,
          revokedAt: null,
          // A new link binds to the Alexa account of its first verified request (recordAlexaVerification).
          alexaUserHash: null,
          credentialVersion: sql`${alexaAccountLinks.credentialVersion} + 1`,
        },
      });
    const links = await tx
      .select({ credentialVersion: alexaAccountLinks.credentialVersion })
      .from(alexaAccountLinks)
      .where(eq(alexaAccountLinks.userId, code.userId))
      .limit(1)
      .for("update");
    const link = links[0];
    if (!link) throw new Error("Collegamento Alexa non persistito");
    await tx
      .update(oauthRefreshTokens)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthRefreshTokens.userId, code.userId),
          isNull(oauthRefreshTokens.revokedAt)
        )
      );
    await tx.insert(oauthRefreshTokens).values({
      userId: code.userId,
      clientId: input.clientId,
      tokenHash: input.refreshTokenHash,
      familyId: randomUUID(),
      expiresAt: input.refreshTokenExpiresAt,
    });
    return { ...code, credentialVersion: link.credentialVersion };
  });
  // After the transaction, never inside or beside it: the purge locks the rows it scans.
  purgeFinishedOAuthArtifactsSometimes(new Date());
  return exchanged;
}

/**
 * A rotated refresh token presented again shortly after its rotation is a retry, not a theft:
 * Amazon resends the same request when the response is lost, and two refreshes can race.
 */
export const REFRESH_TOKEN_REUSE_GRACE_MS = 60_000;

/**
 * True when a revoked refresh token may still be exchanged: it was rotated (its successor,
 * created at the instant of the revocation, is still live) no longer ago than the grace window.
 * A token revoked by unlink, re-link or a family revocation has no live successor.
 */
export function isRefreshRetry(input: {
  revokedAt: Date;
  now: Date;
  successorIsLive: boolean;
}) {
  return (
    input.successorIsLive &&
    input.now.getTime() - input.revokedAt.getTime() <= REFRESH_TOKEN_REUSE_GRACE_MS
  );
}

/**
 * Rotates a refresh token inside its family. Reuse of an already rotated token escalates to
 * revoking the whole family (and the voice credential generation) only outside the grace
 * window; inside it, the retry gets a fresh token of the same family and nothing is revoked.
 * Each successor is stored with `createdAt` equal to its predecessor's `revokedAt`, which
 * identifies the "immediately previous" token without a schema change. Siblings handed out
 * to retries that the client did not keep expire at the next regular rotation.
 */
export async function rotateRefreshToken(input: {
  currentTokenHash: string;
  clientId: string;
  nextTokenHash: string;
  nextTokenExpiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const candidate = await db
    .select()
    .from(oauthRefreshTokens)
    .where(
      and(
        eq(oauthRefreshTokens.tokenHash, input.currentTokenHash),
        eq(oauthRefreshTokens.clientId, input.clientId)
      )
    )
    .limit(1);
  const initial = candidate[0];
  if (!initial) return null;
  const rotated = await db.transaction(async tx => {
    const now = new Date();
    const links = await tx
      .select()
      .from(alexaAccountLinks)
      .where(
        and(
          eq(alexaAccountLinks.userId, initial.userId),
          isNull(alexaAccountLinks.revokedAt)
        )
      )
      .limit(1)
      .for("update");
    if (!links[0]) return null;
    const rows = await tx
      .select()
      .from(oauthRefreshTokens)
      .where(
        and(
          eq(oauthRefreshTokens.id, initial.id),
          eq(oauthRefreshTokens.tokenHash, input.currentTokenHash),
          eq(oauthRefreshTokens.clientId, input.clientId)
        )
      )
      .limit(1)
      .for("update");
    const current = rows[0];
    if (!current) return null;
    if (current.revokedAt) {
      const successors = await tx
        .select({ id: oauthRefreshTokens.id })
        .from(oauthRefreshTokens)
        .where(
          and(
            eq(oauthRefreshTokens.familyId, current.familyId),
            eq(oauthRefreshTokens.createdAt, current.revokedAt),
            isNull(oauthRefreshTokens.revokedAt),
            gt(oauthRefreshTokens.expiresAt, now)
          )
        )
        .limit(1)
        .for("update");
      if (
        isRefreshRetry({
          revokedAt: current.revokedAt,
          now,
          successorIsLive: Boolean(successors[0]),
        })
      ) {
        await tx.insert(oauthRefreshTokens).values({
          userId: current.userId,
          clientId: input.clientId,
          tokenHash: input.nextTokenHash,
          familyId: current.familyId,
          expiresAt: input.nextTokenExpiresAt,
          createdAt: now,
        });
        return {
          ...current,
          credentialVersion: links[0].credentialVersion,
        };
      }
      const familyRevocation = await tx
        .update(oauthRefreshTokens)
        .set({ revokedAt: now })
        .where(
          and(
            eq(oauthRefreshTokens.familyId, current.familyId),
            isNull(oauthRefreshTokens.revokedAt)
          )
        );
      if (Number(familyRevocation[0].affectedRows) > 0) {
        // The only live family is gone (a new link revokes the older ones), so the link is
        // dead until the user links again: say so, as unlinking does, for the dashboard.
        await tx
          .update(alexaAccountLinks)
          .set({ revokedAt: now, credentialVersion: sql`${alexaAccountLinks.credentialVersion} + 1` })
          .where(eq(alexaAccountLinks.id, links[0].id));
      }
      return null;
    }
    if (current.expiresAt <= now) return null;
    const update = await tx
      .update(oauthRefreshTokens)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthRefreshTokens.id, current.id),
          isNull(oauthRefreshTokens.revokedAt)
        )
      );
    if (Number(update[0].affectedRows) !== 1) return null;
    // Tokens a grace-window retry issued and the client never used: the client has just
    // shown which token it kept, so the others stop working (without a family revocation).
    await tx
      .update(oauthRefreshTokens)
      .set({ expiresAt: now })
      .where(
        and(
          eq(oauthRefreshTokens.familyId, current.familyId),
          ne(oauthRefreshTokens.id, current.id),
          isNull(oauthRefreshTokens.revokedAt),
          gt(oauthRefreshTokens.expiresAt, now)
        )
      );
    await tx.insert(oauthRefreshTokens).values({
      userId: current.userId,
      clientId: input.clientId,
      tokenHash: input.nextTokenHash,
      familyId: current.familyId,
      expiresAt: input.nextTokenExpiresAt,
      // Equal to the predecessor's revokedAt: marks this row as its direct successor.
      createdAt: now,
    });
    return {
      ...current,
      credentialVersion: links[0].credentialVersion,
    };
  });
  purgeFinishedOAuthArtifactsSometimes(new Date());
  return rotated;
}

export async function revokeAllVoiceCredentialsForUser(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  await db.transaction(async tx => {
    const now = new Date();
    await tx
      .update(oauthAuthorizationCodes)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthAuthorizationCodes.userId, userId),
          isNull(oauthAuthorizationCodes.consumedAt),
          isNull(oauthAuthorizationCodes.revokedAt)
        )
      );
    await tx
      .update(alexaAccountLinks)
      .set({
        revokedAt: now,
        credentialVersion: sql`${alexaAccountLinks.credentialVersion} + 1`,
      })
      .where(eq(alexaAccountLinks.userId, userId));
    await tx
      .update(oauthRefreshTokens)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthRefreshTokens.userId, userId),
          isNull(oauthRefreshTokens.revokedAt)
        )
      );
  });
}

/**
 * Permanently removes the dashboard account and every child record protected by
 * foreign-key cascades. Credentials are revoked first in the same transaction
 * so concurrent readers cannot observe a deleted account as still authorized.
 */
export async function deleteUserAccount(userId: number): Promise<boolean> {
  const db = await getDb();
  if (!db) throw new Error("Archivio account non disponibile");
  return db.transaction(async tx => {
    const existing = await tx
      .select({ id: users.id })
      .from(users)
      .where(eq(users.id, userId))
      .limit(1)
      .for("update");
    if (!existing[0]) return false;

    const now = new Date();
    await tx
      .update(oauthAuthorizationCodes)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthAuthorizationCodes.userId, userId),
          isNull(oauthAuthorizationCodes.revokedAt)
        )
      );
    await tx
      .update(alexaAccountLinks)
      .set({
        revokedAt: now,
        credentialVersion: sql`${alexaAccountLinks.credentialVersion} + 1`,
      })
      .where(eq(alexaAccountLinks.userId, userId));
    await tx
      .update(oauthRefreshTokens)
      .set({ revokedAt: now })
      .where(
        and(
          eq(oauthRefreshTokens.userId, userId),
          isNull(oauthRefreshTokens.revokedAt)
        )
      );

    const deleted = await tx.delete(users).where(eq(users.id, userId));
    return Number(deleted[0].affectedRows) === 1;
  });
}
