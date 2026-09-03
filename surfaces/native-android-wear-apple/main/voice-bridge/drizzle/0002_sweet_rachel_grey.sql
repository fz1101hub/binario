CREATE TABLE `binario_profiles` (
	`id` int AUTO_INCREMENT NOT NULL,
	`userId` int NOT NULL,
	`schemaVersion` int NOT NULL DEFAULT 1,
	`activeRouteId` varchar(120) NOT NULL,
	`direction` enum('OUTBOUND','RETURN') NOT NULL,
	`monitoredTrainNumbersJson` text NOT NULL,
	`wearSnapshotSharingEnabled` boolean NOT NULL DEFAULT true,
	`voiceProfileSyncRequested` boolean NOT NULL DEFAULT false,
	`appleWatchSetupRequested` boolean NOT NULL DEFAULT false,
	`revision` int NOT NULL DEFAULT 1,
	`lastMutationId` varchar(36) NOT NULL,
	`createdAt` timestamp NOT NULL DEFAULT (now()),
	`updatedAt` timestamp NOT NULL DEFAULT (now()) ON UPDATE CURRENT_TIMESTAMP,
	CONSTRAINT `binario_profiles_id` PRIMARY KEY(`id`),
	CONSTRAINT `binario_profiles_user_unique` UNIQUE(`userId`)
);
--> statement-breakpoint
ALTER TABLE `binario_profiles` ADD CONSTRAINT `binario_profiles_userId_users_id_fk` FOREIGN KEY (`userId`) REFERENCES `users`(`id`) ON DELETE cascade ON UPDATE no action;