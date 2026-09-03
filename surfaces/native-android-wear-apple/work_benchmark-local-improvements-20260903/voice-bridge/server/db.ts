import { and, desc, eq, gt, isNull } from "drizzle-orm";
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
import { ENV } from './_core/env';
import { normalizePersonalRouteDraft, type PersonalRouteDraft } from "./routes/contract";
import { toRemoteProfileDto, type RemoteProfileDraft } from "./profile/contract";
import { synchronizeRemoteProfile, type RemoteProfileStore } from "./profile/service";

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
      values.role = 'admin';
      updateSet.role = 'admin';
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
    console.error("[Database] Failed to upsert user:", error);
    throw error;
  }
}

export async function getUserByOpenId(openId: string) {
  const db = await getDb();
  if (!db) {
    console.warn("[Database] Cannot get user: database not available");
    return undefined;
  }

  const result = await db.select().from(users).where(eq(users.openId, openId)).limit(1);

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

export async function createPersonalRoute(userId: number, input: PersonalRouteDraft) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");

  const values = { userId, ...normalizePersonalRouteDraft(input) };
  const result = await db.insert(railwayRoutes).values(values);
  const id = Number(result[0].insertId);
  const created = await db.select().from(railwayRoutes).where(and(eq(railwayRoutes.id, id), eq(railwayRoutes.userId, userId))).limit(1);
  return created[0]!;
}

export async function setPersonalRoutePinned(userId: number, routeId: number, isPinned: boolean) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db.update(railwayRoutes).set({ isPinned }).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId)));
}

export async function removePersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db.delete(railwayRoutes).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId)));
}

export async function getRemoteProfile(userId: number) {
  const record = await getStoredRemoteProfile(userId);
  return record ? toRemoteProfileDto(record) : null;
}

async function getStoredRemoteProfile(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio profilo non disponibile");
  const rows = await db.select().from(binarioProfiles).where(eq(binarioProfiles.userId, userId)).limit(1);
  return rows[0] ?? null;
}

export async function upsertRemoteProfile(userId: number, input: RemoteProfileDraft) {
  const db = await getDb();
  if (!db) throw new Error("Archivio profilo non disponibile");
  const store: RemoteProfileStore = {
    load: async id => {
      const rows = await db.select().from(binarioProfiles).where(eq(binarioProfiles.userId, id)).limit(1);
      return rows[0] ?? null;
    },
    insert: async (id, revision, values) => {
      await db.insert(binarioProfiles).values({ userId: id, revision, ...values });
    },
    compareAndSet: async (id, expectedRevision, nextRevision, values) => {
      const update = await db.update(binarioProfiles)
        .set({ ...values, revision: nextRevision })
        .where(and(eq(binarioProfiles.userId, id), eq(binarioProfiles.revision, expectedRevision)));
      return Number(update[0].affectedRows) === 1;
    },
  };
  return synchronizeRemoteProfile(store, userId, input);
}

export async function getAlexaLinkStatus(userId: number) {
  const db = await getDb();
  if (!db) return null;
  const result = await db.select().from(alexaAccountLinks).where(eq(alexaAccountLinks.userId, userId)).limit(1);
  return result[0] ?? null;
}

export async function recordAlexaVerification(userId: number, alexaUserHash: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const now = new Date();
  await db.insert(alexaAccountLinks).values({ userId, alexaUserHash, linkedAt: now, lastVerifiedAt: now }).onDuplicateKeyUpdate({
    set: { alexaUserHash, lastVerifiedAt: now, revokedAt: null },
  });
}

export async function revokeAlexaLink(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  await db.update(alexaAccountLinks).set({ revokedAt: new Date() }).where(eq(alexaAccountLinks.userId, userId));
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

export async function consumeAuthorizationCode(codeHash: string, clientId: string, redirectUri: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const now = new Date();
  const codes = await db
    .select()
    .from(oauthAuthorizationCodes)
    .where(and(
      eq(oauthAuthorizationCodes.codeHash, codeHash),
      eq(oauthAuthorizationCodes.clientId, clientId),
      eq(oauthAuthorizationCodes.redirectUri, redirectUri),
      isNull(oauthAuthorizationCodes.consumedAt),
      gt(oauthAuthorizationCodes.expiresAt, now)
    ))
    .limit(1);
  const code = codes[0];
  if (!code) return null;
  const update = await db.update(oauthAuthorizationCodes).set({ consumedAt: now }).where(and(eq(oauthAuthorizationCodes.id, code.id), isNull(oauthAuthorizationCodes.consumedAt)));
  if (Number(update[0].affectedRows) !== 1) return null;
  return code;
}

export async function createRefreshToken(input: { userId: number; tokenHash: string; clientId: string; expiresAt: Date }) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  await db.insert(oauthRefreshTokens).values(input);
}

export async function getActiveRefreshToken(tokenHash: string, clientId: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const rows = await db
    .select()
    .from(oauthRefreshTokens)
    .where(and(
      eq(oauthRefreshTokens.tokenHash, tokenHash),
      eq(oauthRefreshTokens.clientId, clientId),
      isNull(oauthRefreshTokens.revokedAt),
      gt(oauthRefreshTokens.expiresAt, new Date())
    ))
    .limit(1);
  return rows[0] ?? null;
}

export async function revokeRefreshToken(tokenHash: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  await db.update(oauthRefreshTokens).set({ revokedAt: new Date() }).where(eq(oauthRefreshTokens.tokenHash, tokenHash));
}

export async function revokeAllVoiceCredentialsForUser(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio collegamenti vocali non disponibile");
  const now = new Date();
  await db.update(alexaAccountLinks).set({ revokedAt: now }).where(eq(alexaAccountLinks.userId, userId));
  await db.update(oauthRefreshTokens).set({ revokedAt: now }).where(and(eq(oauthRefreshTokens.userId, userId), isNull(oauthRefreshTokens.revokedAt)));
}
