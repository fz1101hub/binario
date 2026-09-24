ALTER TABLE `railway_routes` ADD `personalTagsJson` varchar(1000) DEFAULT '[]' NOT NULL;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `personalTagsJson` varchar(1000) NOT NULL DEFAULT '[]';--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `sortOrder` int NOT NULL DEFAULT 0;--> statement-breakpoint
CREATE INDEX `railway_routes_user_sort_idx` ON `railway_routes` (`userId`,`sortOrder`);
