export function getCompanionGuidance(simpleMode: boolean) {
  return simpleMode
    ? { intro: "Pianifica una tratta locale e controlla sempre data, ora e promemoria.", scheduledTitle: "Orari pianificati", scheduledBody: "Binario mostra solo il piano salvato sul dispositivo." }
    : { intro: "Pianifica una tratta, conserva la preparazione sul dispositivo e distingui sempre l’orario programmato dai dati realtime.", scheduledTitle: "Programmazione locale", scheduledBody: "Gli orari mostrati derivano dalle tratte personali salvate. Ritardi, binari e cancellazioni restano non disponibili senza una fonte autorizzata." };
}
