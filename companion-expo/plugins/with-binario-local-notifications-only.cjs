const { withEntitlementsPlist } = require("expo/config-plugins");

/**
 * The Companion schedules only local notifications. expo-notifications adds the iOS push entitlement
 * (aps-environment) by default; this plugin removes it. It must be listed before "expo-notifications"
 * in app.config.ts: Expo runs the mods of later plugins first, so this one runs after it.
 */
function withBinarioLocalNotificationsOnly(config) {
  return withEntitlementsPlist(config, (next) => {
    delete next.modResults["aps-environment"];
    return next;
  });
}

module.exports = withBinarioLocalNotificationsOnly;
