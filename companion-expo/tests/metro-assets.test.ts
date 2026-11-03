import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const requireFromProject = createRequire(import.meta.url);
const requireFromExpo = createRequire(requireFromProject.resolve("expo/package.json"));
const metroPackagePath = requireFromExpo.resolve("metro/package.json");
const metroPackage = requireFromExpo(metroPackagePath) as { version: string };
const { getAssetData } = requireFromExpo(
  path.join(path.dirname(metroPackagePath), "src", "Assets.js"),
) as {
  getAssetData: (
    assetPath: string,
    localPath: string,
    assetDataPlugins: string[],
    platform: string | null,
    publicPath: string,
  ) => Promise<{ width?: number; height?: number; type: string }>;
};

describe("patched Metro asset metadata", () => {
  it("reads image bytes before calling image-size 2", async () => {
    expect(metroPackage.version).toBe("0.83.3");

    const assetPath = path.join(
      projectRoot,
      "assets",
      "brand",
      "binario-companion-icon.png",
    );
    const data = await getAssetData(
      assetPath,
      "assets/brand/binario-companion-icon.png",
      [],
      null,
      "/assets",
    );

    expect(data).toMatchObject({ width: 1024, height: 1024, type: "png" });
  });
});
