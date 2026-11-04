package it.vibepocket

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class Model(val label:String,val name:String,val approxMiB:Int,val minBytes:Long,val ramGiB:Int=4) {
 val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$name"
}
object ModelCatalog {
 val all = listOf(
  Model("Tiny · 75 MB","ggml-tiny.bin",75,60_000_000,2),
  Model("Base · 142 MB","ggml-base.bin",142,120_000_000,3),
  Model("Small · 466 MB","ggml-small.bin",466,420_000_000),
  Model("Medium Q5 · 514 MB","ggml-medium-q5_0.bin",514,450_000_000),
  Model("Medium Q8 · 785 MB","ggml-medium-q8_0.bin",785,690_000_000,5),
  Model("Medium completo · 1,5 GB","ggml-medium.bin",1550,1_300_000_000,6),
  Model("Large v3 Turbo Q5 · 547 MB","ggml-large-v3-turbo-q5_0.bin",547,475_000_000),
  Model("Large v3 Turbo Q8 · 834 MB","ggml-large-v3-turbo-q8_0.bin",834,730_000_000,5),
  Model("Large v3 Turbo completo · 1,5 GB","ggml-large-v3-turbo.bin",1550,1_300_000_000,6),
  Model("Large v3 Q5 · 1,1 GB","ggml-large-v3-q5_0.bin",1100,950_000_000,6),
  Model("Large v3 completo · 2,9 GB","ggml-large-v3.bin",3100,2_600_000_000,8),
  Model("Large v2 Q5 · 1,1 GB","ggml-large-v2-q5_0.bin",1100,950_000_000,6),
  Model("Large v2 completo · 2,9 GB","ggml-large-v2.bin",3100,2_600_000_000,8),
  Model("Large v1 completo · 2,9 GB","ggml-large-v1.bin",3100,2_600_000_000,8),
  Model("Modello GGML importato","ggml-imported.bin",0,10_000_000,6)
 )
}
class ModelFiles(private val ctx:Context) {
 private val folder = File(ctx.filesDir,"models").apply { mkdirs() }
 fun file(m:Model) = File(folder,m.name)
 fun available(m:Model) = file(m).let{it.isFile && it.length()>=m.minBytes}
 fun delete(m:Model) {
  file(m).delete()
  File(folder,m.name+".part").delete()
 }
 fun import(input:InputStream, model:Model) {
  val partial=File(folder,model.name+".part")
  partial.outputStream().use { output-> input.copyTo(output,256*1024) }
  require(partial.length()>=model.minBytes) { "File GGML troppo piccolo" }
  check(partial.renameTo(file(model))) { "Impossibile salvare il modello" }
 }
 fun download(m:Model, progress:(Int)->Unit) {
  require(m.approxMiB>0)
  if(available(m)) return
  val partial=File(folder,m.name+".part")
  if(folder.usableSpace < (m.approxMiB.toLong()*1024*1024-partial.length()).coerceAtLeast(0)+256L*1024*1024)
   error("Spazio insufficiente: libera memoria sul telefono")
  var start=partial.length()
  var connection:HttpURLConnection?=null
  try {
   connection=(URL(m.url).openConnection() as HttpURLConnection).apply {
    connectTimeout=30000;readTimeout=90000;instanceFollowRedirects=true
    if(start>0) setRequestProperty("Range","bytes=$start-")
   }
   val code=connection.responseCode
   if(code==416) { partial.delete(); error("Download parziale non valido, riprova") }
   check(code in 200..299) {"Errore download HTTP $code"}
   val append=code==206
   if(!append) {start=0}
   val size=connection.contentLengthLong
   val total=if(size>0) start+size else -1L
   connection.inputStream.use{input ->
    FileOutputStream(partial,append).use{output ->
     val buffer=ByteArray(262144)
     var done=start
     while(true) {
      val n=input.read(buffer)
      if(n<0) break
      if(n==0) continue
      output.write(buffer,0,n)
      done+=n
      if(total>0) progress(((done*100.0/total).toInt()).coerceIn(0,100))
     }
    }
   }
   check(total<=0 || partial.length()==total) {"Download interrotto: riprova per riprenderlo"}
   check(partial.length()>=m.minBytes) {"File del modello incompleto"}
   file(m).delete()
   check(partial.renameTo(file(m))) {"Impossibile completare il download"}
  } finally {connection?.disconnect()}
 }
}
