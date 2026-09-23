import {
  boolean,
  index,
  int,
  mysqlEnum,
  mysqlTable,
  text,
  timestamp,
  uniqueIndex,
  varchar,
} from "drizzle-orm/mysql-core";

/**
 * Core user table backing auth flow.
 * Extend this file with additional tables as your product grows.
 * Columns use camelCase to match both database fields and generated types.
 */
export const users = mysqlTable("users", {
  /**
   * Surrogate primary key. Auto-incremented numeric value managed by the database.
   * Use this for relations between tables.
   */
  id: int("id").autoincrement().primaryKey(),
  /** Manus OAuth identifier (openId) returned from the OAuth callback. Unique per user. */
  openId: varchar("openId", { length: 64 }).notNull().unique(),
  name: text("name"),
  email: varchar("email", { length: 320 }),
  loginMethod: varchar("loginMethod", { length: 64 }),
  role: mysqlEnum("role", ["user", "admin"]).default("user").notNull(),
  createdAt: timestamp("createdAt").defaultNow().notNull(),
  updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  lastSignedIn: timestamp("lastSignedIn").defaultNow().notNull(),
});

export type User = typeof users.$inferSelect;
export type InsertUser = typeof users.$inferInsert;

/**
 * A personal rail relation deliberately stores only the labels needed to answer
 * a user request. It never stores travel history, location, tickets or live
 * vehicle positions.
 */
export const railwayRoutes = mysqlTable(
  "railway_routes",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    origin: varchar("origin", { length: 120 }).notNull(),
    destination: varchar("destination", { length: 120 }).notNull(),
    name: varchar("name", { length: 120 }).notNull(),
    isPinned: boolean("isPinned").notNull().default(false),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  },
  table => ({
    userPinnedIdx: index("railway_routes_user_pinned_idx").on(
      table.userId,
      table.isPinned
    ),
  })
);

/**
 * Small, versioned personal state shared only after an explicit authenticated
 * connection. It deliberately excludes live snapshots, travel history, tokens,
 * location, tickets, audio and any credential material.
 */
export const binarioProfiles = mysqlTable(
  "binario_profiles",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    schemaVersion: int("schemaVersion").notNull().default(1),
    activeRouteId: varchar("activeRouteId", { length: 120 }).notNull(),
    direction: mysqlEnum("direction", ["OUTBOUND", "RETURN"]).notNull(),
    monitoredTrainNumbersJson: text("monitoredTrainNumbersJson").notNull(),
    wearSnapshotSharingEnabled: boolean("wearSnapshotSharingEnabled")
      .notNull()
      .default(true),
    voiceProfileSyncRequested: boolean("voiceProfileSyncRequested")
      .notNull()
      .default(false),
    appleWatchSetupRequested: boolean("appleWatchSetupRequested")
      .notNull()
      .default(false),
    revision: int("revision").notNull().default(1),
    lastMutationId: varchar("lastMutationId", { length: 36 }).notNull(),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
    updatedAt: timestamp("updatedAt").defaultNow().onUpdateNow().notNull(),
  },
  table => ({
    userUnique: uniqueIndex("binario_profiles_user_unique").on(table.userId),
  })
);

/**
 * Link metadata is kept separate from OAuth artefacts. The Alexa user value is
 * represented only by a one-way HMAC digest and is never written to logs.
 */
export const alexaAccountLinks = mysqlTable(
  "alexa_account_links",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    alexaUserHash: varchar("alexaUserHash", { length: 128 }),
    credentialVersion: int("credentialVersion").notNull().default(1),
    linkedAt: timestamp("linkedAt").defaultNow().notNull(),
    lastVerifiedAt: timestamp("lastVerifiedAt"),
    revokedAt: timestamp("revokedAt"),
  },
  table => ({
    userUnique: uniqueIndex("alexa_account_links_user_unique").on(table.userId),
    alexaHashUnique: uniqueIndex("alexa_account_links_alexa_hash_unique").on(
      table.alexaUserHash
    ),
  })
);

/** Authorization codes are single use and persisted only as keyed digests. */
export const oauthAuthorizationCodes = mysqlTable(
  "oauth_authorization_codes",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    codeHash: varchar("codeHash", { length: 128 }).notNull(),
    clientId: varchar("clientId", { length: 255 }).notNull(),
    redirectUri: text("redirectUri").notNull(),
    scope: varchar("scope", { length: 512 }).notNull().default("routes.read"),
    expiresAt: timestamp("expiresAt").notNull(),
    consumedAt: timestamp("consumedAt"),
    revokedAt: timestamp("revokedAt"),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
  },
  table => ({
    codeHashUnique: uniqueIndex("oauth_authorization_codes_hash_unique").on(
      table.codeHash
    ),
    userExpiryIdx: index("oauth_authorization_codes_user_expiry_idx").on(
      table.userId,
      table.expiresAt
    ),
  })
);

/** Refresh tokens are revocable and stored only as keyed digests. */
export const oauthRefreshTokens = mysqlTable(
  "oauth_refresh_tokens",
  {
    id: int("id").autoincrement().primaryKey(),
    userId: int("userId")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    tokenHash: varchar("tokenHash", { length: 128 }).notNull(),
    clientId: varchar("clientId", { length: 255 }).notNull(),
    expiresAt: timestamp("expiresAt").notNull(),
    revokedAt: timestamp("revokedAt"),
    createdAt: timestamp("createdAt").defaultNow().notNull(),
  },
  table => ({
    tokenHashUnique: uniqueIndex("oauth_refresh_tokens_hash_unique").on(
      table.tokenHash
    ),
    userExpiryIdx: index("oauth_refresh_tokens_user_expiry_idx").on(
      table.userId,
      table.expiresAt
    ),
  })
);

export type RailwayRoute = typeof railwayRoutes.$inferSelect;
export type BinarioProfile = typeof binarioProfiles.$inferSelect;
export type AlexaAccountLink = typeof alexaAccountLinks.$inferSelect;
