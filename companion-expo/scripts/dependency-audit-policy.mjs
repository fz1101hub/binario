/**
 * High/critical advisories block the Companion release gate.
 *
 * The only exceptions are advisories for which the registry declares no fixed
 * release at all (`patched_versions === "<0.0.0"`) and that reach the project
 * exclusively through Expo build/dev tooling, never through the React Native
 * bundle shipped on devices. Each exception is closed by advisory id, module,
 * installed version and the single transitive path: any change (a new path, a
 * new version, a published patch) makes the advisory blocking again.
 */
export const allowedUnpatchedAdvisories = Object.freeze([
  Object.freeze({
    id: "GHSA-86w9-cpqp-85rv",
    module: "node-forge",
    version: "1.4.0",
    path: ".>expo>@expo/cli>node-forge",
    reason: "Expo CLI dev-server certificate tooling; not bundled in the app.",
  }),
  Object.freeze({
    id: "GHSA-vfj7-8cjw-p6xm",
    module: "braces",
    version: "3.0.3",
    path: ".>expo>@expo/metro>metro-file-map>micromatch>braces",
    reason: "Metro build-time file watching over project-controlled globs; not bundled in the app.",
  }),
]);

function isSeverityBlocking(advisory) {
  return advisory.severity === "high" || advisory.severity === "critical";
}

function matchesException(advisory, exception) {
  const findings = advisory.findings ?? [];
  return advisory.github_advisory_id === exception.id &&
    advisory.module_name === exception.module &&
    advisory.patched_versions === "<0.0.0" &&
    findings.length === 1 &&
    findings[0].version === exception.version &&
    (findings[0].paths ?? []).length === 1 &&
    findings[0].paths[0] === exception.path;
}

export function isBlockingHighSeverityAdvisory(advisory) {
  if (!isSeverityBlocking(advisory)) return false;
  return !allowedUnpatchedAdvisories.some(exception => matchesException(advisory, exception));
}

export function isTemporarilyAllowedAdvisory(advisory) {
  return isSeverityBlocking(advisory) && !isBlockingHighSeverityAdvisory(advisory);
}
