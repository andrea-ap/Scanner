# Scanner

App Android personale per scattare o scansionare foto e PDF, organizzarli in **sessioni** con note, cercarli (anche nel testo letto con OCR) e condividerli (WhatsApp, PDF unico, altre app). Interfaccia in italiano, tema scuro. Tutto resta sul telefono, nessun server.

Package `com.scanner.app` · minSdk 26 · targetSdk 35

## Funzioni
- Sessioni con titolo e note; il titolo fa da etichetta (le sessioni con lo stesso titolo si raggruppano).
- Scansione documenti (ML Kit, ritaglio bordi e filtri), foto con la fotocamera, foto dalla galleria, PDF allegati.
- OCR offline su foto e prime 5 pagine dei PDF; ricerca su nome, note e testo con frase evidenziata. Toccando un risultato trovato in un allegato si apre subito quell'allegato.
- Anteprima dei PDF con numero di pagine e prima riga di testo; vista compatta con badge rosso per i PDF.
- Peso in MB su ogni allegato; "PDF unico" con scelta tra versione originale e compressa, con il peso di entrambe.
- Promemoria facoltativo con notifica su ogni sessione (data e ora), anche dopo il riavvio del telefono. Su alcuni telefoni (es. Xiaomi) serve togliere le restrizioni di batteria all'app e attivare l'avvio automatico.
- Condivisione su WhatsApp, PDF unico, altre app, singolo allegato.
- Lettore a schermo intero con zoom, swipe tra gli allegati, salvataggio in Galleria.
- Backup e ripristino in zip (cartella Download), blocco app con impronta/PIN, swipe per eliminare con "Annulla".

## Permessi
- Fotocamera: per scattare le foto.
- Notifiche: per i promemoria (Android 13+ lo chiede al primo promemoria).
- Avvio del telefono: per riattivare i promemoria dopo un riavvio.

## Stack
Kotlin, Jetpack Compose (Material3), Room, CameraX, ML Kit (OCR + Document Scanner), Coil, Navigation Compose, Biometric.

## Scaricare l'APK (senza compilare)
L'APK pronto è nelle [Release](https://github.com/andrea-ap/Scanner/releases): apri l'ultima dal telefono, scarica `Scanner-X.Y.apk` e aprilo. Android chiederà di consentire l'installazione di app da quella fonte (browser o file manager) la prima volta.

Se l'app è già installata, il nuovo APK la **aggiorna mantenendo i dati**, purché sia firmato con la stessa chiave della versione installata (vedi [Firma](#firma-degli-apk-pubblicati)). Scaricandolo direttamente sul telefono non si passa da `adb`, quindi non compare nemmeno l'errore `INSTALL_PARSE_FAILED_NO_CERTIFICATES` dovuto al trasferimento via USB.

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

## Pubblicare una nuova versione
Il workflow [`.github/workflows/release.yml`](.github/workflows/release.yml) compila l'APK su GitHub Actions (non serve nulla installato sul PC):
- **tag** `vX.Y` → compila l'APK firmato e lo pubblica in una nuova GitHub Release `Scanner X.Y`:
  ```bash
  git tag v1.1
  git push origin v1.1
  ```
  `versionName` viene dal tag, `versionCode` dal numero di esecuzione del workflow (sempre crescente).
- **avvio manuale** (Actions → APK → Run workflow) → APK firmato scaricabile come artifact, senza release.
- **pull request** → controlla solo che l'app compili.

### Firma degli APK pubblicati
Android accetta un aggiornamento solo se l'APK è firmato con la **stessa chiave** della versione installata; altrimenti bisogna disinstallare, e disinstallare cancella tutti i dati. Il workflow legge la chiave da quattro segreti del repository (Settings → Secrets and variables → Actions):

| Segreto | Contenuto |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | il file keystore codificato in base64 |
| `SIGNING_STORE_PASSWORD` | password del keystore |
| `SIGNING_KEY_ALIAS` | alias della chiave |
| `SIGNING_KEY_PASSWORD` | password della chiave |

**Opzione A, consigliata se l'app è già sul telefono**: usare la chiave di debug del PC che ha compilato l'APK installato (`%USERPROFILE%\.android\debug.keystore`). Così gli APK delle Release aggiornano l'app esistente senza perdere i dati. Le credenziali della chiave di debug sono quelle standard di Android:
```powershell
$ks = "$env:USERPROFILE\.android\debug.keystore"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | gh secret set SIGNING_KEYSTORE_BASE64 -R andrea-ap/Scanner
gh secret set SIGNING_STORE_PASSWORD -b android -R andrea-ap/Scanner
gh secret set SIGNING_KEY_ALIAS -b androiddebugkey -R andrea-ap/Scanner
gh secret set SIGNING_KEY_PASSWORD -b android -R andrea-ap/Scanner
```
Non perdere quel file: se il PC cambia, copialo nel nuovo profilo utente, altrimenti le compilazioni locali useranno un'altra chiave.

**Opzione B, chiave nuova e dedicata** (password scelte da te): richiede di disinstallare una volta l'app attuale, quindi prima fai un backup dal menu dell'app e ripristinalo dopo.
```bash
keytool -genkeypair -v -keystore scanner-release.jks -alias scanner -keyalg RSA -keysize 2048 -validity 10000
```
Poi imposta i quattro segreti come sopra con il nuovo file e le tue password. Conserva `scanner-release.jks` in un posto sicuro fuori dal repository (i file `*.jks` e `*.keystore` sono già ignorati da git): senza, non potrai più pubblicare aggiornamenti installabili sopra quelli esistenti.

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
| `Reminder.kt` | promemoria: allarmi, notifica, riprogrammazione al riavvio |
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
