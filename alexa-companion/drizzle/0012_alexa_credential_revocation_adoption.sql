-- Canonical lineage: 0000–0011 keeps the historical WebDev tags and journal
-- timestamps. This additive bridge also upgrades databases that previously ran
-- the shorter Alexa-only 0000–0003 lineage: Drizzle skips older timestamps, so
-- every historical object needed by the canonical schema is adopted here too.
ALTER TABLE `alexa_account_links` ADD COLUMN IF NOT EXISTS `credentialVersion` int NOT NULL DEFAULT 1;
--> statement-breakpoint
ALTER TABLE `oauth_authorization_codes` ADD COLUMN IF NOT EXISTS `revokedAt` timestamp NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `originStationCode` varchar(64) NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `destinationStationCode` varchar(64) NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `travelDate` varchar(10) NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `travelTime` varchar(5) NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `archivedAt` timestamp NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `travelProfile` enum('commuter','occasional','accessibility') NOT NULL DEFAULT 'occasional';
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `plannedReminderMinutes` int NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `personalNote` text NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `accessibilityNote` varchar(500) NULL;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `personalTagsJson` varchar(1000) NOT NULL DEFAULT '[]';
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `sortOrder` int NOT NULL DEFAULT 0;
--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `preDepartureChecklistJson` varchar(2000) NOT NULL DEFAULT '[]';
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS `railway_routes_user_archive_idx` ON `railway_routes` (`userId`,`archivedAt`);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS `railway_routes_user_sort_idx` ON `railway_routes` (`userId`,`sortOrder`);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS `station_preferences` (
  `id` int AUTO_INCREMENT NOT NULL,
  `userId` int NOT NULL,
  `stationName` varchar(120) NOT NULL,
  `stationCode` varchar(64) NOT NULL,
  `isFavorite` boolean NOT NULL DEFAULT false,
  `lastOpenedAt` timestamp NULL,
  `createdAt` timestamp NOT NULL DEFAULT (now()),
  `updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT `station_preferences_id` PRIMARY KEY(`id`),
  CONSTRAINT `station_preferences_user_station_unique` UNIQUE(`userId`,`stationCode`),
  CONSTRAINT `station_preferences_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action,
  INDEX `station_preferences_user_favorite_idx` (`userId`,`isFavorite`),
  INDEX `station_preferences_user_recent_idx` (`userId`,`lastOpenedAt`)
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS `companion_devices` (
  `id` int AUTO_INCREMENT NOT NULL,
  `userId` int NOT NULL,
  `deviceName` varchar(80) NOT NULL,
  `platform` enum('android','ios') NOT NULL,
  `tokenHash` varchar(128) NOT NULL,
  `tokenPrefix` varchar(16) NOT NULL,
  `expiresAt` timestamp NOT NULL,
  `syncEnabled` boolean NOT NULL DEFAULT false,
  `revokedAt` timestamp NULL,
  `lastUsedAt` timestamp NULL,
  `createdAt` timestamp NOT NULL DEFAULT (now()),
  CONSTRAINT `companion_devices_id` PRIMARY KEY(`id`),
  CONSTRAINT `companion_devices_token_hash_unique` UNIQUE(`tokenHash`),
  CONSTRAINT `companion_devices_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action,
  INDEX `companion_devices_user_expiry_idx` (`userId`,`expiresAt`)
);
--> statement-breakpoint
ALTER TABLE `companion_devices` ADD COLUMN IF NOT EXISTS `syncEnabled` boolean NOT NULL DEFAULT false;
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS `companion_pairing_codes` (
  `id` int AUTO_INCREMENT NOT NULL,
  `userId` int NOT NULL,
  `codeHash` varchar(128) NOT NULL,
  `deviceName` varchar(80) NOT NULL,
  `platform` enum('android','ios') NOT NULL,
  `expiresAt` timestamp NOT NULL,
  `consumedAt` timestamp NULL,
  `createdAt` timestamp NOT NULL DEFAULT (now()),
  CONSTRAINT `companion_pairing_codes_id` PRIMARY KEY(`id`),
  CONSTRAINT `companion_pairing_codes_hash_unique` UNIQUE(`codeHash`),
  CONSTRAINT `companion_pairing_codes_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action,
  INDEX `companion_pairing_codes_user_expiry_idx` (`userId`,`expiresAt`)
);
