import { and, asc, desc, eq, gt, inArray, isNotNull, isNull } from "drizzle-orm";
import { drizzle } from "drizzle-orm/mysql2";
import {
  alexaAccountLinks,
  binarioProfiles,
  companionDevices,
  companionPairingCodes,
  InsertUser,
  oauthAuthorizationCodes,
  oauthRefreshTokens,
  railwayRoutes,
  stationPreferences,
  users,
} from "../drizzle/schema";
import { ENV } from './_core/env';
import { normalizePersonalRouteDraft, parsePersonalChecklistJson, parsePersonalTagsJson, personalRouteSyncFingerprint, type PersonalRouteDraft } from "./routes/contract";
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

export async function listPersonalRoutes(userId: number, options: { includeArchived?: boolean } = {}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const conditions = [eq(railwayRoutes.userId, userId)];
  if (!options.includeArchived) conditions.push(isNull(railwayRoutes.archivedAt));
  return db
    .select()
    .from(railwayRoutes)
    .where(and(...conditions))
    .orderBy(desc(railwayRoutes.isPinned), asc(railwayRoutes.sortOrder), desc(railwayRoutes.updatedAt));
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

export async function updatePersonalRoute(userId: number, routeId: number, input: PersonalRouteDraft) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const update = await db.update(railwayRoutes).set(normalizePersonalRouteDraft(input)).where(and(
    eq(railwayRoutes.id, routeId),
    eq(railwayRoutes.userId, userId),
    isNull(railwayRoutes.archivedAt),
  ));
  if (Number(update[0].affectedRows) !== 1) throw new Error("Tratta non disponibile");
  const route = await db.select().from(railwayRoutes).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId))).limit(1);
  return route[0]!;
}

export async function getPersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const route = await db.select().from(railwayRoutes).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId))).limit(1);
  return route[0] ?? null;
}

export async function duplicatePersonalRoute(userId: number, routeId: number) {
  const source = await getPersonalRoute(userId, routeId);
  if (!source || source.archivedAt) throw new Error("Tratta non disponibile");
  const suffix = " · copia";
  const name = `${source.name.slice(0, 120 - suffix.length)}${suffix}`;
  return createPersonalRoute(userId, {
    origin: source.origin,
    originStationCode: source.originStationCode,
    destination: source.destination,
    destinationStationCode: source.destinationStationCode,
    name,
    isPinned: false,
    travelDate: source.travelDate,
    travelTime: source.travelTime,
    travelProfile: source.travelProfile,
    plannedReminderMinutes: source.plannedReminderMinutes,
    personalNote: source.personalNote,
    accessibilityNote: source.accessibilityNote,
    personalTags: parsePersonalTagsJson(source.personalTagsJson),
    preDepartureChecklist: parsePersonalChecklistJson(source.preDepartureChecklistJson),
  });
}

export async function invertPersonalRoute(userId: number, routeId: number) {
  const source = await getPersonalRoute(userId, routeId);
  if (!source || source.archivedAt) throw new Error("Tratta non disponibile");
  return updatePersonalRoute(userId, routeId, {
    origin: source.destination,
    originStationCode: source.destinationStationCode,
    destination: source.origin,
    destinationStationCode: source.originStationCode,
    name: source.name,
    isPinned: source.isPinned,
    travelDate: source.travelDate,
    travelTime: source.travelTime,
    travelProfile: source.travelProfile,
    plannedReminderMinutes: source.plannedReminderMinutes,
    personalNote: source.personalNote,
    accessibilityNote: source.accessibilityNote,
    personalTags: parsePersonalTagsJson(source.personalTagsJson),
    preDepartureChecklist: parsePersonalChecklistJson(source.preDepartureChecklistJson),
  });
}

export async function importPersonalRoutes(userId: number, inputs: PersonalRouteDraft[]) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const existing = await db.select({ origin: railwayRoutes.origin, originStationCode: railwayRoutes.originStationCode, destination: railwayRoutes.destination, destinationStationCode: railwayRoutes.destinationStationCode, travelDate: railwayRoutes.travelDate, travelTime: railwayRoutes.travelTime }).from(railwayRoutes).where(eq(railwayRoutes.userId, userId));
  const knownFingerprints = new Set(existing.map(personalRouteSyncFingerprint));
  const values = inputs.map(input => normalizePersonalRouteDraft(input)).filter(value => {
    const fingerprint = personalRouteSyncFingerprint(value);
    if (knownFingerprints.has(fingerprint)) return false;
    knownFingerprints.add(fingerprint);
    return true;
  }).map(value => ({ userId, ...value }));
  if (values.length === 0) return 0;
  await db.insert(railwayRoutes).values(values);
  return values.length;
}

export async function setPersonalRoutePinned(userId: number, routeId: number, isPinned: boolean) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db.update(railwayRoutes).set({ isPinned }).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId)));
}

