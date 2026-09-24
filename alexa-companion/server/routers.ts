import { COOKIE_NAME } from "@shared/const";
import { z } from "zod";
import { getSessionCookieOptions } from "./_core/cookies";
import { protectedProcedure, publicProcedure, router } from "./_core/trpc";
import { createPersonalRoute, deleteUserAccount, getAlexaLinkStatus, listPersonalRoutes, removePersonalRoute, revokeAllVoiceCredentialsForUser, setPersonalRoutePinned } from "./db";
import { createProfileRouter } from "./profile/router";
import { isAlexaConfigured } from "./voice/config";

const routeInput = z.object({
  origin: z.string().min(2).max(120),
  destination: z.string().min(2).max(120),
  name: z.string().min(2).max(120),
  isPinned: z.boolean().optional(),
});

export const appRouter = router({
  auth: router({
    me: publicProcedure.query(opts => opts.ctx.user),
    logout: publicProcedure.mutation(({ ctx }) => {
      const cookieOptions = getSessionCookieOptions(ctx.req);
      ctx.res.clearCookie(COOKIE_NAME, cookieOptions);
      return {
        success: true,
      } as const;
    }),
  }),
  account: router({
    delete: protectedProcedure
      .input(z.object({ confirmation: z.literal("ELIMINA") }))
      .mutation(async ({ ctx }) => {
        const deleted = await deleteUserAccount(ctx.user.id);
        if (!deleted) throw new Error("Account non trovato durante la cancellazione");
        ctx.res.clearCookie(COOKIE_NAME, getSessionCookieOptions(ctx.req));
        return { success: true } as const;
      }),
  }),
  routes: router({
    list: protectedProcedure.query(({ ctx }) => listPersonalRoutes(ctx.user.id)),
    create: protectedProcedure.input(routeInput).mutation(({ ctx, input }) => createPersonalRoute(ctx.user.id, input)),
    setPinned: protectedProcedure
      .input(z.object({ id: z.number().int().positive(), isPinned: z.boolean() }))
      .mutation(async ({ ctx, input }) => {
        await setPersonalRoutePinned(ctx.user.id, input.id, input.isPinned);
        return { success: true } as const;
      }),
    remove: protectedProcedure
      .input(z.object({ id: z.number().int().positive() }))
      .mutation(async ({ ctx, input }) => {
        await removePersonalRoute(ctx.user.id, input.id);
        return { success: true } as const;
      }),
  }),
  profile: createProfileRouter(),
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
