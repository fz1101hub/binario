const { IOSConfig, withXcodeProject } = require("expo/config-plugins");
const locationUsage = require("../native/ios/location-usage.json");

const locationKey = "NSLocationWhenInUseUsageDescription";

/**
 * Info.plist strings for every iOS localization but the Italian default, which app.config.ts
 * passes to expo-location. They are applied here instead of through the top-level `locales`
 * config: Expo would also write an Android values-b+<lang>/strings.xml for each language, and
 * values-b+zh+Hans already holds the generated native strings of the widget.
 */
function infoPlistLocales() {
  return Object.fromEntries(Object.entries(locationUsage[locationKey])
    .filter(([language]) => language !== "it")
    .map(([language, text]) => [language, { [locationKey]: text }]));
}

/**
 * Mantiene valido il prebuild iOS della baseline Expo e localizza lo scopo della posizione.
 * Widget e Apple Watch richiedono un target Xcode/watchOS separato e non vengono
 * dichiarati né generati finché tale sorgente non è integrata e testata su macOS.
 */
function withBinarioIosLocalSurfaces(config) {
  return withXcodeProject(config, async (next) => {
    next.modResults = await IOSConfig.Locales.setLocalesAsync(
      { ...next, locales: infoPlistLocales() },
      { projectRoot: next.modRequest.projectRoot, project: next.modResults },
    );
    return next;
  });
}

module.exports = withBinarioIosLocalSurfaces;
module.exports.__test = { infoPlistLocales, locationKey };
