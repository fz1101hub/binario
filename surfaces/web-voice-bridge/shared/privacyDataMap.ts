export type PrivacyDataMapItem = { id: "browser" | "account" | "companion" | "voice" | "realtime"; title: string; location: string; description: string };

export const privacyDataMap: readonly PrivacyDataMapItem[] = [
  { id: "browser", title: "Tratte senza accesso", location: "Solo nel browser usato", description: "Le tratte create prima dell’accesso restano nel browser. Puoi eliminarle dalla Home o dal controllo locale del Privacy Center; non sono incluse nell’esportazione dell’area personale." },
  { id: "account", title: "Tratte e preferenze personali", location: "Area personale Binario", description: "Sono presenti solo se scegli di accedere e copi esplicitamente le tratte selezionate. La sincronizzazione companion richiede un consenso revocabile per dispositivo; puoi esportare o eliminare l’area personale da questa sezione." },
  { id: "companion", title: "Telefono, widget e smartwatch", location: "Dispositivo associato", description: "Il companion usa un payload minimo e con scadenza. La cancellazione dei dati dell’area personale revoca collegamenti e token; i dati locali del telefono si gestiscono nell’app." },
  { id: "voice", title: "Dittatura", location: "Elaborazione temporanea", description: "Il microfono si attiva solo dopo consenso. Il testo è modificabile; l’audio non entra nell’archivio personale e il buffer in memoria viene azzerato. La retention dell’archivio tecnico sottostante non è attestata qui." },
  { id: "realtime", title: "Dati ferroviari realtime", location: "Nessuna fonte attiva", description: "Binario non riceve né conserva ritardi, binari, cancellazioni o posizione finché non esiste una fonte autorizzata e verificata." },
];
