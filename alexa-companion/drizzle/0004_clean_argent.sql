CREATE TABLE `station_preferences` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`stationName` varchar(120) NOT NULL,
	`stationCode` varchar(64) NOT NULL,
	`isFavorite` boolean NOT NULL DEFAULT false,
	`lastOpenedAt` timestamp,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `station_preferences_id` PRIMARY KEY(`id`),
	CONSTRAINT `station_preferences_user_station_unique` UNIQUE(`userId`,`stationCode`)
);
--> statement-breakpoint
ALTER TABLE `station_preferences` ADD CONSTRAINT `station_preferences_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX `station_preferences_user_favorite_idx` ON `station_preferences` (`userId`,`isFavorite`);--> statement-breakpoint
CREATE INDEX `station_preferences_user_recent_idx` ON `station_preferences` (`userId`,`lastOpenedAt`);