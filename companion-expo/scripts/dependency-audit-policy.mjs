export function isBlockingHighSeverityAdvisory(advisory) {
  return advisory.severity === "high" || advisory.severity === "critical";
}
