-- Add refresh-token family lineage for replay detection.
-- Existing tokens each start in their own family so the migration is additive
-- and never joins unrelated credentials.
ALTER TABLE `oauth_refresh_tokens` ADD COLUMN IF NOT EXISTS `familyId` varchar(64) NULL;
--> statement-breakpoint
UPDATE `oauth_refresh_tokens` SET `familyId` = CONCAT('legacy-', `id`) WHERE `familyId` IS NULL;
--> statement-breakpoint
ALTER TABLE `oauth_refresh_tokens` MODIFY COLUMN `familyId` varchar(64) NOT NULL;
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS `oauth_refresh_tokens_family_idx` ON `oauth_refresh_tokens` (`familyId`);
