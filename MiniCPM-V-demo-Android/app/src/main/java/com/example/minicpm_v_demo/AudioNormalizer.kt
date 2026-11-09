package com.example.minicpm_v_demo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Normalize supported audio into the canonical PCM16 mono 16 kHz WAV expected by JNI. */
object AudioNormalizer {
    const val SAMPLE_RATE = 16000

    fun normalize(context: Context, uri: Uri, output: File): Int {
        val temp = File.createTempFile("audio-source-", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取音频文件")
            return normalize(temp, output)
        } finally { temp.delete() }
    }

    fun normalize(source: File, output: File): Int {
        require(source.length() in 1..(100L * 1024 * 1024)) { "音频文件为空或超过 100 MB" }
        val pcm = if (isWave(source)) readWave(source) else decode(source)
        require(pcm.rate in 8000..192000 && pcm.channels in 1..8 && pcm.samples.isNotEmpty()) { "音频参数无效" }
        val duration = pcm.samples.size.toLong() * 1000 / pcm.rate / pcm.channels
        require(duration in 500..300000) { "参考音频时长需为 0.5 秒至 5 分钟" }
        val frames = pcm.samples.size / pcm.channels
        val count = (frames.toLong() * SAMPLE_RATE / pcm.rate).toInt()
        val mono = ShortArray(count)
        for (i in 0 until count) {
            val pos = i.toDouble() * pcm.rate / SAMPLE_RATE
            val a = pos.toInt().coerceIn(0, frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val f = (pos - a).toFloat()
            var va = 0f; var vb = 0f
            for (c in 0 until pcm.channels) {
                va += pcm.samples[a * pcm.channels + c]
                vb += pcm.samples[b * pcm.channels + c]
            }
            val value = ((va / pcm.channels) * (1 - f) + (vb / pcm.channels) * f).coerceIn(-1f, 1f)
            mono[i] = (value * 32767).roundToInt().toShort()
        }
        writeWav(output, mono)
        return count * 1000 / SAMPLE_RATE
    }

    private data class Pcm(val samples: FloatArray, val rate: Int, val channels: Int)
    private fun isWave(f: File) = RandomAccessFile(f, "r").use {
        if (it.length() < 12) false else { val h=ByteArray(12); it.readFully(h); String(h,0,4)=="RIFF" && String(h,8,4)=="WAVE" }
    }

    // RIFF chunk parser: extra LIST/JUNK/metadata chunks are skipped safely.
    private fun readWave(file: File): Pcm = RandomAccessFile(file, "r").use { r ->
        val h=ByteArray(12); r.readFully(h)
        var fmt=0; var ch=0; var rate=0; var bits=0; var dataAt=-1L; var dataSize=0L
        while (r.filePointer + 8 <= r.length()) {
            val c=ByteArray(8); r.readFully(c); val id=String(c,0,4)
            val size=(c[4].toLong() and 255) or ((c[5].toLong() and 255) shl 8) or ((c[6].toLong() and 255) shl 16) or ((c[7].toLong() and 255) shl 24)
            val start=r.filePointer; val safe=minOf(size,r.length()-start)
            if (id=="fmt " && safe>=16) {
                val b=ByteArray(minOf(safe,64).toInt()); r.readFully(b)
                fun u16(p:Int)=(b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
                fun u32(p:Int)=(b[p].toLong() and 255) or ((b[p+1].toLong() and 255) shl 8) or ((b[p+2].toLong() and 255) shl 16) or ((b[p+3].toLong() and 255) shl 24)
                fmt=u16(0); ch=u16(2); rate=u32(4).toInt(); bits=u16(14)
                if (fmt==0xfffe && b.size>=40) fmt=u16(24)
            } else if (id=="data") { dataAt=start; dataSize=safe }
            r.seek(start+safe+(safe and 1))
            if (dataAt>=0 && fmt!=0) break
        }
        require(fmt==1 || fmt==3) { "WAV 仅支持 PCM/IEEE Float 编码" }
        require(ch in 1..8 && rate in 8000..192000 && dataAt>=0) { "WAV 声道数、采样率或数据块无效" }
        require((fmt==1 && bits in listOf(8,16,24,32)) || (fmt==3 && bits==32)) { "不支持该 WAV 位深" }
        val bytes=bits/8; val frame=bytes*ch; val frames=(dataSize/frame).coerceAtMost(rate.toLong()*300).toInt()
        require(frames>0) { "WAV 没有音频数据" }
        val raw=ByteArray(frames*frame); r.seek(dataAt); r.readFully(raw)
        val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val samples=FloatArray(frames*ch) {
            (when {
                fmt==3 -> b.float
                bits==8 -> ((b.get().toInt() and 255)-128)/128f
                bits==16 -> b.short/32768f
                bits==24 -> { val x=(b.get().toInt() and 255) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() shl 16); (if(x and 0x800000!=0) x or -0x1000000 else x)/8388608f }
                else -> b.int/2147483648f
            }).coerceIn(-1f,1f)
        }
        Pcm(samples,rate,ch)
    }

    // Uses Android's installed decoders for MP3, AAC/M4A, FLAC, OGG/Vorbis, Opus, AMR, etc.
    private fun decode(file: File): Pcm {
        val ex=MediaExtractor(); var decoder:MediaCodec?=null
        try {
            ex.setDataSource(file.absolutePath)
            var track=-1; var format:MediaFormat?=null
            for(i in 0 until ex.trackCount) { val f=ex.getTrackFormat(i); if(f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true){track=i;format=f;break} }
            require(track>=0 && format!=null) { "无法识别音频格式" }
            ex.selectTrack(track)
            decoder=MediaCodec.createDecoderByType(format!!.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format,null,null,0); decoder.start()
            val out=ByteArrayOutputStream(); val info=MediaCodec.BufferInfo()
            var inputDone=false; var outputDone=false
            var rate=format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels=format!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=android.media.AudioFormat.ENCODING_PCM_16BIT
            val deadline=System.currentTimeMillis()+120000
            while(!outputDone && System.currentTimeMillis()<deadline) {
                if(!inputDone) { val ix=decoder.dequeueInputBuffer(10000); if(ix>=0) {
                    val buf=decoder.getInputBuffer(ix)!!; val n=ex.readSampleData(buf,0)
                    if(n<0){decoder.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                    else {decoder.queueInputBuffer(ix,0,n,ex.sampleTime,0);ex.advance()}
                } }
                when(val ox=decoder.dequeueOutputBuffer(info,10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { val f=decoder.outputFormat;rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))encoding=f.getInteger(MediaFormat.KEY_PCM_ENCODING) }
                    else -> if(ox>=0) { val buf=decoder.getOutputBuffer(ox);if(buf!=null && info.size>0){buf.position(info.offset);buf.limit(info.offset+info.size);val bytes=ByteArray(info.size);buf.get(bytes);out.write(bytes)};outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;decoder.releaseOutputBuffer(ox,false) }
                }
            }
            require(outputDone) { "音频解码超时" }
            val raw=out.toByteArray();val floatPcm=encoding==android.media.AudioFormat.ENCODING_PCM_FLOAT
            val bb=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val samples=FloatArray(raw.size/(if(floatPcm)4 else 2)){if(floatPcm)bb.float.coerceIn(-1f,1f) else bb.short/32768f}
            return Pcm(samples,rate,channels)
        } finally { try{decoder?.stop()}catch(_:Exception){};try{decoder?.release()}catch(_:Exception){};ex.release() }
    }

    private fun writeWav(file:File, pcm:ShortArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file,"rw").use { r ->
            r.setLength(0)
            fun s(v:String){r.write(v.toByteArray(Charsets.US_ASCII))}
            fun i16(v:Int){r.write(v and 255);r.write((v shr 8) and 255)}
            fun i32(v:Int){repeat(4){r.write((v shr (8*it)) and 255)}}
            val bytes=pcm.size*2
            s("RIFF");i32(36+bytes);s("WAVE");s("fmt ");i32(16);i16(1);i16(1);i32(SAMPLE_RATE);i32(SAMPLE_RATE*2);i16(2);i16(16);s("data");i32(bytes)
            val chunk=ByteArray(8192);var p=0
            while(p<pcm.size){val n=minOf(pcm.size-p,chunk.size/2);for(i in 0 until n){val v=pcm[p+i].toInt();chunk[i*2]=v.toByte();chunk[i*2+1]=(v shr 8).toByte()};r.write(chunk,0,n*2);p+=n}
        }
    }
}
