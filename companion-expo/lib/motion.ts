/** Screen transition for the root stack: none when the user (in Binario or in the system) asked to reduce motion. */
export function screenAnimation(motionReduced: boolean) {
  return motionReduced ? "none" as const : "default" as const;
}

/** Binario's «Riduci movimento» adds to the system setting; it never turns the system's request off. */
export function isMotionReduced(appSetting: boolean, systemSetting: boolean) {
  return appSetting || systemSetting;
}
