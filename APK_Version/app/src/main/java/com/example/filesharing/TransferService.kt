package com.example.filesharing

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

class TransferService : Service() {
    private var server: ApplicationEngine? = null

    companion object {
        const val CHANNEL_ID = "ARC_FLASH_CHANNEL"
        const val NOTIFICATION_ID = 101
        const val SERVER_PORT = 5000
        const val ARC_FLASH_KEY = "ARC_FLASH_SECURE_KEY"
        
        val incomingFileFlow = MutableSharedFlow<IncomingFileEvent>(extraBufferCapacity = 128)
        val fileCompletedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val dataProgressFlow = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 256)
        val stopSignalFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 10)
        
        private val serviceJob = SupervisorJob()
        val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
        
        val sessions = Collections.synchronizedMap(mutableMapOf<String, String>())
        var isRunning = false
        @Volatile var isCancelled = false

        fun getLocalIp(): String {
            val ips = getAllLocalIps()
            // Prefer common private subnets often used for WiFi/Hotspots
            return ips.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") } 
                ?: ips.firstOrNull() ?: "0.0.0.0"
        }

        fun getAllLocalIps(): List<String> {
            val ips = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (!ni.isUp || ni.isLoopback) continue
                    val addresses = ni.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address) {
                            val host = addr.hostAddress
                            if (host != null) ips.add(host)
                        }
                    }
                }
            } catch (e: Exception) {}
            return ips
        }

        fun sanitizeFilename(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    data class IncomingFileEvent(
        val name: String, 
        val size: Long, 
        val path: String, 
        val isFolder: Boolean = false,
        val folderName: String = "",
        val folderTotalSize: Long = 0
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isCancelled = false
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopServiceInternal()
            return START_NOT_STICKY
        }
        val notification = createNotification("ARC FLASH Ready")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startServer()
        return START_STICKY
    }

    private fun stopServiceInternal() {
        isCancelled = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopServiceInternal()
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                server = embeddedServer(CIO, port = SERVER_PORT) {
                    routing {
                        // Requirement 5: DEVICE VALIDATION
                        get("/api/server-info") {
                            val resp = JSONObject()
                                .put("status", "ok")
                                .put("name", android.os.Build.MODEL)
                                .put("version", "1.0")
                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        // SPI Protocol: Authentication
                        post("/spi/auth") {
                            val key = call.request.headers["X-ARCFLASH-KEY"]
                            if (key != ARC_FLASH_KEY) {
                                call.respond(HttpStatusCode.Unauthorized)
                                return@post
                            }
                            val sessionId = UUID.randomUUID().toString()
                            sessions[sessionId] = "MobileDevice"
                            val resp = JSONObject().put("session_id", sessionId)
                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        // SPI Protocol: Server Info / List
                        get("/spi/list") {
                            val key = call.request.headers["X-ARCFLASH-KEY"]
                            if (key != ARC_FLASH_KEY) {
                                call.respond(HttpStatusCode.Unauthorized)
                                return@get
                            }
                            val resp = JSONObject().put("status", "ok").put("files", JSONArray())
                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        post("/cancel_transfer") {
                            val key = call.request.headers["X-ARCFLASH-KEY"]
                            if (key != ARC_FLASH_KEY) {
                                call.respond(HttpStatusCode.Forbidden)
                                return@post
                            }
                            isCancelled = true
                            stopSignalFlow.tryEmit(Unit)
                            call.respond(HttpStatusCode.OK)
                        }

                        // SPI Protocol: Upload
                        post("/spi/upload") {
                            val key = call.request.headers["X-ARCFLASH-KEY"]
                            val sid = call.request.headers["session-id"]
                            
                            if (key != ARC_FLASH_KEY || sid == null) {
                                call.respond(HttpStatusCode.Forbidden)
                                return@post
                            }

                            isCancelled = false
                            val multipart = call.receiveMultipart()
                            multipart.forEachPart { part ->
                                if (part is PartData.FileItem) {
                                    val originalPath = part.headers["X-Relative-Path"] ?: part.originalFileName ?: "file"
                                    val fileName = originalPath.split("/").last()
                                    val relativeDir = if (originalPath.contains("/")) originalPath.substringBeforeLast("/") else ""
                                    val fileSize = part.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
                                    
                                    val isFolder = part.headers["X-Is-Folder"] == "true"
                                    val folderName = part.headers["X-Folder-Name"] ?: ""
                                    val folderTotalSize = part.headers["X-Folder-Total-Size"]?.toLong() ?: 0L

                                    val prefs = getSharedPreferences("ARC_FLASH_PREFS", Context.MODE_PRIVATE)
                                    val storageUriStr = prefs.getString("STORAGE_URI", null)
                                    val storageUri = if (storageUriStr != null) Uri.parse(storageUriStr) else null
                                    
                                    incomingFileFlow.tryEmit(IncomingFileEvent(
                                        name = if (isFolder) folderName else fileName, 
                                        size = if (isFolder) folderTotalSize else fileSize, 
                                        path = if (isFolder) folderName else originalPath,
                                        isFolder = isFolder,
                                        folderName = folderName,
                                        folderTotalSize = folderTotalSize
                                    ))

                                    if (storageUri != null) {
                                        saveToSaf(storageUri, fileName, relativeDir, if (isFolder) folderName else originalPath, part)
                                    } else {
                                        val defaultDir = File(getExternalFilesDir(null), "ArcFlash")
                                        if (!defaultDir.exists()) defaultDir.mkdirs()
                                        saveToInternal(defaultDir, fileName, relativeDir, if (isFolder) folderName else originalPath, part)
                                    }
                                }
                                part.dispose()
                            }
                            call.respondText("OK")
                        }
                    }
                }
                server?.start(wait = false)
            } catch (e: Exception) {}
        }
    }

    private fun saveToInternal(parent: File, fileName: String, relativeDir: String, identifier: String, part: PartData.FileItem) {
        val targetDir = if (relativeDir.isNotEmpty()) File(parent, relativeDir) else parent
        if (!targetDir.exists()) targetDir.mkdirs()
        val file = File(targetDir, fileName)
        try {
            FileOutputStream(file).use { out ->
                part.streamProvider().use { input ->
                    val bufferSize = 64 * 1024
                    val buffer = ByteArray(bufferSize)
                    val bufferedOut = BufferedOutputStream(out, bufferSize)
                    var bytesRead: Int
                    while (!isCancelled) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        bufferedOut.write(buffer, 0, bytesRead)
                        dataProgressFlow.tryEmit(identifier to bytesRead.toLong())
                    }
                    bufferedOut.flush()
                }
            }
            if (!isCancelled) fileCompletedFlow.tryEmit(identifier)
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun saveToSaf(treeUri: Uri, fileName: String, relativeDir: String, identifier: String, part: PartData.FileItem) {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return
        var targetDir = root
        if (relativeDir.isNotEmpty()) {
            val parts = relativeDir.split("/")
            for (p in parts) {
                if (p.isEmpty()) continue
                val existingDir = targetDir.findFile(p)
                targetDir = if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    targetDir.createDirectory(p) ?: targetDir
                }
            }
        }

        val existing = targetDir.findFile(fileName)
        existing?.delete()
        
        val file = targetDir.createFile("application/octet-stream", fileName) ?: return
        
        try {
            contentResolver.openOutputStream(file.uri)?.use { out ->
                part.streamProvider().use { input ->
                    val bufferSize = 64 * 1024
                    val buffer = ByteArray(bufferSize)
                    val bufferedOut = BufferedOutputStream(out, bufferSize)
                    var bytesRead: Int
                    while (!isCancelled) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        bufferedOut.write(buffer, 0, bytesRead)
                        dataProgressFlow.tryEmit(identifier to bytesRead.toLong())
                    }
                    bufferedOut.flush()
                }
            }
            if (!isCancelled) {
                fileCompletedFlow.tryEmit(identifier)
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, TransferService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARC FLASH")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ARC FLASH Transfer", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        isCancelled = true
        server?.stop(500, 1000)
        serviceJob.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
