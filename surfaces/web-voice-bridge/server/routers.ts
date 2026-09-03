import { COOKIE_NAME } from "@shared/const";
import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { getSessionCookieOptions } from "./_core/cookies";
import { systemRouter } from "./_core/systemRouter";
import { protectedProcedure, publicProcedure, router } from "./_core/trpc";
import { archivePersonalRoute, clearRecentStations, createPersonalRoute, duplicatePersonalRoute, getAlexaLinkStatus, getPersonalRoute, importPersonalRoutes, invertPersonalRoute, listPersonalRoutes, listStationPreferences, recordStationOpened, removePersonalRoute, reorderPersonalRoutes, restorePersonalRoute, revokeAllVoiceCredentialsForUser, setPersonalRoutePinned, setStationFavorite, updatePersonalRoute } from "./db";
import { createProfileRouter } from "./profile/router";
import { dictationMimeTypes, transcribeItalianRailDictation } from "./dictation";
import { isAlexaConfigured } from "./voice/config";
import { createCompanionRouter } from "./companion/router";
import { createPrivacyRouter } from "./privacy/router";
import { PairingAttemptGuard } from "./companion/pairingAttemptGuard";
import { hashPairingAttemptClient } from "./companion/deviceAuth";

const routeInput = z.object({
  origin: z.string().min(2).max(120),
  originStationCode: z.string().max(64).nullable().optional(),
  destination: z.string().min(2).max(120),
  destinationStationCode: z.string().max(64).nullable().optional(),
  name: z.string().min(2).max(120),
  isPinned: z.boolean().optional(),
  travelDate: z.string().max(10).nullable().optional(),
  travelTime: z.string().max(5).nullable().optional(),
  travelProfile: z.enum(["commuter", "occasional", "accessibility"]).optional(),
  plannedReminderMinutes: z.number().int().min(0).max(1_440).nullable().optional(),
  personalNote: z.string().max(1_000).nullable().optional(),
  accessibilityNote: z.string().max(500).nullable().optional(),
  personalTags: z.array(z.string().min(2).max(32)).max(5).optional(),
  preDepartureChecklist: z.array(z.object({ id: z.string().regex(/^[a-z0-9_-]{1,40}$/i), label: z.string().min(1).max(140), done: z.boolean() })).max(8).optional(),
});

const stationInput = z.object({
  stationName: z.string().min(2).max(120),
  stationCode: z.string().min(1).max(64),
});

const guestDictationAttemptGuard = new PairingAttemptGuard();

function getGuestDictationClientKey(headers: { [key: string]: string | string[] | undefined }, ip?: string) {
  const forwarded = headers["x-forwarded-for"];
  const source = typeof forwarded === "string" ? forwarded.split(",")[0].trim() : ip ?? "unknown";
  return hashPairingAttemptClient(source || "unknown");
}

