export type RailDataStatus = {
  label: string;
  detail: string;
  tone: "live" | "scheduled" | "unavailable";
};

export function getRailDataStatus(input: {
  hasRealtimeSource: boolean;
  hasScheduledSource: boolean;
}): RailDataStatus {
  if (input.hasRealtimeSource) {
    return {
      label: "Dati live verificati",
      detail: "Aggiornati dalla fonte ferroviaria collegata.",
      tone: "live",
    };
  }

  if (input.hasScheduledSource) {
    return {
      label: "Orario programmato",
      detail: "Non contiene ritardi, binari, cancellazioni o posizioni realtime.",
      tone: "scheduled",
    };
  }

  return {
    label: "Dati realtime non disponibili",
    detail: "Il monitoraggio sarà pronto quando verrà collegata una fonte ferroviaria autorizzata.",
    tone: "unavailable",
  };
}

export function createPersonalRouteName(origin: string, destination: string, customName: string) {
  const normalizedCustomName = customName.trim();
  if (normalizedCustomName) return normalizedCustomName;
  return `${origin.trim()} → ${destination.trim()}`;
}

export function swapEndpoints(origin: string, destination: string) {
  return { origin: destination, destination: origin };
}
