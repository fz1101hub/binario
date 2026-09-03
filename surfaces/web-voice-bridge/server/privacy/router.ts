import { protectedProcedure, router } from "../_core/trpc";
import { deletePersonalBinarioData, exportPersonalBinarioData } from "../db";

export function createPrivacyRouter() {
  return router({
    exportData: protectedProcedure.query(({ ctx }) => exportPersonalBinarioData(ctx.user.id)),
    deleteData: protectedProcedure.mutation(async ({ ctx }) => {
      await deletePersonalBinarioData(ctx.user.id);
      return { success: true } as const;
    }),
  });
}