export const appRouter = router({
    // if you need to use socket.io, read and register route in server/_core/index.ts, all api should start with '/api/' so that the gateway can route correctly
  system: systemRouter,
  auth: router({
    me: publicProcedure.query(opts => opts.ctx.user),
    logout: publicProcedure.mutation(({ ctx }) => {
      const cookieOptions = getSessionCookieOptions(ctx.req);
      ctx.res.clearCookie(COOKIE_NAME, { ...cookieOptions, maxAge: -1 });
      return {
        success: true,
      } as const;
    }),
  }),
  routes: router({
    list: protectedProcedure.input(z.object({ includeArchived: z.boolean().optional() }).optional()).query(({ ctx, input }) => input ? listPersonalRoutes(ctx.user.id, input) : listPersonalRoutes(ctx.user.id)),
    get: protectedProcedure.input(z.object({ id: z.number().int().positive() })).query(({ ctx, input }) => getPersonalRoute(ctx.user.id, input.id)),
    create: protectedProcedure.input(routeInput).mutation(({ ctx, input }) => createPersonalRoute(ctx.user.id, input)),
    update: protectedProcedure.input(routeInput.extend({ id: z.number().int().positive() })).mutation(({ ctx, input }) => {
      const { id, ...draft } = input;
      return updatePersonalRoute(ctx.user.id, id, draft);
    }),
    duplicate: protectedProcedure.input(z.object({ id: z.number().int().positive() })).mutation(({ ctx, input }) => duplicatePersonalRoute(ctx.user.id, input.id)),
    invert: protectedProcedure.input(z.object({ id: z.number().int().positive() })).mutation(({ ctx, input }) => invertPersonalRoute(ctx.user.id, input.id)),
    import: protectedProcedure.input(z.object({ schemaVersion: z.literal(1), routes: z.array(routeInput).min(1).max(50) })).mutation(async ({ ctx, input }) => ({ imported: await importPersonalRoutes(ctx.user.id, input.routes) })),
    setPinned: protectedProcedure
      .input(z.object({ id: z.number().int().positive(), isPinned: z.boolean() }))
      .mutation(async ({ ctx, input }) => {
        await setPersonalRoutePinned(ctx.user.id, input.id, input.isPinned);
        return { success: true } as const;
      }),
    reorder: protectedProcedure.input(z.object({ ids: z.array(z.number().int().positive()).min(1).max(50) })).mutation(async ({ ctx, input }) => {
      await reorderPersonalRoutes(ctx.user.id, input.ids);
      return { success: true } as const;
    }),
    remove: protectedProcedure
      .input(z.object({ id: z.number().int().positive() }))
      .mutation(async ({ ctx, input }) => {
        await removePersonalRoute(ctx.user.id, input.id);
        return { success: true } as const;
      }),
    archive: protectedProcedure
      .input(z.object({ id: z.number().int().positive() }))
      .mutation(async ({ ctx, input }) => ({ success: await archivePersonalRoute(ctx.user.id, input.id) })),
    restore: protectedProcedure
      .input(z.object({ id: z.number().int().positive() }))
      .mutation(async ({ ctx, input }) => ({ success: await restorePersonalRoute(ctx.user.id, input.id) })),
  }),
  stations: router({
    listPreferences: protectedProcedure.query(({ ctx }) => listStationPreferences(ctx.user.id)),
    markOpened: protectedProcedure.input(stationInput).mutation(async ({ ctx, input }) => {
      await recordStationOpened(ctx.user.id, input);
      return { success: true } as const;
    }),
    setFavorite: protectedProcedure.input(stationInput.extend({ isFavorite: z.boolean() })).mutation(async ({ ctx, input }) => {
      await setStationFavorite(ctx.user.id, input);
      return { success: true } as const;
    }),
    clearRecent: protectedProcedure.mutation(async ({ ctx }) => {
      await clearRecentStations(ctx.user.id);
      return { success: true } as const;
    }),
  }),
  companion: createCompanionRouter(),
  privacy: createPrivacyRouter(),
  profile: createProfileRouter(),
  dictation: router({
    transcribe: publicProcedure
      .input(z.object({
        audioBase64: z.string().min(4).max(22_369_624),
        mimeType: z.enum(dictationMimeTypes),
      }))
      .mutation(({ ctx, input }) => {
        if (!guestDictationAttemptGuard.allow(getGuestDictationClientKey(ctx.req.headers, ctx.req.ip))) throw new TRPCError({ code: "TOO_MANY_REQUESTS", message: "Troppe richieste di dettatura. Riprova più tardi." });
        return transcribeItalianRailDictation(input);
      }),
  }),
  voice: router({
    status: protectedProcedure.query(async ({ ctx }) => {
      const link = await getAlexaLinkStatus(ctx.user.id);
      return {
        configured: isAlexaConfigured(),
        linked: Boolean(link && !link.revokedAt),
        lastVerifiedAt: link?.lastVerifiedAt ?? null,
      };
    }),
    unlink: protectedProcedure.mutation(async ({ ctx }) => {
      await revokeAllVoiceCredentialsForUser(ctx.user.id);
      return { success: true } as const;
    }),
  }),
});

export type AppRouter = typeof appRouter;