export async function reorderPersonalRoutes(userId: number, routeIds: number[]) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const activeRoutes = await db.select({ id: railwayRoutes.id }).from(railwayRoutes).where(and(eq(railwayRoutes.userId, userId), isNull(railwayRoutes.archivedAt)));
  if (activeRoutes.length !== routeIds.length || new Set(routeIds).size !== routeIds.length || activeRoutes.some(route => !routeIds.includes(route.id))) throw new Error("Ordinamento non valido");
  await db.transaction(async tx => {
    for (let index = 0; index < routeIds.length; index += 1) {
      await tx.update(railwayRoutes).set({ sortOrder: index }).where(and(eq(railwayRoutes.id, routeIds[index]!), eq(railwayRoutes.userId, userId), isNull(railwayRoutes.archivedAt)));
    }
  });
}

export async function removePersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db.delete(railwayRoutes).where(and(eq(railwayRoutes.id, routeId), eq(railwayRoutes.userId, userId)));
}

export async function archivePersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const update = await db.update(railwayRoutes).set({ archivedAt: new Date() }).where(and(
    eq(railwayRoutes.id, routeId),
    eq(railwayRoutes.userId, userId),
    isNull(railwayRoutes.archivedAt),
  ));
  return Number(update[0].affectedRows) === 1;
}

export async function restorePersonalRoute(userId: number, routeId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const update = await db.update(railwayRoutes).set({ archivedAt: null }).where(and(
    eq(railwayRoutes.id, routeId),
    eq(railwayRoutes.userId, userId),
    isNotNull(railwayRoutes.archivedAt),
  ));
  return Number(update[0].affectedRows) === 1;
}

type StationPreferenceInput = { stationName: string; stationCode: string };
export const MAX_RECENT_STATIONS = 6;

export function recentStationIdsToPrune<T extends { id: number }>(recentStations: T[]) {
  return recentStations.slice(MAX_RECENT_STATIONS).map(station => station.id);
}

function normalizeStationPreference(input: StationPreferenceInput) {
  const stationName = input.stationName.trim().replace(/\s+/g, " ");
  const stationCode = input.stationCode.trim();
  if (stationName.length < 2 || stationName.length > 120 || !stationCode || stationCode.length > 64) throw new Error("Stazione non valida");
  return { stationName, stationCode };
}

export async function listStationPreferences(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio stazioni non disponibile");
  return db.select().from(stationPreferences).where(eq(stationPreferences.userId, userId)).orderBy(desc(stationPreferences.isFavorite), desc(stationPreferences.lastOpenedAt)).limit(20);
}

export async function recordStationOpened(userId: number, input: StationPreferenceInput) {
  const db = await getDb();
  if (!db) throw new Error("Archivio stazioni non disponibile");
  const station = normalizeStationPreference(input);
  const now = new Date();
  await db.insert(stationPreferences).values({ userId, ...station, lastOpenedAt: now }).onDuplicateKeyUpdate({ set: { stationName: station.stationName, lastOpenedAt: now } });
  const recentStations = await db
    .select({ id: stationPreferences.id })
    .from(stationPreferences)
    .where(and(eq(stationPreferences.userId, userId), eq(stationPreferences.isFavorite, false)))
    .orderBy(desc(stationPreferences.lastOpenedAt));
  const staleIds = recentStationIdsToPrune(recentStations);
  if (staleIds.length) await db.delete(stationPreferences).where(and(eq(stationPreferences.userId, userId), inArray(stationPreferences.id, staleIds)));
}

export async function setStationFavorite(userId: number, input: StationPreferenceInput & { isFavorite: boolean }) {
  const db = await getDb();
  if (!db) throw new Error("Archivio stazioni non disponibile");
  const station = normalizeStationPreference(input);
  await db.insert(stationPreferences).values({ userId, ...station, isFavorite: input.isFavorite }).onDuplicateKeyUpdate({ set: { stationName: station.stationName, isFavorite: input.isFavorite } });
}

export async function clearRecentStations(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio stazioni non disponibile");
  await db.delete(stationPreferences).where(and(eq(stationPreferences.userId, userId), eq(stationPreferences.isFavorite, false)));
  await db.update(stationPreferences).set({ lastOpenedAt: null }).where(and(eq(stationPreferences.userId, userId), eq(stationPreferences.isFavorite, true)));
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

export async function createCompanionPairingCode(input: {
  userId: number;
  codeHash: string;
  deviceName: string;
  platform: "android" | "ios";
  expiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  await db.insert(companionPairingCodes).values(input);
}

export async function consumeCompanionPairingCode(codeHash: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const now = new Date();
  const rows = await db.select().from(companionPairingCodes).where(and(
    eq(companionPairingCodes.codeHash, codeHash),
    isNull(companionPairingCodes.consumedAt),
    gt(companionPairingCodes.expiresAt, now),
  )).limit(1);
  const pairing = rows[0];
  if (!pairing) return null;
  const update = await db.update(companionPairingCodes).set({ consumedAt: now }).where(and(
    eq(companionPairingCodes.id, pairing.id),
    isNull(companionPairingCodes.consumedAt),
  ));
  return Number(update[0].affectedRows) === 1 ? pairing : null;
}

export async function createCompanionDevice(input: {
  userId: number;
  deviceName: string;
  platform: "android" | "ios";
  tokenHash: string;
  tokenPrefix: string;
  expiresAt: Date;
}) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const result = await db.insert(companionDevices).values(input);
  return Number(result[0].insertId);
}

export async function getActiveCompanionDevice(tokenHash: string) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const rows = await db.select().from(companionDevices).where(and(
    eq(companionDevices.tokenHash, tokenHash),
    isNull(companionDevices.revokedAt),
    gt(companionDevices.expiresAt, new Date()),
  )).limit(1);
  return rows[0] ?? null;
}

