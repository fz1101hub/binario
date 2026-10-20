/**
 * Mantiene valido il prebuild iOS della baseline Expo.
 * Widget e Apple Watch richiedono un target Xcode/watchOS separato e non vengono
 * dichiarati né generati finché tale sorgente non è integrata e testata su macOS.
 */
function withBinarioIosLocalSurfaces(config) {
  return config;
}

module.exports = withBinarioIosLocalSurfaces;
