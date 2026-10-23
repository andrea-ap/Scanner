# Scanner – note del progetto

App Android personale per scattare/scansionare foto e PDF, organizzarli in **sessioni** con note, cercarli e condividerli (WhatsApp, PDF unico, altre app).
Lingua dell'interfaccia: italiano. Package `com.scanner.app`, minSdk 26, targetSdk 35.

## Stack
Kotlin + Jetpack Compose (Material3, tema scuro sempre attivo), Room (SQLite), CameraX, ML Kit (OCR offline + Document Scanner), Coil, Navigation Compose, Biometric.
Nessun server: tutto resta sul telefono.

## Build e installazione (Windows)
- Build: `./gradlew assembleDebug --no-daemon -q` → `app/build/outputs/apk/debug/app-debug.apk`
- Gradle non è nel PATH: si usa il wrapper (8.13). JDK 17 e Android SDK (platform 34/35) sono già installati; `local.properties` punta all'SDK.
- Installazione sul telefono (USB debug attivo): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  - Su Xiaomi/POCO serve "Installa via USB" attivo, altrimenti `INSTALL_FAILED_USER_RESTRICTED`.
  - Usare sempre `-r` (aggiorna): disinstallare cancella tutti i dati dell'app.
  - Se compare `INSTALL_PARSE_FAILED_NO_CERTIFICATES` ("SHA-256 digest of contents did not verify"): non è un problema di firma, il file arriva alterato via USB (APK di 62 MB in streaming). Rimedio: `adb install --no-streaming -r ...`, oppure cancellare l'APK e ricompilare. Idea per evitarlo: `ndk { abiFilters += "arm64-v8a" }` in `app/build.gradle.kts` per ridurre l'APK (non ancora fatto).
- **GitHub**: https://github.com/andrea-ap/Scanner (ramo `main`, README con istruzioni di compilazione). `gh` (GitHub CLI 2.102.1) è installato e l'utente ha fatto il login come `andrea-ap`; fuori da un terminale nuovo non è nel PATH: `"/c/Program Files/GitHub CLI/gh.exe"`. **Il 22/10/2026 il repository è stato azzerato su richiesta dell'utente**: storia ridotta a un solo commit su `main`, un unico ramo, vecchia Release 1.0 (fatta a mano) e tag `V1.0` cancellati. La **Release v1.1** (23/10/2026, etichetta, filtri, ricerca tollerante, Condividi verso Scanner, promemoria) e la **Release v1.0** (tag `v1.0`, "Scanner 1.0") è stata ricreata dalla CI con l'APK firmato (`Scanner-1.0.apk`). Le versioni future si pubblicano con la CI: `git tag vX.Y` + `git push origin vX.Y` (solo su richiesta dell'utente; il tag deve puntare a un commit già pubblicato). L'APK di debug locale è in `release/Scanner-1.0-debug.apk` (cartella ignorata da git). Gli screenshot `s_*.png`/`screen*.png` non vanno mai pubblicati: mostrano documenti personali.
- **CI/Release automatiche**: `.github/workflows/release.yml` compila l'APK firmato su GitHub Actions e, con un tag `vX.Y`, lo pubblica come asset della Release. La firma usa i segreti `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (non più nel README). **Impostati il 22/10/2026** con il `debug.keystore` del PC (`%USERPROFILE%\.android\`, alias `androiddebugkey`): così l'APK delle Release aggiorna l'app già installata senza perdere i dati. Se il PC cambia, copiare quel file nel nuovo profilo.
- Per rifare i segreti: `base64 -w0 ~/.android/debug.keystore | gh secret set SIGNING_KEYSTORE_BASE64 -R andrea-ap/Scanner`; poi `gh secret set SIGNING_STORE_PASSWORD -b android`, `SIGNING_KEY_ALIAS -b androiddebugkey`, `SIGNING_KEY_PASSWORD -b android`. Senza la stessa chiave Android rifiuta l'aggiornamento e bisogna disinstallare (si perdono i dati: prima backup).
- **Norton 360** segnala l'APK debug come "app sospetta" (falso allarme: non firmato, usa la camera). Rispondere Annulla, mai Disinstalla.
- Sui Xiaomi il risparmio batteria/avvio automatico può bloccare i promemoria: app su "Nessuna restrizione" e avvio automatico attivo.
- Il telefono si blocca spesso durante gli screenshot (`adb exec-out screencap -p`): sbloccarlo prima.

