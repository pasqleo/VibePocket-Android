package it.vibepocket

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:Activity() {
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 private lateinit var files:ModelFiles
 private lateinit var jobStore:JobStore
 private val handler = Handler(Looper.getMainLooper())
 private var lastScreenState = ""
 private lateinit var progress:ProgressBar
 private lateinit var resumeButton:Button
 private val poll = object:Runnable { override fun run() { refreshJob(); handler.postDelayed(this,800L) } }
 private val recorder=Recorder()
 private val bg=Color.rgb(12,18,33)
 private val panel=Color.rgb(28,40,62)
 private val fg=Color.rgb(239,246,255)
 private val accent=Color.rgb(106,231,196)
 private lateinit var modelMenu:Spinner
 private lateinit var languageMenu:Spinner
 private lateinit var status:TextView
 private lateinit var modelStatus:TextView
 private lateinit var result:TextView
 private lateinit var audioStatus:TextView
 private lateinit var actions:LinearLayout
 private lateinit var actionButton:Button
 private var chosenAudio:File?=null
 private var sourceName="registrazione"
 private var busy=false
 private var pendingExport="txt"
 private var lastSegments:List<SavedSegment> = emptyList()
 private val langs=listOf("Automatico","Italiano","English","Español","Français","Deutsch","Português")
 private val codes=listOf("auto","it","en","es","fr","de","pt")
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=bg;window.navigationBarColor=bg
  files=ModelFiles(this)
  jobStore=JobStore(this)
  val scroll=ScrollView(this).apply{setBackgroundColor(bg)}
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,26,20,40)}
  scroll.addView(root);setContentView(scroll)
  root.addView(text("VIBE POCKET",28,accent))
  root.addView(text("Trascrizione Whisper locale • Android",14,fg))
  root.addView(text("1  MODELLO AI",19,accent))
  root.addView(text("Whisper da Tiny a Large v3. Per il telefono consigliato Large v3 Turbo Q5.",13,fg))
  modelMenu=Spinner(this)
  modelMenu.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,ModelCatalog.all.map{it.label})
  root.addView(modelMenu)
  modelStatus=text("",13,fg);root.addView(modelStatus)
  root.addView(button("Scarica il modello selezionato"){downloadModel()})
  root.addView(button("Importa modello GGML .bin"){selectFile(105,"application/octet-stream")})
  root.addView(button("Elimina il modello selezionato"){deleteModel()})
  root.addView(text("2  REGISTRA O IMPORTA",19,accent))
  actionButton=button("● Avvia registrazione"){recordOrStop()}
  root.addView(actionButton)
  root.addView(button("Importa M4A, WAV, MP3, FLAC e audio da video"){selectFile(101,"audio/*")})
  audioStatus=text("Nessun audio selezionato",13,fg);root.addView(audioStatus)
  root.addView(text("LINGUA",14,accent))
  languageMenu=Spinner(this)
  languageMenu.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,langs)
  root.addView(languageMenu)
  root.addView(button("✦ AVVIA NUOVA TRASCRIZIONE"){transcribe()})
  resumeButton=button("↻ RIPRENDI DOPO INTERRUZIONE"){resumeTranscription()}
  root.addView(resumeButton)
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0 }
  root.addView(progress)
  root.addView(text("3  RISULTATO",19,accent))
  status=text("Pronto",13,fg);root.addView(status)
  result=text("Il testo comparirà qui.",16,fg);result.setTextIsSelectable(true);result.setPadding(12,12,12,12)
  result.setBackgroundColor(panel);root.addView(result)
  root.addView(button("Esporta TXT"){export("txt")})
  root.addView(button("Esporta SRT"){export("srt")})
  root.addView(button("Esporta VTT"){export("vtt")})
  root.addView(button("Condividi testo"){val i=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,result.text.toString())};startActivity(Intent.createChooser(i,"Condividi"))})
  root.addView(text("PRIVACY: registrazioni e trascrizioni restano sul telefono. Internet solo per scaricare i modelli.",12,fg))
  modelMenu.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
   override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
   override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){updateModel()}
  }
  val saved=getPreferences(Context.MODE_PRIVATE).getInt("model",6).coerceIn(0,ModelCatalog.all.lastIndex)
  modelMenu.setSelection(saved)
  updateModel()
  refreshJob()
  handler.postDelayed(poll,800L)
 }
 private fun text(s:String,size:Int,color:Int)=TextView(this).apply{
  text=s;textSize=size.toFloat();setTextColor(color);setPadding(4,14,4,14)
 }
 private fun button(s:String,fn:()->Unit)=Button(this).apply{
  text=s;isAllCaps=false;setTextColor(fg);setBackgroundColor(panel);setOnClickListener{fn()}
 }
 private fun chosen()=ModelCatalog.all[modelMenu.selectedItemPosition.coerceAtLeast(0)]
 private fun updateModel(){
  val m=chosen()
  getPreferences(Context.MODE_PRIVATE).edit().putInt("model",modelMenu.selectedItemPosition).apply()
  modelStatus.text=if(files.available(m)) "✓ Installato • ${m.label}" else "Da scaricare • ${m.label}"
 }
 private fun notice(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
 private fun work(title:String,fn:suspend ()->Unit){
  if(busy){notice("È già in corso un'operazione");return}
  busy=true;status.text=title
  scope.launch {
   try{fn()}catch(e:Exception){status.text="Errore: ${e.message}";notice(e.message?:"Errore")}
   finally{busy=false;updateModel()}
  }
 }
 private fun downloadModel(){
  val m=chosen()
  if(m.approxMiB==0){notice("Per un modello personale usa Importa");return}
  work("Scaricamento di ${m.label}…"){
   withContext(Dispatchers.IO){files.download(m){pct->runOnUiThread{status.text="Download ${m.label}: $pct%"}}}
   status.text="Modello pronto per la trascrizione"
  }
 }
 private fun deleteModel(){
  if(busy)return
  AlertDialog.Builder(this).setTitle("Eliminare ${chosen().label}?")
   .setMessage("Le trascrizioni non saranno eliminate.")
   .setPositiveButton("Elimina"){_,_->files.delete(chosen());updateModel()}
   .setNegativeButton("Annulla",null).show()
 }
 private fun selectFile(code:Int,mime:String){
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=mime}
  startActivityForResult(i,code)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
  super.onActivityResult(requestCode,resultCode,data)
  if(resultCode!=RESULT_OK||data?.data==null)return
  val uri=data.data!!
  when(requestCode){
   101->importAudio(uri)
   105->importModel(uri)
   109->saveExport(uri)
  }
 }
 private fun importAudio(uri:Uri){
  work("Importazione audio…"){
   val f=withContext(Dispatchers.IO){
    contentResolver.openInputStream(uri)!!.use { jobStore.importSource(it, uri.lastPathSegment ?: "audio") }
   }
   chosenAudio=f;sourceName=uri.lastPathSegment?:"audio";audioStatus.text="File selezionato: $sourceName"
   status.text="Audio importato"
  }
 }
 private fun importModel(uri:Uri){
  work("Importazione modello GGML…"){
   withContext(Dispatchers.IO){contentResolver.openInputStream(uri)!!.use{files.import(it,ModelCatalog.all.last())}}
   modelMenu.setSelection(ModelCatalog.all.lastIndex)
   status.text="Modello personalizzato importato"
  }
 }
 private fun recordOrStop(){
  if(recorder.recording){
   recorder.stop();chosenAudio=recorder.output;sourceName="registrazione"
   audioStatus.text="Registrazione pronta: ${chosenAudio?.name}";actionButton.text="● Avvia registrazione";status.text="Registrazione completata"
  }else{
   if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),400);return
   }
   try{
    val target=File(jobStore.root,"vibepocket_record_${System.currentTimeMillis()}.wav")
    recorder.start(target);actionButton.text="■ Termina registrazione"
    status.text="Registrazione in corso…"
   }catch(e:Exception){notice("Microfono: ${e.message}")}
  }
 }
 private fun transcribe(){
  if(busy){notice("Termina prima il download o l'importazione");return}
  if(recorder.recording){notice("Termina prima la registrazione");return}
  val audio=chosenAudio
  if(audio==null || !audio.exists()){notice("Seleziona o registra un audio");return}
  val existing=jobStore.load()
  if(existing!=null && existing.phase in listOf("converting","transcribing")){
   AlertDialog.Builder(this).setTitle("Trascrizione ancora in corso o interrotta")
    .setMessage("Prima di avviare un nuovo lavoro, riprendi quello salvato. Non eliminiamo automaticamente il lavoro parziale.")
    .setPositiveButton("Riprendi"){_,_->resumeTranscription()}
    .setNegativeButton("Annulla",null).show()
   return
  }
  val m=chosen()
  if(!files.available(m)){notice("Scarica prima il modello selezionato");return}
  val am=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  val mem=ActivityManager.MemoryInfo();am.getMemoryInfo(mem)
  val launch= {
   try {
    jobStore.newJob(audio, sourceName, m, codes[languageMenu.selectedItemPosition])
    refreshJob()
    resumeTranscription()
   }catch(e:Exception){notice(e.message?:"Impossibile creare il lavoro")}
  }
  if(mem.totalMem/1024/1024/1024 < m.ramGiB) {
   AlertDialog.Builder(this).setTitle("Modello molto pesante")
    .setMessage("Il modello può superare la RAM disponibile. Se il sistema chiude l'app, i blocchi già completati saranno recuperabili.")
    .setPositiveButton("Prova"){_,_->launch()}
    .setNegativeButton("Annulla",null).show()
  } else launch()
 }
 private fun resumeTranscription(){
  val job=jobStore.load()
  if(job==null){notice("Non c'è una trascrizione da riprendere");return}
  if(job.phase=="completed"){notice("Trascrizione già completata");return}
  if(!File(job.source).exists()){notice("Audio originale mancante");return}
  val model=ModelCatalog.all.firstOrNull{it.name==job.modelName}
  if(model==null || !files.available(model)){
   notice("Riscarica il modello usato per questo lavoro: "+job.modelName);return
  }
  try {
   startForegroundService(Intent(this,TranscriptionService::class.java))
   status.text="Elaborazione avviata; la percentuale si aggiorna dopo ogni blocco"
  } catch(e:Exception){notice("Avvio servizio fallito: "+e.message)}
 }
 private fun refreshJob(){
  if(!::jobStore.isInitialized || !::result.isInitialized)return
  val job=jobStore.load()
  if(job==null){
   if(::resumeButton.isInitialized)resumeButton.isEnabled=false
   return
  }
  if(::progress.isInitialized)progress.progress=job.percent
  if(::resumeButton.isInitialized)resumeButton.isEnabled=(job.phase!="completed")
  val label=when(job.phase){
   "ready" -> "Audio pronto per la trascrizione"
   "converting" -> "Preparazione audio M4A/altro: "+job.conversionPercent+"%"
   "transcribing" -> "Trascrizione "+job.percent+"% • "+job.completedChunks+"/"+job.totalChunks+" blocchi"
   "completed" -> "Completata: 100%"
   "failed" -> "Interrotta al "+job.percent+"%: "+job.error+". Premi Riprendi."
   else -> "Trascrizione: "+job.percent+"%"
  }
  val snapshot=job.id+":"+job.completedChunks+":"+job.phase+":"+job.conversionPercent+":"+job.error
  if(snapshot!=lastScreenState){
   lastScreenState=snapshot
   if(!busy)status.text=label
   if(job.segments.isNotEmpty()){
    result.text=job.transcript
    lastSegments=job.segments
   }else if(job.phase!="completed" && !busy)result.text="I primi segmenti compariranno qui."
   if(job.source == chosenAudio?.absolutePath || chosenAudio==null){
    audioStatus.text="Lavoro salvato: "+job.originalName
   }
  }
 }
 private fun export(format:String){
  if(lastSegments.isEmpty() && result.text.toString().startsWith("Il testo")){notice("Trascrivi prima un audio");return}
  pendingExport=format
  val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
   addCategory(Intent.CATEGORY_OPENABLE);type="text/plain"
   putExtra(Intent.EXTRA_TITLE,"VibePocket_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.ITALY).format(Date())}.$format")
  }
  startActivityForResult(i,109)
 }
 private fun clock(ms:Long,sep:Char):String{
  val hrs=ms/3600000;val mins=ms/60000%60;val sec=ms/1000%60;val milli=ms%1000
  return "%02d:%02d:%02d%c%03d".format(Locale.US,hrs,mins,sec,sep,milli)
 }
 private fun saveExport(uri:Uri){
  val format=pendingExport
  val out=when(format){
   "srt"->lastSegments.mapIndexed{i,s->"${i+1}\n${clock(s.startMs,',')} --> ${clock(s.endMs,',')}\n${s.text.trim()}\n"}.joinToString("\n")
   "vtt"->"WEBVTT\n\n"+lastSegments.joinToString("\n\n"){"${clock(it.startMs,'.')} --> ${clock(it.endMs,'.')}\n${it.text.trim()}"}
   else->result.text.toString()
  }
  try{contentResolver.openOutputStream(uri)?.use{it.write(out.toByteArray(Charsets.UTF_8))};notice("File esportato")}
  catch(e:Exception){notice("Esportazione fallita: ${e.message}")}
 }
 override fun onDestroy(){handler.removeCallbacks(poll);scope.cancel();if(recorder.recording)recorder.stop();super.onDestroy()}
}
