# Vibe Pocket 0.2 – Whisper Advanced (Android)
Progetto Android Kotlin **non ufficiale** basato su whisper.cpp via AAR Android precompilato.

**Modelli:** Tiny, Base, Small, Medium (completo/Q5/Q8), Large v3 Turbo (completo/Q5/Q8), Large v3 (completo/Q5), Large v2 (completo/Q5), Large v1, GGML personale. I modelli vengono scaricati dal repository ggml di whisper.cpp su Hugging Face, **non** inclusi nell'APK.

**Funzioni di questa build:** registrazione WAV locale, importazione WAV/MP3/FLAC, scelta lingua, trascrizione offline, salvataggio TXT/SRT/VTT, condivisione testo, cancellazione/importazione di modelli GGML. La registrazione/decodifica audio dipende dai formati supportati dalla libreria nativa. Non supporta Parakeet, Nemotron, diarizzazione né riassunti. Non è una conversione ufficiale dell'app desktop Vibe.

**APK:** aprire la scheda Actions, selezionare il workflow "Compila Vibe Pocket APK" e scaricare l'artifact "VibePocket-Android-APK". Android ARM64, min. Android 8.0. APK debug, installabile ma non da pubblicare su Play Store.

**Avvertenza:** i modelli da 1–3 GB potrebbero non caricarsi su telefoni con RAM limitata; il sistema può chiudere l'app. Large v3 Turbo Q5 è l'opzione pratica raccomandata.
