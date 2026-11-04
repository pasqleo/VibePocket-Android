package it.vibepocket

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

class Recorder {
 @Volatile private var running=false
 private var worker:Thread?=null
 private var source:AudioRecord?=null
 var output:File?=null
  private set
 val recording get()=running
 fun start(file:File) {
  check(!running)
  val rate=16000
  val size=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
  val input=AudioRecord(MediaRecorder.AudioSource.MIC,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2)
  check(input.state==AudioRecord.STATE_INITIALIZED) {"Microfono non disponibile"}
  source=input;output=file
  val wav=RandomAccessFile(file,"rw")
  wav.setLength(0);wav.write(ByteArray(44))
  input.startRecording();running=true
  worker=thread(name="vibe-record",isDaemon=true) {
   var bytes=0L
   val b=ByteArray(size)
   try{
    while(running){
     val n=input.read(b,0,b.size)
     if(n>0){wav.write(b,0,n);bytes+=n}
    }
   } finally {
    writeHeader(wav,bytes)
    wav.close()
    try{input.stop()}catch(_:Exception){}
    input.release();source=null
   }
  }
 }
 fun stop() {running=false;worker?.join(5000);worker=null}
 private fun writeHeader(f:RandomAccessFile,n:Long) {
  fun le4(x:Long){for(i in 0..3)f.write(((x shr (8*i))and 255).toInt())}
  fun le2(x:Int){f.write(x and 255);f.write((x shr 8)and 255)}
  f.seek(0)
  f.writeBytes("RIFF");le4(n+36);f.writeBytes("WAVEfmt ");le4(16);le2(1);le2(1)
  le4(16000);le4(32000);le2(2);le2(16);f.writeBytes("data");le4(n)
 }
}