export async function touchCompanionDevice(deviceId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  await db.update(companionDevices).set({ lastUsedAt: new Date() }).where(eq(companionDevices.id, deviceId));
}

export async function setCompanionDeviceSync(userId: number, deviceId: number, syncEnabled: boolean) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const update = await db.update(companionDevices).set({ syncEnabled }).where(and(
    eq(companionDevices.id, deviceId),
    eq(companionDevices.userId, userId),
    isNull(companionDevices.revokedAt),
    gt(companionDevices.expiresAt, new Date()),
  ));
  return Number(update[0].affectedRows) === 1;
}

export async function listCompanionDevices(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  return db.select({
    id: companionDevices.id,
    deviceName: companionDevices.deviceName,
    platform: companionDevices.platform,
    tokenPrefix: companionDevices.tokenPrefix,
    expiresAt: companionDevices.expiresAt,
    syncEnabled: companionDevices.syncEnabled,
    revokedAt: companionDevices.revokedAt,
    lastUsedAt: companionDevices.lastUsedAt,
    createdAt: companionDevices.createdAt,
  }).from(companionDevices).where(eq(companionDevices.userId, userId)).orderBy(desc(companionDevices.createdAt));
}

export async function revokeCompanionDevice(userId: number, deviceId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const update = await db.update(companionDevices).set({ revokedAt: new Date() }).where(and(
    eq(companionDevices.id, deviceId),
    eq(companionDevices.userId, userId),
    isNull(companionDevices.revokedAt),
  ));
  return Number(update[0].affectedRows) === 1;
}

export async function revokeAllCompanionDevices(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio companion non disponibile");
  const update = await db.update(companionDevices).set({ revokedAt: new Date() }).where(and(eq(companionDevices.userId, userId), isNull(companionDevices.revokedAt)));
  return Number(update[0].affectedRows);
}

export async function exportPersonalBinarioData(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  const [routes, stations, profiles, devices, alexaLinks] = await Promise.all([
    db.select().from(railwayRoutes).where(eq(railwayRoutes.userId, userId)),
    db.select().from(stationPreferences).where(eq(stationPreferences.userId, userId)),
    db.select().from(binarioProfiles).where(eq(binarioProfiles.userId, userId)).limit(1),
    db.select({ deviceName: companionDevices.deviceName, platform: companionDevices.platform, expiresAt: companionDevices.expiresAt, revokedAt: companionDevices.revokedAt, lastUsedAt: companionDevices.lastUsedAt, createdAt: companionDevices.createdAt }).from(companionDevices).where(eq(companionDevices.userId, userId)),
    db.select({ linkedAt: alexaAccountLinks.linkedAt, lastVerifiedAt: alexaAccountLinks.lastVerifiedAt, revokedAt: alexaAccountLinks.revokedAt }).from(alexaAccountLinks).where(eq(alexaAccountLinks.userId, userId)),
  ]);
  return {
    schemaVersion: 1,
    exportedAt: new Date().toISOString(),
    notice: "Esportazione dei dati personali Binario. Non contiene password, token, codici di pairing, audio, coordinate, ticket o dati ferroviari realtime.",
    routes,
    stationPreferences: stations,
    profile: profiles[0] ? toRemoteProfileDto(profiles[0]) : null,
    companionDevices: devices,
    alexaLinks,
  };
}

export async function deletePersonalBinarioData(userId: number) {
  const db = await getDb();
  if (!db) throw new Error("Archivio personale non disponibile");
  await db.transaction(async tx => {
    await tx.delete(companionPairingCodes).where(eq(companionPairingCodes.userId, userId));
    await tx.delete(companionDevices).where(eq(companionDevices.userId, userId));
    await tx.delete(oauthAuthorizationCodes).where(eq(oauthAuthorizationCodes.userId, userId));
    await tx.delete(oauthRefreshTokens).where(eq(oauthRefreshTokens.userId, userId));
    await tx.delete(alexaAccountLinks).where(eq(alexaAccountLinks.userId, userId));
    await tx.delete(binarioProfiles).where(eq(binarioProfiles.userId, userId));
    await tx.delete(stationPreferences).where(eq(stationPreferences.userId, userId));
    await tx.delete(railwayRoutes).where(eq(railwayRoutes.userId, userId));
  });
}
