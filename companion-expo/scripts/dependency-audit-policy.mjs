const allowedUnpatchedAdvisories = new Map([
  ["GHSA-w3rx-r6r6-pgpr", "image-size"],
  ["GHSA-5p2g-fcmc-qvqq", "image-size"],
]);

const allowedImageSizeVersion = "1.2.1";
const allowedImageSizePath = ".>expo>@expo/metro>metro>image-size";

export function isBlockingHighSeverityAdvisory(advisory) {
  if (advisory.severity !== "high" && advisory.severity !== "critical") return false;

  const allowedModule = allowedUnpatchedAdvisories.get(advisory.github_advisory_id);
  const findingsAreScoped = advisory.findings?.length === 1 &&
    advisory.findings[0].version === allowedImageSizeVersion &&
    advisory.findings[0].paths?.length === 1 &&
    advisory.findings[0].paths[0] === allowedImageSizePath;

  return allowedModule !== advisory.module_name ||
    advisory.patched_versions !== "<0.0.0" ||
    !findingsAreScoped;
}

export function isTemporarilyAllowedAdvisory(advisory) {
  return (advisory.severity === "high" || advisory.severity === "critical") &&
    !isBlockingHighSeverityAdvisory(advisory);
}
