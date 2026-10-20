# Binario v0.1.1 — recupero locale verificato

## Perimetro controllato

Questo controllo usa esclusivamente la cartella locale originaria `/home/ubuntu/binario-companion-native` e la sua copia già presente nel ramo `recovery/expo-companion-session-20260903`. Non utilizza archivio di sessione, versioni v0.20.x o codice del repository storico per stabilire provenienza o completezza.

| Verifica | Esito |
| --- | --- |
| Identità app | `app.config.ts`: versione `0.1.1`, codice Android `3`, package `im.binario.companion`. |
| Sorgenti confrontati | 35 file TypeScript/TSX/CJS/JSON/Markdown sotto `app`, `components`, `lib`, `plugins`, `tests` e `docs`. |
| Integrità | Le impronte SHA-256 dei 35 file sono identiche tra la cartella locale originaria e `companion-expo/` nel ramo laterale. |
| File radice | `package.json`, `app.config.ts`, `vitest.config.ts` e i documenti presenti sono inclusi nella baseline del ramo laterale. |
| APK locale | `android/app/build/outputs/apk/release/app-release.apk`, 72.592.327 byte, SHA-256 `e17c48223e22c3c65da999ac23dcaaf467cf80e824a892781140f8c1fac2358b`. |
| APK su Git | `artifacts/v0.1.1/Binario-v0.1.1-release.apk`, commit `bd2a6ce`. |

## Commit di preservazione

| Commit | Contenuto |
| --- | --- |
| `b929618` | Copia della baseline locale v0.1.1 sotto `companion-expo/`, escluse sole dipendenze e build rigenerabili. |
| `506c6ae` | Checksum dell’APK e checklist di recupero. |
| `bd2a6ce` | APK release locale v0.1.1 effettivamente versionato nel ramo laterale. |

## Limite tecnico ancora visibile

La cartella locale v0.1.1 non contiene `node_modules`, lockfile e alcuni moduli richiamati dagli import. Il recupero attesta che **tutto ciò che era presente localmente** è stato copiato e confrontato, non che il progetto incompleto possa già essere ricompilato. Nessun file mancante è stato rimpiazzato, ricostruito o prelevato da un’altra linea durante questa verifica.

> La v0.1.1 locale è la baseline da preservare. Qualsiasi integrazione futura sarà documentata come modifica nuova, con provenienza, test e commit distinti.
