import { TRPCError } from "@trpc/server";
import { z } from "zod";
import {
  consumeCompanionPairingCode,
  createCompanionDevice,
  createCompanionPairingCode,
  getActiveCompanionDevice,
  importPersonalRoutes,
  listPersonalRoutes,
  listCompanionDevices,
  revokeAllCompanionDevices,
  revokeCompanionDevice,
  setCompanionDeviceSync,
  touchCompanionDevice,
} from "../db";
import { protectedProcedure, publicProcedure, router } from "../_core/trpc";
import { createUnavailableCompanionSnapshot } from "../../shared/companionSnapshot";
import { createCompanionSecret, getCompanionBearer, hashDeviceToken, hashPairingAttemptClient, hashPairingCode, tokenPrefix } from "./deviceAuth";
import { PairingAttemptGuard } from "./pairingAttemptGuard";

const deviceInput = z.object({
  deviceName: z.string().trim().min(2).max(80),
  platform: z.enum(["android", "ios"]),
});
const subjectInput = z.object({
  subject: z.object({
    kind: z.enum(["station", "route", "train"]),
    id: z.string().min(1).max(120),
    label: z.string().min(1).max(160),
  }),
});
const plannedRouteInput = z.object({
  origin: z.string().trim().min(2).max(120),
  originStationCode: z.string().trim().max(64).nullable().optional(),
  destination: z.string().trim().min(2).max(120),
  destinationStationCode: z.string().trim().max(64).nullable().optional(),
  name: z.string().trim().min(2).max(120),
  travelDate: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/),
  travelTime: z.string().trim().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
  plannedReminderMinutes: z.number().int().min(0).max(1_440).nullable(),
});

type CompanionRepository = {
  createPairingCode: typeof createCompanionPairingCode;
  consumePairingCode: typeof consumeCompanionPairingCode;
  createDevice: typeof createCompanionDevice;
  getActiveDevice: typeof getActiveCompanionDevice;
  touchDevice: typeof touchCompanionDevice;
  listDevices: typeof listCompanionDevices;
  listPersonalRoutes: typeof listPersonalRoutes;
  importPersonalRoutes: typeof importPersonalRoutes;
  setDeviceSync: typeof setCompanionDeviceSync;
  revokeAllDevices: typeof revokeAllCompanionDevices;
  revokeDevice: typeof revokeCompanionDevice;
};

const liveRepository: CompanionRepository = {
  createPairingCode: createCompanionPairingCode,
  consumePairingCode: consumeCompanionPairingCode,
  createDevice: createCompanionDevice,
  getActiveDevice: getActiveCompanionDevice,
  touchDevice: touchCompanionDevice,
  listDevices: listCompanionDevices,
  listPersonalRoutes,
  importPersonalRoutes,
  setDeviceSync: setCompanionDeviceSync,
  revokeAllDevices: revokeAllCompanionDevices,
  revokeDevice: revokeCompanionDevice,
};

function getAttemptClientKey(headers: { [key: string]: string | string[] | undefined }, ip?: string) {
  const forwarded = headers["x-forwarded-for"];
  const source = typeof forwarded === "string" ? forwarded.split(",")[0].trim() : ip ?? "unknown";
  return hashPairingAttemptClient(source || "unknown");
}

