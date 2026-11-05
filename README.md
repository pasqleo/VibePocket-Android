# Vibe Pocket 0.4 — diagnosi e ottimizzazione CPU

App Android indipendente/non ufficiale che trascrive audio offline con whisper.cpp; sorgenti e workflow di compilazione GitHub Actions.

## Novità 0.4

- **Diagnostica in tempo reale**: fase di preparazione M4A, caricamento modello, numero del blocco corrente, secondi trascorsi sul blocco, ultimo tempo misurato e tempo di caricamento del modello.
- **Stima residua**, dopo almeno un blocco completato, calcolata dalla media effettiva; non una promessa di tempo certo.
- **Segnale di attività del servizio** registrato ogni 10 secondi, per distinguere un processo ancora in esecuzione da una possibile interruzione. Il segnale non dimostra che il decoder Whisper stia avanzando internamente.
- **Selettore thread**: 4, 6 o 8 thread CPU; impostazione applicata ai nuovi lavori, non cambia un job in corso o già salvato.
- **Benchmark CPU facoltativo** su un campione di 10 secondi della trascrizione completata, confronta 4/6/8 thread con lo stesso modello. Memorizza la scelta consigliata per le trascrizioni successive, solo quando l'utente conferma.
- Lingua predefinita per i nuovi lavori: **italiano**, selezione modificabile.
- Rimangono M4A (AAC), WAV, MP3, FLAC, registrazione, download dei modelli Whisper Tiny/Base/Small/Medium/Large, trascrizione a segmenti da 30 secondi, salvataggio checkpoint per ripresa dopo crash, export TXT/SRT/VTT.

## GPU — stato reale

**La versione 0.4 continua a usare la CPU (whisper.cpp / NEON): NON utilizza la GPU**. Il motore AAR `dev.ffmpegkit-maintained:whisper-android:1.0.0` è distribuito con `GGML_NEON=ON` e senza Vulkan; non può essere accelerato con un interruttore nell'interfaccia. Per un backend Vulkan su Mali occorre compilare una libreria nativa NDK Vulkan, testarla sul modello Large-v3 Turbo Q5 e predisporre un fallback per hardware non compatibile. La diagnostica riporta i dettagli della libreria installata, senza affermare un'accelerazione non presente.

## Installazione

In **Actions → Compila Vibe Pocket APK → ultima build riuscita → Artifacts**, scaricare `VibePocket-Android-APK`, estrarre il file `app-debug.apk` e installarlo su Android ARM64 (minSdk 26). Per aggiornare un APK debug precedente potrebbero non coincidere le firme: se Android richiede la disinstallazione, esportare prima i testi. Disinstallare cancella modelli e checkpoint. Evitare di fare benchmark durante una trascrizione in corso.

## Benchmark e prestazioni

Nel test effettuato con il dispositivo dell'utente, 4 minuti di audio Large-v3 Turbo Q5 hanno richiesto circa 28 minuti con 4 thread CPU. Non è un dato valido per tutti i dispositivi. Dopo una trascrizione completata, premere **Benchmark 4/6/8 thread**. Il benchmark può richiedere vari minuti e usa la CPU, quindi il telefono può scaldarsi. I risultati del benchmark sono indicativi: testare con condizioni termiche simili. Se 8 thread è più lento di 4, scegliere il valore minore.

## Limiti

Questa build non garantisce che i grandi modelli girino su ogni smartphone. La ripresa riparte dall'ultimo blocco completato e non dall'ultimo secondo inferito. Le parole al confine dei blocchi possono essere divise. Il benchmark è legato al ciclo di vita della schermata (non un servizio di background). Android può limitare i servizi in foreground, e la persistenza non sopravvive a disinstallazione/cancellazione dati.

Il progetto non integra Parakeet, Nemotron né la diarizzazione.
