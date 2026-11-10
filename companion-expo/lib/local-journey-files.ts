import * as FileSystem from "expo-file-system/legacy";

/** Cache files the Companion writes for an export (calendar, transfer archive) before the share sheet opens. */
export const localJourneyExportFileNames = ["binario-tratta.ics", "binario-tratte.json"] as const;
/** Where expo-document-picker copies a picked file (Android cacheDir and iOS Caches, same folder name). */
const documentPickerCacheFolder = "DocumentPicker";

/** Removes the cache copy of a picked file once its text has been read. */
export async function deletePickedFileCopy(uri: string) {
  try { await FileSystem.deleteAsync(uri, { idempotent: true }); } catch { /* best effort: «Cancella archivio locale» retries */ }
}

/**
 * Removes the export files and any picked-file copies left in the app cache. An export already handed to
 * another app or service is outside the Companion and is not touched.
 */
export async function deleteLocalJourneyCacheFiles() {
  const cacheDirectory = FileSystem.cacheDirectory;
  if (!cacheDirectory) return;
  for (const name of [...localJourneyExportFileNames, documentPickerCacheFolder]) {
    await FileSystem.deleteAsync(`${cacheDirectory}${name}`, { idempotent: true });
  }
}
