ALTER TABLE `oauth_refresh_tokens` ADD `familyId` varchar(64) NULL;
--> statement-breakpoint
UPDATE `oauth_refresh_tokens`
SET `familyId` = SHA2(CONCAT('legacy:', `id`, ':', `tokenHash`), 256)
WHERE `familyId` IS NULL;
--> statement-breakpoint
ALTER TABLE `oauth_refresh_tokens` MODIFY `familyId` varchar(64) NOT NULL;
--> statement-breakpoint
CREATE INDEX `oauth_refresh_tokens_family_revoked_idx` ON `oauth_refresh_tokens` (`familyId`,`revokedAt`);
