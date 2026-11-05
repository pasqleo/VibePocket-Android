# Vibe Pocket 0.3 — M4A + progresso + ripristino dopo crash
Versione Android Kotlin non ufficiale di trascrizione Whisper offline.

## Novità
- Importazione **M4A (AAC), MP3, FLAC e WAV**, usando MediaExtractor + MediaCodec per convertire localmente l'audio in WAV mono PCM16 16 kHz prima di trascriverlo.
- **Avanzamento reale** in percentuale sulla durata audio: divisione in blocchi da 30 secondi. La percentuale cresce al termine di ogni blocco: non è una stima dei minuti residui. Il pre-processing M4A ha percentuale separata.
- **Checkpoint atomico dopo ogni blocco** con testo e timestamp; se Android termina il processo, riaprire l'app e premere **RIPRENDI DOPO INTERRUZIONE**. Il blocco in elaborazione al momento dell'arresto verrà ricalcolato.
- Servizio foreground con notifica per continuare anche quando la schermata viene chiusa; i limiti dei servizi foreground Android 15+ possono interrompere sessioni molto lunghe. Non garantisce sopravvivenza a "Forza arresto", riavvio dispositivo o cancellazione dati.
- Audio importati e WAV di lavoro conservati nello spazio privato dell'app fino alla sostituzione del lavoro. Il download dei modelli Whisper è separato.

## Modelli
Tiny, Base, Small, Medium Q5/Q8/full, Large v3 Turbo Q5/Q8/full, Large v3 Q5/full, Large v2 Q5/full, Large v1 e GGML importato. Il telefono deve avere RAM sufficiente per caricare i modelli grandi.

## Installazione
Nel repository: **Actions → Compila Vibe Pocket APK → ultima build verde → Artifacts → VibePocket-Android-APK**.
L'APK è una build debug per Android ARM64 (8.0+). GitHub Actions può usare una chiave debug diversa tra compilazioni: per aggiornare un APK esistente Android potrebbe richiedere disinstallazione, che elimina anche i modelli e le trascrizioni salvati nell'app. Fare un backup esportando il testo prima di disinstallare. Non installare la build come se fosse un'app di produzione.

## Limiti
- I blocchi consecutivi di 30 secondi possono spezzare parole/frasi ai confini e non garantiscono continuità perfetta. I timestamp riflettono la posizione dei blocchi.
- Il recupero dei checkpoint richiede che il file sorgente, il modello e i dati privati dell'app siano ancora presenti.
- Alcuni codec M4A poco comuni possono non essere supportati dal decoder Android del dispositivo.
- Con modelli molto grandi, il sistema può uccidere il processo per memoria insufficiente anche ripetutamente.
- Non inclusi Parakeet, Nemotron, diarizzazione o riassunto.
