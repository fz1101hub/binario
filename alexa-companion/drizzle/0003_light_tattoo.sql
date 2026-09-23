ALTER TABLE `alexa_account_links` ADD `credentialVersion` int DEFAULT 1 NOT NULL;--> statement-breakpoint
ALTER TABLE `oauth_authorization_codes` ADD `revokedAt` timestamp;