export function createCompanionRouter(repository: CompanionRepository = liveRepository, attemptGuard = new PairingAttemptGuard()) {
  return router({
    snapshot: protectedProcedure.input(subjectInput).query(({ input }) => createUnavailableCompanionSnapshot(input.subject)),
    issuePairingCode: protectedProcedure.input(deviceInput).mutation(async ({ ctx, input }) => {
      const code = createCompanionSecret();
      const expiresAt = new Date(Date.now() + 10 * 60 * 1000);
      await repository.createPairingCode({
        userId: ctx.user.id,
        codeHash: hashPairingCode(code),
        deviceName: input.deviceName,
        platform: input.platform,
        expiresAt,
      });
      return { code, expiresAtUtcMs: expiresAt.getTime() };
    }),
    redeemPairingCode: publicProcedure.input(z.object({ code: z.string().min(40).max(128) })).mutation(async ({ ctx, input }) => {
      const clientKey = getAttemptClientKey(ctx.req.headers, ctx.req.ip);
      if (!attemptGuard.allow(clientKey)) throw new TRPCError({ code: "TOO_MANY_REQUESTS", message: "Troppi tentativi di collegamento. Riprova più tardi." });
      const pairing = await repository.consumePairingCode(hashPairingCode(input.code));
      if (!pairing) throw new TRPCError({ code: "UNAUTHORIZED", message: "Codice di collegamento non valido o scaduto" });
      const token = createCompanionSecret();
      const expiresAt = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000);
      const deviceId = await repository.createDevice({
        userId: pairing.userId,
        deviceName: pairing.deviceName,
        platform: pairing.platform,
        tokenHash: hashDeviceToken(token),
        tokenPrefix: tokenPrefix(token),
        expiresAt,
      });
      return { deviceId, deviceToken: token, expiresAtUtcMs: expiresAt.getTime() };
    }),
    deviceSnapshot: publicProcedure.input(subjectInput).query(async ({ ctx, input }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      await repository.touchDevice(device.id);
      return createUnavailableCompanionSnapshot(input.subject);
    }),
    devicePlannedRoutes: publicProcedure.query(async ({ ctx }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      if (!device.syncEnabled) throw new TRPCError({ code: "FORBIDDEN", message: "Sincronizzazione non autorizzata per questo dispositivo" });
      await repository.touchDevice(device.id);
      const routes = await repository.listPersonalRoutes(device.userId);
      return routes.flatMap(route => route.travelDate && route.travelTime ? [{
        id: route.id,
        name: route.name,
        origin: route.origin,
        originStationCode: route.originStationCode,
        destination: route.destination,
        destinationStationCode: route.destinationStationCode,
        travelDate: route.travelDate,
        travelTime: route.travelTime,
        plannedReminderMinutes: route.plannedReminderMinutes,
      }] : []);
    }),
    deviceSyncStatus: publicProcedure.query(async ({ ctx }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      await repository.touchDevice(device.id);
      return { linked: true, syncEnabled: device.syncEnabled === true };
    }),
    setDeviceSync: publicProcedure.input(z.object({ enabled: z.boolean() })).mutation(async ({ ctx, input }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      const updated = await repository.setDeviceSync(device.userId, device.id, input.enabled);
      if (!updated) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      return { syncEnabled: input.enabled };
    }),
    deviceImportPlannedRoutes: publicProcedure.input(z.object({ schemaVersion: z.literal(1), routes: z.array(plannedRouteInput).min(1).max(40) })).mutation(async ({ ctx, input }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device || !device.syncEnabled) throw new TRPCError({ code: "FORBIDDEN", message: "Sincronizzazione non autorizzata per questo dispositivo" });
      await repository.touchDevice(device.id);
      return { imported: await repository.importPersonalRoutes(device.userId, input.routes) };
    }),
    revokeCurrentDevice: publicProcedure.mutation(async ({ ctx }) => {
      const token = getCompanionBearer(ctx.req.headers.authorization);
      if (!token) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non collegato" });
      const device = await repository.getActiveDevice(hashDeviceToken(token));
      if (!device) throw new TRPCError({ code: "UNAUTHORIZED", message: "Companion non autorizzato" });
      return { revoked: await repository.revokeDevice(device.userId, device.id) };
    }),
    listDevices: protectedProcedure.query(({ ctx }) => repository.listDevices(ctx.user.id)),
    setManagedDeviceSync: protectedProcedure.input(z.object({ id: z.number().int().positive(), enabled: z.boolean() })).mutation(async ({ ctx, input }) => {
      const updated = await repository.setDeviceSync(ctx.user.id, input.id, input.enabled);
      if (!updated) throw new TRPCError({ code: "NOT_FOUND", message: "Dispositivo non attivo o non associato a questo account" });
      return { syncEnabled: input.enabled };
    }),
    revokeDevice: protectedProcedure.input(z.object({ id: z.number().int().positive() })).mutation(async ({ ctx, input }) => ({
      revoked: await repository.revokeDevice(ctx.user.id, input.id),
    })),
    revokeAllDevices: protectedProcedure.mutation(async ({ ctx }) => ({ revoked: await repository.revokeAllDevices(ctx.user.id) })),
  });
}
