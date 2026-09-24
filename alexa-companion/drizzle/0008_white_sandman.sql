ALTER TABLE `railway_routes` ADD `travelProfile` enum('commuter','occasional','accessibility') DEFAULT 'occasional' NOT NULL;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `travelProfile` enum('commuter','occasional','accessibility') NOT NULL DEFAULT 'occasional';--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `plannedReminderMinutes` int;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `personalNote` text;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD COLUMN IF NOT EXISTS `accessibilityNote` varchar(500);
