import { NOT_ADMIN_ERR_MSG, UNAUTHED_ERR_MSG } from '@shared/const';
import { initTRPC, TRPCError } from "@trpc/server";
import superjson from "superjson";
import type { TrpcContext } from "./context";
import { describeErrorForLog } from "./logRedaction";

export const INTERNAL_ERROR_MESSAGE = "Errore interno del servizio. Riprova più tardi.";

// Database drivers put connection strings, table names and SQL fragments in
// their error messages: they never reach a client, and the log keeps only the
// redacted description (class, driver code, parameterised SQL), never the values.
export function maskInternalError<T extends { message: string }>(shape: T, code: string): T {
  return code === "INTERNAL_SERVER_ERROR" ? { ...shape, message: INTERNAL_ERROR_MESSAGE } : shape;
}

const t = initTRPC.context<TrpcContext>().create({
  transformer: superjson,
  errorFormatter({ shape, error }) {
    if (error.code === "INTERNAL_SERVER_ERROR")
      console.error("[tRPC] internal error:", describeErrorForLog(error.cause ?? error));
    return maskInternalError(shape, error.code);
  },
});

export const router = t.router;
export const publicProcedure = t.procedure;

const requireUser = t.middleware(async opts => {
  const { ctx, next } = opts;

  if (!ctx.user) {
    throw new TRPCError({ code: "UNAUTHORIZED", message: UNAUTHED_ERR_MSG });
  }

  return next({
    ctx: {
      ...ctx,
      user: ctx.user,
    },
  });
});

export const protectedProcedure = t.procedure.use(requireUser);

export const adminProcedure = t.procedure.use(
  t.middleware(async opts => {
    const { ctx, next } = opts;

    if (!ctx.user || ctx.user.role !== 'admin') {
      throw new TRPCError({ code: "FORBIDDEN", message: NOT_ADMIN_ERR_MSG });
    }

    return next({
      ctx: {
        ...ctx,
        user: ctx.user,
      },
    });
  }),
);
