import {
  decideRemoteProfileWrite,
  normalizeRemoteProfileDraft,
  RemoteProfileConflictError,
  toRemoteProfileDto,
  type RemoteProfileDraft,
  type RemoteProfileStoredValues,
  type StoredRemoteProfile,
} from "./contract";

export type RemoteProfileStore = {
  load(userId: number): Promise<StoredRemoteProfile | null>;
  insert(userId: number, revision: number, values: RemoteProfileStoredValues): Promise<void>;
  compareAndSet(userId: number, expectedRevision: number, nextRevision: number, values: RemoteProfileStoredValues): Promise<boolean>;
};

export async function synchronizeRemoteProfile(store: RemoteProfileStore, userId: number, input: RemoteProfileDraft) {
  const profile = normalizeRemoteProfileDraft(input);
  const existing = await store.load(userId);
  const decision = decideRemoteProfileWrite(existing, profile);

  if (decision.idempotent) {
    return { profile: toRemoteProfileDto(existing!), idempotent: true as const };
  }

  const values: RemoteProfileStoredValues = {
    schemaVersion: profile.schemaVersion,
    activeRouteId: profile.activeRouteId,
    direction: profile.direction,
    monitoredTrainNumbersJson: JSON.stringify(profile.monitoredTrainNumbers),
    // Legacy database columns remain until a dedicated migration removes them.
    // They are not part of the Alexa companion API and cannot configure watches.
    wearSnapshotSharingEnabled: false,
    voiceProfileSyncRequested: true,
    appleWatchSetupRequested: false,
    lastMutationId: profile.clientMutationId,
  };

  if (!existing) {
    await store.insert(userId, decision.nextRevision, values);
  } else {
    const updated = await store.compareAndSet(userId, existing.revision, decision.nextRevision, values);
    if (!updated) throw new RemoteProfileConflictError();
  }

  const saved = await store.load(userId);
  if (!saved) throw new Error("Profilo non disponibile dopo il salvataggio");
  return { profile: toRemoteProfileDto(saved), idempotent: false as const };
}
