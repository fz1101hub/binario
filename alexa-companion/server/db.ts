import { and, desc, eq, gt, isNull, ne, sql } from "drizzle-orm";
import { randomUUID } from "node:crypto";
import { drizzle } from "drizzle-orm/mysql2";
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
import {
  normalizePersonalRouteDraft,
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

// Lazily create the drizzle instance so local tooling can run without a DB.
export async function getDb() {
  if (!_db && process.env.DATABASE_URL) {
    try {
      _db = drizzle(process.env.DATABASE_URL);
    } catch (error) {
      console.warn("[Database] Failed to connect:", error);
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
    console.error(
      "[Database] Failed to upsert user:",
      error instanceof Error ? error.message : String(error)
    );
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

export async function listPersonalRoutes(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  return db
    .select()
    .from(railwayRoutes)
    .where(eq(railwayRoutes.userId, userId))
    .orderBy(desc(railwayRoutes.isPinned), desc(railwayRoutes.updatedAt));
}

export async function createPersonalRoute(
  userId: number,
  input: PersonalRouteDraft
) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");

  const values = { userId, ...normalizePersonalRouteDraft(input) };
  const result = await db.insert(railwayRoutes).values(values);
  const id = Number(result[0].insertId);
  const created = await db
    .select()
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
    .where(eq(railwayRoutes.userId, userId))
    .orderBy(desc(railwayRoutes.isPinned), desc(railwayRoutes.updatedAt));
}

export async function recordAlexaVerification(
  userId: number,
  alexaUserHash: string
) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const now = new Date();
  // The same Alexa account may be re-linked by another Binario user: detach the
  // hash from anyone else first, so the unique index cannot make the upsert
  // resurrect a different user's (possibly revoked) row. A revocation is only
  // lifted by exchangeAuthorizationCode, never by a routine verification.
  await db.transaction(async tx => {
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
  return db.transaction(async tx => {
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
}

export async function rotateRefreshToken(input: {
  currentTokenHash: string;
  clientId: string;
  nextTokenHash: string;
  nextTokenExpiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  return db.transaction(async tx => {
    const now = new Date();
    // Look up even revoked tokens: presenting a consumed member of a rotation
    // family is reuse evidence and must revoke every still-active descendant.
    const rows = await tx
      .select()
      .from(oauthRefreshTokens)
      .where(
        and(
          eq(oauthRefreshTokens.tokenHash, input.currentTokenHash),
          eq(oauthRefreshTokens.clientId, input.clientId)
        )
      )
      .limit(1)
      .for("update");
    const current = rows[0];
    if (!current || current.expiresAt <= now) return null;

    const links = await tx
      .select()
      .from(alexaAccountLinks)
      .where(
        and(
          eq(alexaAccountLinks.userId, current.userId),
          isNull(alexaAccountLinks.revokedAt)
        )
      )
      .limit(1)
      .for("update");
    if (!links[0]) return null;

    if (current.revokedAt != null) {
      await tx
        .update(oauthRefreshTokens)
        .set({ revokedAt: now })
        .where(
          and(
            eq(oauthRefreshTokens.familyId, current.familyId),
            isNull(oauthRefreshTokens.revokedAt)
          )
        );
      return null;
    }

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
    await tx.insert(oauthRefreshTokens).values({
      userId: current.userId,
      clientId: input.clientId,
      tokenHash: input.nextTokenHash,
      familyId: current.familyId,
      expiresAt: input.nextTokenExpiresAt,
    });
    return {
      ...current,
      credentialVersion: links[0].credentialVersion,
    };
  });
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
