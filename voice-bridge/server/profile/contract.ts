export const REMOTE_PROFILE_SCHEMA_VERSION = 1 as const;

export type RemoteProfileDraft = {
  schemaVersion: number;
  activeRouteId: string;
  direction: "OUTBOUND" | "RETURN";
  monitoredTrainNumbers: string[];
  wearSnapshotSharingEnabled: boolean;
  voiceProfileSyncRequested: boolean;
  appleWatchSetupRequested: boolean;
  expectedRevision?: number;
  clientMutationId: string;
};

export type StoredRemoteProfile = {
  schemaVersion: number;
  activeRouteId: string;
  direction: "OUTBOUND" | "RETURN";
  monitoredTrainNumbersJson: string;
  wearSnapshotSharingEnabled: boolean;
  voiceProfileSyncRequested: boolean;
  appleWatchSetupRequested: boolean;
  revision: number;
  lastMutationId: string;
  updatedAt: Date;
};

export type RemoteProfileStoredValues = Omit<StoredRemoteProfile, "revision" | "updatedAt">;

export type RemoteProfileDto = {
  schemaVersion: number;
  activeRouteId: string;
  direction: "OUTBOUND" | "RETURN";
  monitoredTrainNumbers: string[];
  wearSnapshotSharingEnabled: boolean;
  voiceProfileSyncRequested: boolean;
  appleWatchSetupRequested: boolean;
  revision: number;
  updatedAt: Date;
};

export class RemoteProfileConflictError extends Error {
  constructor() {
    super("Il profilo è stato modificato su un altro dispositivo. Rileggi lo stato prima di salvare.");
    this.name = "RemoteProfileConflictError";
  }
}

function normalizeRouteId(value: string): string {
  const normalized = value.trim();
  if (normalized.length < 1 || normalized.length > 120) throw new Error("Identificativo tratta non valido");
  return normalized;
}

function normalizeTrainNumber(value: string): string {
  const normalized = value.trim().replace(/\s+/g, "");
  if (!/^[A-Za-z0-9-]{1,32}$/.test(normalized)) throw new Error("Numero treno non valido");
  return normalized;
}

export function normalizeRemoteProfileDraft(input: RemoteProfileDraft) {
  if (input.schemaVersion !== REMOTE_PROFILE_SCHEMA_VERSION) throw new Error("Versione profilo non supportata");
  const monitoredTrainNumbers = Array.from(new Set(input.monitoredTrainNumbers.map(normalizeTrainNumber)));
  if (monitoredTrainNumbers.length > 32) throw new Error("Sono consentiti al massimo 32 monitoraggi");

  return {
    schemaVersion: REMOTE_PROFILE_SCHEMA_VERSION,
    activeRouteId: normalizeRouteId(input.activeRouteId),
    direction: input.direction,
    monitoredTrainNumbers,
    wearSnapshotSharingEnabled: input.wearSnapshotSharingEnabled,
    voiceProfileSyncRequested: input.voiceProfileSyncRequested,
    appleWatchSetupRequested: input.appleWatchSetupRequested,
    expectedRevision: input.expectedRevision,
    clientMutationId: input.clientMutationId,
  };
}

export function isIdempotentReplay(lastMutationId: string | null | undefined, clientMutationId: string): boolean {
  return Boolean(lastMutationId) && lastMutationId === clientMutationId;
}

export function decideRemoteProfileWrite(existing: Pick<StoredRemoteProfile, "revision" | "lastMutationId"> | null, input: RemoteProfileDraft) {
  if (existing && isIdempotentReplay(existing.lastMutationId, input.clientMutationId)) {
    return { idempotent: true as const, nextRevision: existing.revision };
  }
  if (existing && input.expectedRevision !== undefined && input.expectedRevision !== existing.revision) {
    throw new RemoteProfileConflictError();
  }
  if (!existing && input.expectedRevision !== undefined && input.expectedRevision !== 0) {
    throw new RemoteProfileConflictError();
  }
  return { idempotent: false as const, nextRevision: (existing?.revision ?? 0) + 1 };
}

export function toRemoteProfileDto(record: StoredRemoteProfile): RemoteProfileDto {
  let monitoredTrainNumbers: unknown = [];
  try {
    monitoredTrainNumbers = JSON.parse(record.monitoredTrainNumbersJson);
  } catch {
    throw new Error("Profilo remoto non valido");
  }
  if (!Array.isArray(monitoredTrainNumbers) || !monitoredTrainNumbers.every(value => typeof value === "string")) {
    throw new Error("Profilo remoto non valido");
  }
  const normalized = normalizeRemoteProfileDraft({
    schemaVersion: record.schemaVersion,
    activeRouteId: record.activeRouteId,
    direction: record.direction,
    monitoredTrainNumbers,
    wearSnapshotSharingEnabled: record.wearSnapshotSharingEnabled,
    voiceProfileSyncRequested: record.voiceProfileSyncRequested,
    appleWatchSetupRequested: record.appleWatchSetupRequested,
    clientMutationId: record.lastMutationId,
  });
  return {
    schemaVersion: normalized.schemaVersion,
    activeRouteId: normalized.activeRouteId,
    direction: normalized.direction,
    monitoredTrainNumbers: normalized.monitoredTrainNumbers,
    wearSnapshotSharingEnabled: normalized.wearSnapshotSharingEnabled,
    voiceProfileSyncRequested: normalized.voiceProfileSyncRequested,
    appleWatchSetupRequested: normalized.appleWatchSetupRequested,
    revision: record.revision,
    updatedAt: record.updatedAt,
  };
}
