import { defineConfig } from "vitest/config";
import path from "node:path";
import { fileURLToPath } from "node:url";

const dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  resolve: {
    alias: {
      "expo-localization": path.resolve(
        dirname,
        "tests/stubs/expo-localization.ts",
      ),
      "@": path.resolve(dirname, "."),
    },
  },
  test: { include: ["tests/**/*.test.ts"] },
});
