import { TRPCError } from "@trpc/server";
import { z } from "zod";
import { protectedProcedure, router } from "../_core/trpc";
import { getRemoteProfile, upsertRemoteProfile } from "../db";
import { REMOTE_PROFILE_SCHEMA_VERSION, RemoteProfileConflictError, type RemoteProfileDraft } from "./contract";

const remoteProfileInput = z.object({
  schemaVersion: z.literal(REMOTE_PROFILE_SCHEMA_VERSION),
  activeRouteId: z.string().trim().min(1).max(120),
  direction: z.enum(["OUTBOUND", "RETURN"]),
  monitoredTrainNumbers: z.array(z.string().trim().min(1).max(32)).max(32),
  wearSnapshotSharingEnabled: z.boolean(),
  voiceProfileSyncRequested: z.boolean(),
  appleWatchSetupRequested: z.boolean(),
  expectedRevision: z.number().int().min(0).optional(),
  clientMutationId: z.string().uuid(),
});

export type ProfileRepository = {
  get(userId: number): ReturnType<typeof getRemoteProfile>;
  upsert(userId: number, input: RemoteProfileDraft): ReturnType<typeof upsertRemoteProfile>;
};

const defaultRepository: ProfileRepository = {
  get: getRemoteProfile,
  upsert: upsertRemoteProfile,
};

export function createProfileRouter(repository: ProfileRepository = defaultRepository) {
  return router({
    get: protectedProcedure.query(({ ctx }) => repository.get(ctx.user.id)),
    upsert: protectedProcedure.input(remoteProfileInput).mutation(async ({ ctx, input }) => {
      try {
        return await repository.upsert(ctx.user.id, input);
      } catch (error) {
        if (error instanceof RemoteProfileConflictError) {
          throw new TRPCError({ code: "CONFLICT", message: error.message });
        }
        throw error;
      }
    }),
  });
}
