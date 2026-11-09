package com.example.minicpm_v_demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ApiSettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("local_api", Context.MODE_PRIVATE) }
    private lateinit var tokenField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(text: String, size: Float = 16f) {
            root.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, pad / 2, 0, pad / 2) })
        }
        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } },
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        label(getString(R.string.api_title), 22f)
        label("http://127.0.0.1:8765")
        label(getString(R.string.api_token))
        tokenField = EditText(this).apply { setText(getToken()); isSingleLine = true; isFocusable = false }
        root.addView(tokenField, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        button(getString(R.string.api_copy_token)) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VoxCPM2 API token", tokenField.text))
            Toast.makeText(this, R.string.api_token_copied, Toast.LENGTH_SHORT).show()
        }
        button(getString(R.string.api_regenerate_token)) {
            val value = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("token", value).apply()
            tokenField.setText(value)
        }
        label(getString(R.string.api_security_note), 14f)
        label(getString(R.string.api_start_hint), 14f)
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad, 0, pad) }
        root.addView(status)
        button(getString(R.string.api_start)) {
            ContextCompat.startForegroundService(this, Intent(this, LocalApiService::class.java).setAction(LocalApiService.START))
            status.setText(R.string.api_running)
        }
        button(getString(R.string.api_stop)) {
            startService(Intent(this, LocalApiService::class.java).setAction(LocalApiService.STOP))
            status.setText(R.string.api_stopped)
        }
        button(getString(R.string.cancel)) { finish() }
        setContentView(root)
        status.setText(if (LocalApiService.running) R.string.api_running else R.string.api_stopped)
    }

    private fun getToken(): String {
        prefs.getString("token", null)?.let { if (it.isNotBlank()) return it }
        val value = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString("token", value).apply()
        return value
    }
}

