CREATE TABLE `alexa_account_links` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`alexaUserHash` varchar(128),
	`linkedAt` timestamp NOT NULL DEFAULT (now()),
	`lastVerifiedAt` timestamp,
	`revokedAt` timestamp,
	CONSTRAINT `alexa_account_links_id` PRIMARY KEY(`id`),
	CONSTRAINT `alexa_account_links_user_unique` UNIQUE(`userId`),
	CONSTRAINT `alexa_account_links_alexa_hash_unique` UNIQUE(`alexaUserHash`)
);
--> statement-breakpoint
CREATE TABLE `oauth_authorization_codes` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`codeHash` varchar(128) NOT NULL,
	`clientId` varchar(255) NOT NULL,
	`redirectUri` text NOT NULL,
	`scope` varchar(512) NOT NULL DEFAULT 'routes.read',
	`expiresAt` timestamp NOT NULL,
	`consumedAt` timestamp,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `oauth_authorization_codes_id` PRIMARY KEY(`id`),
	CONSTRAINT `oauth_authorization_codes_hash_unique` UNIQUE(`codeHash`)
);
--> statement-breakpoint
CREATE TABLE `oauth_refresh_tokens` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`tokenHash` varchar(128) NOT NULL,
	`clientId` varchar(255) NOT NULL,
	`expiresAt` timestamp NOT NULL,
	`revokedAt` timestamp,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	CONSTRAINT `oauth_refresh_tokens_id` PRIMARY KEY(`id`),
	CONSTRAINT `oauth_refresh_tokens_hash_unique` UNIQUE(`tokenHash`)
);
--> statement-breakpoint
CREATE TABLE `railway_routes` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`origin` varchar(120) NOT NULL,
	`destination` varchar(120) NOT NULL,
	`name` varchar(120) NOT NULL,
	`isPinned` boolean NOT NULL DEFAULT false,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `railway_routes_id` PRIMARY KEY(`id`)
);
--> statement-breakpoint
ALTER TABLE `alexa_account_links` ADD CONSTRAINT `alexa_account_links_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `oauth_authorization_codes` ADD CONSTRAINT `oauth_authorization_codes_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `oauth_refresh_tokens` ADD CONSTRAINT `oauth_refresh_tokens_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE `railway_routes` ADD CONSTRAINT `railway_routes_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX `oauth_authorization_codes_user_expiry_idx` ON `oauth_authorization_codes` (`userId`,`expiresAt`);--> statement-breakpoint
CREATE INDEX `oauth_refresh_tokens_user_expiry_idx` ON `oauth_refresh_tokens` (`userId`,`expiresAt`);--> statement-breakpoint
CREATE INDEX `railway_routes_user_pinned_idx` ON `railway_routes` (`userId`,`isPinned`);