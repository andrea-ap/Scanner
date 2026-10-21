# Scanner

App Android personale per scattare o scansionare foto e PDF, organizzarli in **sessioni** con note, cercarli (anche nel testo letto con OCR) e condividerli (WhatsApp, PDF unico, altre app). Interfaccia in italiano, tema scuro. Tutto resta sul telefono, nessun server.

Package `com.scanner.app` · minSdk 26 · targetSdk 35

## Funzioni
- Sessioni con titolo e note; il titolo fa da etichetta (le sessioni con lo stesso titolo si raggruppano).
- Scansione documenti (ML Kit, ritaglio bordi e filtri), foto con la fotocamera, foto dalla galleria, PDF allegati.
- OCR offline su foto e prime 5 pagine dei PDF; ricerca su nome, note e testo con frase evidenziata. Toccando un risultato trovato in un allegato si apre subito quell'allegato.
- Anteprima dei PDF con numero di pagine e prima riga di testo; vista compatta con badge rosso per i PDF.
- Peso in MB su ogni allegato; "PDF unico" con scelta tra versione originale e compressa, con il peso di entrambe.
- Condivisione su WhatsApp, PDF unico, altre app, singolo allegato.
- Lettore a schermo intero con zoom, swipe tra gli allegati, salvataggio in Galleria.
- Backup e ripristino in zip (cartella Download), blocco app con impronta/PIN, swipe per eliminare con "Annulla".

## Stack
Kotlin, Jetpack Compose (Material3), Room, CameraX, ML Kit (OCR + Document Scanner), Coil, Navigation Compose, Biometric.

## Come si compila
Servono:
- **JDK 17**
- **Android SDK** con platform 34/35 (si installa con Android Studio o con gli strumenti da riga di comando)

1. Clona il progetto e crea `local.properties` nella cartella principale con il percorso dell'SDK (il file non è nel repository):
   ```
   sdk.dir=C:/Users/<utente>/AppData/Local/Android/Sdk
   ```
   (su Linux/macOS: `sdk.dir=/percorso/Android/Sdk`)
2. Compila l'APK di debug con il wrapper Gradle incluso (non serve installare Gradle):
   ```bash
   ./gradlew assembleDebug --no-daemon -q
   ```
   (su Windows PowerShell: `.\gradlew.bat assembleDebug`). Il file risultante è `app/build/outputs/apk/debug/app-debug.apk`.
   Un avviso "SDK XML version 4" è innocuo.

### Installazione sul telefono
Attiva il debug USB, collega il telefono e:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- Usa sempre `-r` (aggiorna): disinstallare cancella tutti i dati dell'app.
- Se compare `INSTALL_PARSE_FAILED_NO_CERTIFICATES`, ricompila e usa `adb install --no-streaming -r ...`.
- Su Xiaomi/POCO attiva "Installa via USB", altrimenti `INSTALL_FAILED_USER_RESTRICTED`.
- Antivirus come Norton 360 possono segnalare l'APK debug come "app sospetta" (falso allarme: non è firmato e usa la fotocamera). Scegli Annulla, mai Disinstalla.

L'APK è solo di debug; non c'è ancora una release firmata.

## Struttura (`app/src/main/java/com/scanner/app/`)
| File | Contenuto |
|---|---|
| `Data.kt` | Room: `Session`, `Photo` (anche i PDF), `ScannerDao`, `AppDatabase` |
| `MainViewModel.kt` | stato, sessioni, aggiunta foto/PDF, OCR, backup/ripristino |
| `HomeScreen.kt` | elenco sessioni, etichette, ricerca, ordinamento, vista compatta, menu |
| `SessionScreen.kt` | dettaglio sessione, griglia allegati, condivisione, scelta PDF unico |
| `ViewerScreen.kt` | lettore a schermo intero |
| `CameraScreen.kt` | CameraX |
| `Scan.kt` | scanner documenti ML Kit |
| `PdfExport.kt`, `PdfUtil.kt` | PDF unico (originale/compresso), rendering pagine PDF |
| `Share.kt` | WhatsApp, altre app, PDF, singolo allegato |
| `Backup.kt` | backup zip, ripristino, copia in Galleria |
| `AppPrefs.kt` | impostazioni |
| `MainActivity.kt` | navigazione, blocco app |
| `Theme.kt` | palette scura |

## Dati
- Foto e PDF in `filesDir/photos/` (privati, non in Galleria).
- Backup: `Scanner_backup_AAAAMMGG_HHMM.zip` in Download. Il ripristino non è distruttivo: salta le sessioni con stesso nome e data di creazione. Richiede Android 10+.

## Note di progetto
Le decisioni di design, lo stato delle verifiche e le idee future sono in [CLAUDE.md](CLAUDE.md).