class LocalApiService : Service() {
    companion object {
        const val START = "voxcpm.local_api.START"
        const val STOP = "voxcpm.local_api.STOP"
        private const val CHANNEL = "voxcpm_local_api"
        @Volatile var running = false
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalApiServer? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "VoxCPM2 Local API", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 7, Intent(this, LocalApiService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("VoxCPM2 Local API")
            .setContentText("127.0.0.1:8765")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
        startForeground(8765, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (server == null) {
            val token = getSharedPreferences("local_api", MODE_PRIVATE).getString("token", "").orEmpty()
            server = LocalApiServer(applicationContext, 8765, token).also { it.start() }
            running = true
            scope.launch {
                try {
                    val engine = TtsEngine.getInstance(applicationContext)
                    var n = 0
                    while (n++ < 60 && (engine.state.value is TtsState.Initializing || engine.state.value is TtsState.Uninitialized)) delay(500)
                    if (!engine.isLoaded) engine.loadModel()
                } catch (e: Exception) { Log.e("LocalApiService", "VoxCPM2 initialization failed", e) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        server?.stop()
        server = null
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

private data class ApiTask(
    val id: String,
    @Volatile var status: String = "queued",
    @Volatile var error: String? = null,
    @Volatile var audio: File? = null
)

private class LocalApiServer(private val context: Context, private val port: Int, private val token: String) {
    private val executor = Executors.newFixedThreadPool(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generationLock = Mutex()
    private val tasks = ConcurrentHashMap<String, ApiTask>()
    @Volatile private var active = false
    private var server: ServerSocket? = null

    fun start() {
        active = true
        executor.execute {
            try {
                server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
                while (active) {
                    val client = server?.accept() ?: break
                    executor.execute { handle(client) }
                }
            } catch (e: Exception) { if (active) Log.e("LocalApiServer", "Listener failed", e) }
        }
    }

    fun stop() {
        active = false
        try { server?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        scope.cancel()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30000
                val input = BufferedInputStream(socket.getInputStream())
                val header = ByteArrayOutputStream()
                var matched = 0
                val end = byteArrayOf(13, 10, 13, 10)
                while (header.size() < 16384 && matched < 4) {
                    val b = input.read()
                    if (b < 0) return
                    header.write(b)
                    matched = if (b == (end[matched].toInt() and 255)) matched + 1 else if (b == 13) 1 else 0
                }
                if (matched != 4) { reply(socket, 400, err("bad headers")); return }
                val lines = header.toString("ISO-8859-1").split("\r\n")
                val first = lines.first().split(" ")
                if (first.size < 2) { reply(socket, 400, err("bad request")); return }
                val method = first[0]
                val path = first[1].substringBefore("?")
                val headers = lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i < 1) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }.toMap()
                val size = headers["content-length"]?.toIntOrNull() ?: 0
                if (size !in 0..(10 * 1024 * 1024)) { reply(socket, 413, err("body too large")); return }
                val body = ByteArray(size)
                var read = 0
                while (read < size) {
                    val count = input.read(body, read, size - read)
                    if (count < 0) throw IllegalArgumentException("incomplete body")
                    read += count
                }
                if (method == "GET" && path == "/health") {
                    val engine = TtsEngine.getInstance(context)
                    reply(socket, 200, JSONObject().put("service", "running").put("model", "voxcpm2")
                        .put("ready", engine.isLoaded && engine.state.value is TtsState.Ready).toString())
                    return
                }
                if (headers["authorization"] != "Bearer $token" || token.isBlank()) {
                    reply(socket, 401, err("unauthorized")); return
                }
                if (method == "GET" && path == "/v1/models") {
                    reply(socket, 200, JSONObject().put("object", "list").put("data",
                        JSONArray().put(JSONObject().put("id", "voxcpm2").put("object", "model").put("owned_by", "local"))).toString())
                    return
                }
                if (method == "POST" && (path == "/v1/audio/speech" || path == "/v1/audio/clone")) {
                    val request = JSONObject(String(body, Charsets.UTF_8))
                    val text = request.optString("input", request.optString("text", "")).trim()
                    if (text.isBlank() || text.length > 5000) { reply(socket, 400, err("input length must be 1-5000")); return }
                    val cfg = request.optDouble("cfg_value", 2.0).toFloat().coerceIn(0.5f, 5f)
                    val steps = request.optInt("timesteps", 5).coerceIn(1, 20)
                    val reference = if (path.endsWith("/clone")) {
                        val encoded = request.optString("reference_audio_base64", "")
                        if (encoded.isBlank()) { reply(socket, 400, err("reference_audio_base64 required")); return }
                        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) {
                            reply(socket, 400, err("invalid base64")); return
                        }
                        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) {
                            reply(socket, 400, err("reference audio must be between 1 byte and 8 MiB")); return
                        }
                        val source = File(context.filesDir, "api_audio/ref_${UUID.randomUUID()}.source").apply {
                            parentFile?.mkdirs(); writeBytes(bytes)
                        }
                        val normalized = File(context.filesDir, "api_audio/ref_${UUID.randomUUID()}.wav")
                        try {
                            AudioNormalizer.normalize(source, normalized)
                            normalized.absolutePath
                        } catch (e: Exception) {
                            source.delete()
                            reply(socket, 400, err("unsupported reference audio: ${e.message ?: "decode failed"}")); return
                        } finally {
                            source.delete()
                        }
                    } else null
                    val task = ApiTask(UUID.randomUUID().toString())
                    tasks[task.id] = task
                    scope.launch {
                        task.status = "running"
                        val output = File(context.filesDir, "api_audio/${task.id}.wav")
                        output.parentFile?.mkdirs()
                        try {
                            generationLock.withLock {
                                val engine = TtsEngine.getInstance(context)
                                if (!engine.isLoaded) throw IllegalStateException("VoxCPM2 model is not ready")
                                val ok = engine.generate(text, cfg, steps, reference, output.absolutePath)
                                if (!ok || !output.exists() || output.length() <= 44) throw IllegalStateException("generation failed")
                            }
                            task.audio = output
                            task.status = "completed"
                        } catch (e: Exception) {
                            task.error = e.message ?: "generation failed"
                            task.status = "failed"
                            output.delete()
                        } finally { if (reference != null) File(reference).delete() }
                    }
                    reply(socket, 202, JSONObject().put("id", task.id).put("status", task.status)
                        .put("status_url", "/v1/tasks/${task.id}").toString())
                    return
                }
                if (method == "GET" && path.startsWith("/v1/tasks/")) {
                    val parts = path.removePrefix("/v1/tasks/").split("/")
                    val task = tasks[parts[0]]
                    if (task == null) { reply(socket, 404, err("task not found")); return }
                    if (parts.size > 1 && parts[1] == "audio") {
                        val audio = task.audio
                        if (task.status != "completed" || audio == null || !audio.exists()) { reply(socket, 409, err("audio not ready")); return }
                        replyBytes(socket, 200, audio.readBytes(), "audio/wav")
                    } else {
                        val result = JSONObject().put("id", task.id).put("status", task.status)
                        task.error?.let { result.put("error", it) }
                        if (task.status == "completed") result.put("audio_url", "/v1/tasks/${task.id}/audio")
                        reply(socket, 200, result.toString())
                    }
                    return
                }
                reply(socket, 404, err("endpoint not found"))
            } catch (e: Exception) {
                Log.e("LocalApiServer", "Request failed", e)
                try { reply(socket, 500, err(e.message ?: "internal error")) } catch (_: Exception) {}
            }
        }
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()
    private fun reply(socket: Socket, code: Int, body: String) =
        replyBytes(socket, code, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    private fun replyBytes(socket: Socket, code: Int, body: ByteArray, type: String) {
        val phrase = when (code) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; else -> "Internal Server Error" }
        val header = "HTTP/1.1 $code $phrase\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
