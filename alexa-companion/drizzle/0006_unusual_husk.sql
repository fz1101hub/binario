CREATE TABLE `companion_devices` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`deviceName` varchar(80) NOT NULL,
	`platform` enum('android','ios') NOT NULL,
	`tokenHash` varchar(128) NOT NULL,
	`tokenPrefix` varchar(16) NOT NULL,
	`expiresAt` timestamp NOT NULL,
	`revokedAt` timestamp,
	`lastUsedAt` timestamp,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `companion_devices_id` PRIMARY KEY(`id`),
	CONSTRAINT `companion_devices_token_hash_unique` UNIQUE(`tokenHash`)
);
--> statement-breakpoint
CREATE TABLE `companion_pairing_codes` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`codeHash` varchar(128) NOT NULL,
	`deviceName` varchar(80) NOT NULL,
	`platform` enum('android','ios') NOT NULL,
	`expiresAt` timestamp NOT NULL,
	`consumedAt` timestamp,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `companion_pairing_codes_id` PRIMARY KEY(`id`),
	CONSTRAINT `companion_pairing_codes_hash_unique` UNIQUE(`codeHash`)
);
--> statement-breakpoint
ALTER TABLE `companion_devices` ADD CONSTRAINT `companion_devices_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `companion_pairing_codes` ADD CONSTRAINT `companion_pairing_codes_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX `companion_devices_user_expiry_idx` ON `companion_devices` (`userId`,`expiresAt`);--> statement-breakpoint
CREATE INDEX `companion_pairing_codes_user_expiry_idx` ON `companion_pairing_codes` (`userId`,`expiresAt`);