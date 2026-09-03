ALTER TABLE `railway_routes` ADD `archivedAt` timestamp;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `archivedAt` timestamp NULL;
CREATE INDEX `railway_routes_user_archive_idx` ON `railway_routes` (`userId`,`archivedAt`);