## Struttura (`app/src/main/java/com/scanner/app/`)
| File | Contenuto |
|---|---|
| `Data.kt` | Room: `Session`, `Photo` (anche i PDF), `ScannerDao`, `AppDatabase` (db `scanner.db`, versione 3: migrazione 1→2 aggiunge `reminderAt`, 2→3 aggiunge `category`) |
| `MainViewModel.kt` | stato, creazione sessioni, aggiunta foto/PDF, OCR, backup/ripristino |
| `HomeScreen.kt` | elenco sessioni, etichette, ricerca con frase evidenziata, ordinamento, vista compatta, swipe per eliminare, menu |
| `SessionScreen.kt` | dettaglio sessione: note, Scansiona/Scatta/Foto/PDF, griglia allegati, condivisione |
| `ViewerScreen.kt` | lettore a schermo intero: swipe tra allegati, zoom (pizzico, doppio tocco, scorrimento in tutte le direzioni), salva in Galleria, condividi |
| `CameraScreen.kt` | CameraX; le foto scattate vanno anche nella Galleria |
| `Scan.kt` | scanner documenti ML Kit (ritaglio bordi, filtri) |
| `PdfExport.kt`, `PdfUtil.kt` | PDF unico della sessione; rendering pagine PDF |
| `Share.kt` | WhatsApp, altre app, PDF, singolo allegato, apri PDF |
| `Backup.kt` | backup zip in Download, ripristino, copia in Galleria |
| `Search.kt` | ricerca tollerante: `foldText` (senza accenti, stessa lunghezza), `findWord` (1 errore per parole di almeno 5 lettere), `matchesAll` |
| `Reminder.kt` | promemoria: allarmi, notifica, riprogrammazione al riavvio |
| `AppPrefs.kt` | impostazioni: ordinamento, vista compatta, blocco app |
| `MainActivity.kt` | navigazione, blocco con impronta/PIN |
| `Theme.kt` | palette scura verde acqua + giallo |

## Dati
- Foto e PDF: `filesDir/photos/` (privati, non visibili in Galleria). `IMG_*.jpg`, `DOC_*.pdf` + `DOC_*.pdf.png` (anteprima prima pagina).
- Un allegato è PDF se il nome finisce in `.pdf` (`Photo.isPdf`); l'anteprima è `Photo.displayPath`.
- OCR in background su foto e pagine dei PDF (fino a `MAX_OCR_PAGES` = 40) → `Photo.ocrText`. La ricerca è in memoria (`MainViewModel.sessions` = combine di sessioni, foto e testo cercato), non più SQL `LIKE`: vale su nome, etichetta, note e testo OCR. Tocco lungo su un allegato: "Rileggi testo (OCR)" (`rerunOcr`), "Vedi testo letto" (mostra l'OCR salvato, utile se la ricerca non trova una scritta in una foto) o "Elimina".
- Il **titolo della sessione fa da etichetta**, a meno che si imposti `Session.category` (riga "Etichetta" nella sessione, facoltativa): `labelOf(session)` = categoria se non vuota, altrimenti titolo. Sessioni con la stessa etichetta (senza distinguere maiuscole) formano un gruppo; con categoria impostata la card mostra l'etichetta nel chip e il titolo nella riga sotto. Sotto la ricerca compaiono solo le etichette ripetute (almeno 2 sessioni), con il numero.
- Se non c'è una nota, la card mostra il nome di default "Sessione GG/MM · HH:mm" (da data di creazione).
- Eliminare una sessione con swipe la nasconde subito e la cancella solo dopo il timeout della snackbar "Annulla".
- Backup: `Scanner_backup_AAAAMMGG_HHMM.zip` in Download (`backup.json` + `files/`). Ripristino non distruttivo: salta le sessioni con stesso nome e data di creazione.
- Backup/Galleria richiedono Android 10+.

## Decisioni di design
- Intestazione verde scuro con titolo e contatore in giallo; "+" giallo piccolo (44 dp) che si nasconde scorrendo verso il basso.
- Griglia allegati (`SessionScreen`): ogni PDF mostra in basso a sinistra il badge "PDF · N pag." e sotto la prima riga di testo letta dall'OCR (se disponibile).
- Vista compatta (`SessionRow`): i PDF sono un badge rosso (C62828) con scritta bianca "N PDF", solo per i PDF; le foto restano testo ("N foto").
- Ricerca: se la parola è trovata in una foto/PDF, toccando il risultato si apre la sessione e subito il lettore su quell'allegato (`Hit.photoId`); "indietro" torna alla sessione. Se è nella nota, si apre solo la sessione.
- Peso in MB: ogni allegato nella griglia ha un badge in alto a destra (`formatSize`, in `PdfExport.kt`). Il menu Condividi mostra il peso esatto del "PDF unico", preparato in background (`produceState` con debounce 800 ms in `SessionScreen`). Toccando "PDF unico" si apre una finestra con due scelte e i rispettivi pesi, "Originale" e "Compresso" (`buildSessionPdf(..., compressed = true)`, file `<nome>_compresso.pdf`); si condivide solo dopo la scelta dell'utente.
- Promemoria (per sessione, facoltativo): riga "Promemoria" nella schermata sessione + snackbar "Vuoi un promemoria?" dopo l'aggiunta di allegati (`SessionScreen`). Scelta rapida (domani 9:00, tra una settimana) o data+ora con selettori. `Session.reminderAt`, allarme con `AlarmManager` (preciso se Android lo permette), `Reminder.kt` (`ReminderReceiver` mostra la notifica, `BootReceiver` riprogramma dopo il riavvio). Il tocco sulla notifica apre la sessione (`EXTRA_SESSION_ID`). Dopo lo scatto il promemoria si azzera. Backup e ripristino lo includono; eliminare la sessione lo annulla. Campanella nell'elenco della home.
- Filtri nella home: chip fissi "Con promemoria" e "Con PDF" (combinabili con il filtro per etichetta), `FilterPill` in `HomeScreen.kt`.
- "Condividi → Scanner": `MainActivity` (singleTop) accetta `SEND`/`SEND_MULTIPLE` di `image/*` e `application/pdf`; `ShareTargetDialog` chiede in quale sessione aggiungere (o nuova) e importa con `addPhotoFromUri`/`addPdfFromUri`.
- Card grandi con foto, fascia scura in basso per la leggibilità, chip in basso a destra (N PDF in rosso, N foto).
- Blocco app: richiesto all'apertura e dopo più di 30 s in background (camera/scanner/selettori file non lo riattivano). Richiede un blocco schermo sul telefono.
- Condivisione WhatsApp: `ACTION_SEND_MULTIPLE` con pacchetto `com.whatsapp`; WhatsApp può ignorare il testo quando le foto sono più di una.

## Stato (ultima sessione, 22/10/2026)
Compilata e installata sul telefono (ultima versione = quella in `release/`). Viste e verificate con screenshot: home, vista compatta con badge PDF rosso, menu "+", menu a tre puntini, schermata sessione con anteprima PDF (badge pagine + testo), apertura scanner Google, ricerca con testo evidenziato, tocco sul risultato che apre direttamente la foto trovata nel lettore (provato con "sim" in "bonifici"; confermato dall'utente), pesi in MB sugli allegati, finestra "PDF unico" con Originale/Compresso (compresso a 1200 px: confermato dall'utente "va bene").
Promemoria provato sul telefono il 22/10: notifica arrivata, tocco apre la sessione, promemoria azzerato dopo lo scatto; l'utente ha confermato anche il funzionamento dopo il riavvio del telefono e con backup/ripristino. Campanella verificata in vista compatta e nelle schede grandi (chip arancione con testo scuro).
Provati il 23/10 sul telefono (screenshot): ricerca tollerante ("partcella" trova "particella"), filtri "Con promemoria"/"Con PDF", etichetta (impostata e tolta), finestra "Aggiungi file a…" (intent SEND simulato con adb), migrazione database 2→3 senza perdere le sessioni.
**Non ancora provati a mano**: import reale di una foto/PDF da "Condividi" di un'altra app, "Rileggi testo" su un PDF lungo, backup/ripristino con etichetta; scansione vera con ritaglio/filtri, swipe per eliminare con Annulla, swipe tra gli allegati nel lettore, salva in Galleria dal lettore, blocco con impronta/PIN, ripristino da backup; "indietro" dal lettore aperto da una ricerca e risultato trovato nella nota; condivisione reale del PDF compresso su WhatsApp.

## Idee non ancora fatte
- Comprimere ancora di più il PDF unico (oggi, 7 foto: originale 11,9 MB, compresso 4,5 MB a 1200 px; a 800 px pesava 2,1 MB ma il testo risultava sbiadito): `PdfDocument` non ricomprime in JPEG, quindi si agisce solo sulla risoluzione (`PDF_SMALL_PHOTO` e `PDF_SMALL_PAGE` = 1200 px in `PdfExport.kt`). `loadBitmap` ora scala esattamente al lato massimo (prima riduceva solo a potenze di 2 e le foto grandi restavano enormi).
- Backup automatico settimanale in Download.
- Anteprime dei PDF ancora più ricche (strisce di miniature delle pagine, "variante C" mostrata come mockup).

## File di prova da ignorare
`screen*.png`, `s_*.png` in questa cartella sono screenshot del telefono usati per controllare la grafica.
