package com.example.filesharing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit

// --- Models ---
enum class TransferStatus { PENDING, ACTIVE, SUCCESS, FAILED, CANCELLED, PAUSED }
enum class TransferType { UPLOAD, DOWNLOAD }

@androidx.annotation.Keep
data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val progress: Float = 0f,
    val speed: String = "0.0 MB/s",
    val status: TransferStatus = TransferStatus.PENDING,
    val relativePath: String,
    val uri: Uri? = null,
    val type: TransferType = TransferType.UPLOAD,
    val errorMessage: String? = null,
    val transferredBytes: Long = 0,
    val lastTransferredBytes: Long = 0,
    val lastUpdate: Long = 0,
    val isPull: Boolean = true,
    val isFolder: Boolean = false,
    val childrenUris: List<Pair<Uri, String>> = emptyList()
)

@androidx.annotation.Keep
data class TransferStats(
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val totalSize: Long = 0,
    val transferredSize: Long = 0,
    val overallSpeed: String = "0.0 MB/s",
    val rawSpeed: Long = 0, // Bytes per second
    val startTime: Long = 0,
    val elapsedTime: String = "00:00",
    val isTransferring: Boolean = false,
    val lastTransferredSize: Long = 0,
    val lastUpdateTime: Long = 0,
    val activeTimeMs: Long = 0 // Tracks total duration excluding pauses
)

data class DiscoveredDevice(
    val ip: String,
    val name: String,
    val lastSeen: Long = System.currentTimeMillis()
)

// --- ViewModel ---
class FileSharingViewModel(application: Application) : AndroidViewModel(application) {
    private val arcFlashKeyString = "ARC_FLASH_SECURE_KEY"
    private var sessionId: String? = null
    private var socket: Socket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _sendTransfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val sendTransfers = _sendTransfers.asStateFlow()

    private val _receiveTransfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val receiveTransfers = _receiveTransfers.asStateFlow()

    private val _sendStats = MutableStateFlow(TransferStats())
    val sendStats = _sendStats.asStateFlow()

    private val _receiveStats = MutableStateFlow(TransferStats())
    val receiveStats = _receiveStats.asStateFlow()

    private val _localIp = MutableStateFlow("")
    val localIp = _localIp.asStateFlow()

    private val _storagePath = MutableStateFlow<Uri?>(null)
    val storagePath = _storagePath.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _themeMode = MutableStateFlow(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    val themeMode = _themeMode.asStateFlow()

    private val _remoteServerIp = MutableStateFlow<String?>(null)
    val remoteServerIp = _remoteServerIp.asStateFlow()
    
    private val _isConnectionValidated = MutableStateFlow(false)
    val isConnectionValidated = _isConnectionValidated.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
        .connectionPool(ConnectionPool(30, 5, TimeUnit.MINUTES))
        .connectTimeout(1000, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // Increased read timeout for large transfers
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(15) // Increased concurrency for transfers
    private val activeJobs = mutableMapOf<String, Job>()
    private var prefs: SharedPreferences? = null
    private var discoveryJob: Job? = null

    init {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                prefs = application.getSharedPreferences("ARC_FLASH_PREFS", Context.MODE_PRIVATE)
                val savedPath = prefs?.getString("STORAGE_URI", null)
                if (savedPath != null) _storagePath.value = savedPath.toUri()
                val savedTheme = prefs?.getInt("THEME_MODE", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                _themeMode.value = savedTheme
                AppCompatDelegate.setDefaultNightMode(savedTheme)
                _localIp.value = TransferService.getLocalIp()
                
                observeBackgroundReceiver()
                setupNetworkMonitoring(application)
                startStatsTimer()
                startDiscoveryLoop()
                
                application.startService(Intent(application, TransferService::class.java))
            } catch (e: Exception) {
                Log.e("ArcFlash", "Init error", e)
            }
        }, 500)
    }

    private fun startStatsTimer() {
        viewModelScope.launch { while (isActive) { delay(1000); updateIntervalSpeeds() } }
    }

    private fun startDiscoveryLoop() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshDiscovery()
                delay(5000)
            }
        }
    }

    fun refreshDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentIp = TransferService.getLocalIp()
            _localIp.value = currentIp
            // Removed _discoveredDevices.update { emptyList() } to prevent blinking
            if (currentIp != "0.0.0.0" && currentIp.isNotEmpty()) {
                scanSubnet(currentIp)
            }
            cleanupDevices()
        }
    }

    private suspend fun scanSubnet(localIp: String) {
        val subnet = localIp.substringBeforeLast(".")
        val gateway = "$subnet.1"
        coroutineScope {
            launch { validateDiscoveredDevice(gateway) }
            val scanSemaphore = Semaphore(25)
            (1..254).forEach { i ->
                val targetIp = "$subnet.$i"
                if (targetIp != localIp && targetIp != gateway) {
                    launch { scanSemaphore.withPermit { validateDiscoveredDevice(targetIp) } }
                    if (i % 30 == 0) delay(150)
                }
            }
        }
    }

    private suspend fun validateDiscoveredDevice(ip: String) {
        try {
            val url = "http://$ip:5000/api/server-info"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = JSONObject(resp.body?.string() ?: "{}")
                    val name = body.optString("name", "Arc Flash Device")
                    addDiscoveredDevice(ip, name)
                }
            }
        } catch (_: Exception) {}
    }

    private fun addDiscoveredDevice(ip: String, name: String) {
        _discoveredDevices.update { list ->
            val existing = list.find { it.ip == ip }
            if (existing != null) {
                list.map { if (it.ip == ip) it.copy(name = name, lastSeen = System.currentTimeMillis()) else it }
            } else {
                list + DiscoveredDevice(ip, name)
            }
        }
    }

    private fun cleanupDevices() {
        val now = System.currentTimeMillis()
        _discoveredDevices.update { list ->
            list.filter { now - it.lastSeen < 12000 }
        }
        val currentRemote = _remoteServerIp.value
        if (currentRemote != null) {
            val isStillInList = _discoveredDevices.value.any { it.ip == currentRemote }
            // Only clear remoteServerIp if the device is not found AND the connection is actually not valid anymore.
            // This prevents blinking if discovery is flaky but connection is stable.
            if (!isStillInList) {
                 viewModelScope.launch {
                     if (!checkConnectionBeforeTransfer(currentRemote)) {
                         _remoteServerIp.value = null
                         _isConnectionValidated.value = false
                     }
                 }
            }
        }
    }

    private fun updateIntervalSpeeds() {
        val now = System.currentTimeMillis()
        fun updateFlowStats(statsFlow: MutableStateFlow<TransferStats>, transfersFlow: MutableStateFlow<List<TransferItem>>, type: TransferType) {
            val stats = statsFlow.value
            if (stats.totalFiles == 0) return

            if (stats.isTransferring) {
                if (!_isPaused.value && !TransferService.isCancelled) {
                    val durationMs = now - stats.lastUpdateTime
                    if (durationMs >= 500) { // Only update if at least 0.5 seconds have passed
                        val deltaBytes = stats.transferredSize - stats.lastTransferredSize
                        val speedBps = (deltaBytes * 1000.0 / durationMs).toLong()
                        val speedMBps = (speedBps / 1024.0 / 1024.0)
                        
                        val newActiveTime = stats.activeTimeMs + durationMs
                        val sec = (newActiveTime / 1000).coerceAtLeast(0)

                        statsFlow.update { it.copy(
                            overallSpeed = "%.1f MB/s".format(speedMBps),
                            rawSpeed = speedBps,
                            lastTransferredSize = stats.transferredSize,
                            lastUpdateTime = now,
                            activeTimeMs = newActiveTime,
                            elapsedTime = "%02d:%02d".format(sec / 60, sec % 60)
                        ) }

                        transfersFlow.update { list -> 
                            list.map { item -> 
                                if (item.status == TransferStatus.ACTIVE) {
                                    val itemDelta = item.transferredBytes - item.lastTransferredBytes
                                    val itemSpeed = (itemDelta / (durationMs / 1000.0) / 1024 / 1024)
                                    item.copy(speed = "%.1f MB/s".format(itemSpeed), lastTransferredBytes = item.transferredBytes, lastUpdate = now) 
                                } else if (item.status == TransferStatus.SUCCESS) {
                                    item.copy(speed = "Completed") 
                                } else if (item.status == TransferStatus.FAILED) {
                                    item.copy(speed = "Failed")
                                } else if (item.status == TransferStatus.CANCELLED) {
                                    item.copy(speed = "Cancelled")
                                } else item 
                            } 
                        }
                    }
                } else if (_isPaused.value) {
                    statsFlow.update { it.copy(overallSpeed = "Paused", rawSpeed = 0, lastUpdateTime = now) }
                }
            } else if (stats.completedFiles > 0 && stats.completedFiles == stats.totalFiles) {
                // Transfer is completed, but overallSpeed and elapsedTime should have been finalized in checkStatsCompletion.
                // This block is now primarily for displaying the final summary if it wasn't already set.
                // Ensure we don't re-update if already finalized.
                val sec = (stats.activeTimeMs / 1000).coerceAtLeast(0)
                val avgSpeed = if (stats.activeTimeMs > 0) (stats.totalSize.toDouble() / 1024 / 1024 / (stats.activeTimeMs / 1000.0)) else 0.0
                val speedText = if (avgSpeed > 0) "Completed (%.1f MB/s)".format(avgSpeed) else "Completed"
                
                statsFlow.update { 
                    if (it.overallSpeed != speedText || it.elapsedTime != "%02d:%02d".format(sec / 60, sec % 60)) { 
                        it.copy(
                            overallSpeed = speedText,
                            rawSpeed = 0,
                            elapsedTime = "%02d:%02d".format(sec / 60, sec % 60)
                        )
                    } else it 
                }
            }
        }
        updateFlowStats(_sendStats, _sendTransfers, TransferType.UPLOAD) 
        updateFlowStats(_receiveStats, _receiveTransfers, TransferType.DOWNLOAD) 
    }

    private fun observeBackgroundReceiver() {
        viewModelScope.launch { TransferService.incomingFileFlow.collect { handleIncomingFile(it.name, it.size, it.path, pull = false) } }
        viewModelScope.launch {
            TransferService.dataProgressFlow.collect { (path, delta) ->
                _receiveStats.update { it.copy(transferredSize = it.transferredSize + delta) }
                _receiveTransfers.update { list ->
                    list.map { item ->
                        if (item.status == TransferStatus.ACTIVE && (item.relativePath == path || (item.isFolder && path.startsWith(item.name)))) {
                            val newTransferred = item.transferredBytes + delta
                            val progress = (if (item.size > 0) newTransferred.toFloat() / item.size else 0f).coerceIn(0f, 1f)
                            var newStatus = item.status
                            if (newTransferred >= item.size && item.size > 0) {
                                newStatus = TransferStatus.SUCCESS
                                _receiveStats.update { it.copy(completedFiles = it.completedFiles + 1) }
                            }
                            item.copy(transferredBytes = newTransferred, progress = progress, status = newStatus)
                        } else item
                    }
                }
                checkStatsCompletion(TransferType.DOWNLOAD)
            }
        }
    }

    private fun handleIncomingFile(name: String, size: Long, path: String, pull: Boolean) {
        val fileName = TransferService.sanitizeFilename(name)
        if (_receiveTransfers.value.any { it.name == fileName && it.size == size && it.relativePath == path }) return
        
        TransferService.isCancelled = false
        _receiveStats.update { 
            if (!it.isTransferring || it.completedFiles == it.totalFiles) { // If not transferring or previous batch completed, reset
                TransferStats(
                    startTime = System.currentTimeMillis(),
                    isTransferring = true,
                    lastUpdateTime = System.currentTimeMillis(),
                    totalFiles = 1,
                    totalSize = size
                )
            } else {
                it.copy(totalFiles = it.totalFiles + 1, totalSize = it.totalSize + size)
            }
        }
        val item = TransferItem(name = fileName, size = size, relativePath = path, type = TransferType.DOWNLOAD, status = TransferStatus.ACTIVE, lastUpdate = System.currentTimeMillis(), isPull = pull)
        _receiveTransfers.update { it + item }
    }

    private fun setupNetworkMonitoring(context: Context) {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { viewModelScope.launch { delay(1000); _localIp.value = TransferService.getLocalIp() } }
            override fun onLost(network: Network) { _localIp.value = "0.0.0.0" }
        }
        networkCallback?.let { connectivityManager.registerNetworkCallback(request, it) }
    }

    fun setTheme(mode: Int) { _themeMode.value = mode; prefs?.edit { putInt("THEME_MODE", mode) }; AppCompatDelegate.setDefaultNightMode(mode) }
    fun setStoragePath(uri: Uri) { _storagePath.value = uri; prefs?.edit { putString("STORAGE_URI", uri.toString()) } }
    
    fun togglePause() { 
        val now = System.currentTimeMillis()
        if (_isPaused.value) {
            _sendStats.update { it.copy(lastUpdateTime = now) }
            _receiveStats.update { it.copy(lastUpdateTime = now) }
        }
        _isPaused.value = !_isPaused.value 
    }

    fun connectToScannedData(data: String) {
        var ip = data
        if (data.contains(":://")) ip = Uri.parse(data).host ?: data
        if (ip.contains(":")) ip = ip.substringBefore(":")
        addDiscoveredDevice(ip, "QR Device")
        validateAndConnect(ip)
    }

    fun validateAndConnect(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isConnectionValidated.value = false
                val url = "http://$ip:5000/spi/auth"
                val req = Request.Builder().url(url).header("X-ARCFLASH-KEY", arcFlashKeyString).post("{}".toRequestBody("application/json".toMediaTypeOrNull())).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = JSONObject(resp.body?.string() ?: "{}")
                        sessionId = body.optString("session_id")
                        _remoteServerIp.value = ip
                        _isConnectionValidated.value = true
                        addDiscoveredDevice(ip, "Connected Device")
                        setupSocket(ip)
                    } else {
                        withContext(Dispatchers.Main) { _isConnectionValidated.value = false; _remoteServerIp.value = null }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _isConnectionValidated.value = false; _remoteServerIp.value = null }
            }
        }
    }

    private fun setupSocket(ip: String) {
        try {
            socket?.disconnect()
            val opts = IO.Options(); opts.query = "session_id=$sessionId"
            socket = IO.socket("http://$ip:5000", opts)
            socket?.on("incoming_file") { args -> val data = args[0] as JSONObject; viewModelScope.launch { handleIncomingFile(data.optString("name"), data.optLong("size"), data.optString("path"), pull = true) } }
            socket?.on("cancel_transfer") { stopAll() }
            socket?.on(Socket.EVENT_DISCONNECT) { _isConnectionValidated.value = false }
            socket?.connect()
        } catch (_: Exception) {}
    }

    private suspend fun checkConnectionBeforeTransfer(serverIp: String): Boolean {
        return try { val req = Request.Builder().url("http://$serverIp:5000/api/server-info").get().build(); client.newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    fun addFiles(context: Context, uris: List<Uri>, serverIp: String) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!checkConnectionBeforeTransfer(serverIp)) { _isConnectionValidated.value = false; return@launch }
            clear(TransferType.UPLOAD) // Clear previous upload stats and transfers
            TransferService.isCancelled = false
            uris.forEach { uri -> val (name, size) = getFileNameAndSize(context, uri); enqueue(context, TransferItem(name = name, size = size, relativePath = name, uri = uri, type = TransferType.UPLOAD), serverIp) }
        }
    }

    private fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        var name = docFile?.name ?: "file_${System.currentTimeMillis()}"; var size = docFile?.length() ?: 0L
        if (size <= 0L) { try { context.contentResolver.query(uri, null, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) { val nIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sIdx = cursor.getColumnIndex(OpenableColumns.SIZE); if (nIdx != -1) name = cursor.getString(nIdx) ?: name; if (sIdx != -1) size = cursor.getLong(sIdx) } } } catch (_: Exception) {} }
        return name to size
    }

    fun addFolder(context: Context, treeUri: Uri, serverIp: String) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!checkConnectionBeforeTransfer(serverIp)) { _isConnectionValidated.value = false; return@launch }
            clear(TransferType.UPLOAD) // Clear previous upload stats and transfers
            TransferService.isCancelled = false
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@launch
            val rootName = rootDoc.name ?: "Folder"
            val fileList = mutableListOf<Pair<Uri, String>>()
            var totalSize = 0L
            suspend fun scanInternal(parentId: String, path: String) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0); val name = cursor.getString(1); val mime = cursor.getString(2); val size = cursor.getLong(3)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) scanInternal(id, "$path/$name") else { fileList.add(docUri to "$path/$name"); totalSize += size }
                    }
                }
            }
            scanInternal(DocumentsContract.getTreeDocumentId(treeUri), rootName)
            val item = TransferItem(name = rootName, size = totalSize, relativePath = rootName, type = TransferType.UPLOAD, isFolder = true, childrenUris = fileList)
            enqueue(context, item, serverIp)
        }
    }

    private fun resetStats() { 
        TransferService.isCancelled = false
        _sendStats.value = TransferStats(isTransferring = true, startTime = System.currentTimeMillis(), lastUpdateTime = System.currentTimeMillis()) 
    }

    private fun enqueue(context: Context, item: TransferItem, serverIp: String) {
        val listFlow = if (item.type == TransferType.UPLOAD) _sendTransfers else _receiveTransfers
        val statsFlow = if (item.type == TransferType.UPLOAD) _sendStats else _receiveStats
        
        listFlow.update { it + item }
        statsFlow.update { 
            if (!it.isTransferring || it.completedFiles == it.totalFiles) { // If not transferring or previous batch completed, reset
                TransferStats(
                    startTime = System.currentTimeMillis(),
                    isTransferring = true,
                    lastUpdateTime = System.currentTimeMillis(),
                    totalFiles = 1,
                    totalSize = item.size
                )
            } else {
                it.copy(totalFiles = it.totalFiles + 1, totalSize = it.totalSize + item.size)
            }
        }
        startJob(context, item, serverIp)
    }

    private fun startJob(context: Context, item: TransferItem, serverIp: String) {
        val job = viewModelScope.launch(Dispatchers.IO) { semaphore.withPermit { var retryCount = 0; var success = false; while (retryCount < 3 && !success && isActive) { try { if (item.type == TransferType.UPLOAD) runUpload(context, item, serverIp); success = true } catch (e: Exception) { retryCount++; if (retryCount >= 3) updateItem(item.id, item.type) { it.copy(status = TransferStatus.FAILED, errorMessage = e.message) } else delay(2000) } } } }
        activeJobs[item.id] = job
    }

    private fun runUpload(context: Context, item: TransferItem, serverIp: String) {
        Log.d("ArcFlashUpload", "Starting upload for item: ${item.name}")
        updateItem(item.id, item.type) { it.copy(status = TransferStatus.ACTIVE, errorMessage = null, lastUpdate = System.currentTimeMillis()) }
        var uploadedForTotalItem = 0L
        val filesToUpload = if (item.isFolder) item.childrenUris else listOf(item.uri!! to item.relativePath)
        try {
            for ((uri, relativePath) in filesToUpload) {
                if (TransferService.isCancelled) throw IOException("Cancelled")
                val rawInput = context.contentResolver.openInputStream(uri) ?: throw IOException("Stream error")
                val fileSize = DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
                val input = BufferedInputStream(rawInput, 64 * 1024)
                var fileUploaded = 0L
                val body = ProgressRequestBody(input, "application/octet-stream".toMediaTypeOrNull(), fileSize, { _isPaused.value }, { TransferService.isCancelled }, { current, _ -> val delta = current - fileUploaded; fileUploaded = current; uploadedForTotalItem += delta; _sendStats.update { it.copy(transferredSize = it.transferredSize + delta) }; updateItemProgress(item.id, item.type, uploadedForTotalItem, item.size) })
                val reqBuilder = Request.Builder().url("http://$serverIp:5000/spi/upload").header("X-ARCFLASH-KEY", arcFlashKeyString).header("session-id", sessionId ?: "").header("X-Relative-Path", relativePath).header("X-Is-Folder", item.isFolder.toString()).header("X-Folder-Name", item.name).header("X-Folder-Total-Size", item.size.toString())
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", relativePath.split("/").last(), body).build()
                val req = reqBuilder.post(multipart).build()
                client.newCall(req).execute().use { resp -> if (!resp.isSuccessful) { if (TransferService.isCancelled) throw IOException("Cancelled"); throw IOException("Code: ${resp.code}") } }
            }
            updateItem(item.id, item.type) { it.copy(status = TransferStatus.SUCCESS, progress = 1f, speed = "Completed") }
            _sendStats.update { it.copy(completedFiles = it.completedFiles + 1) }
            checkStatsCompletion(TransferType.UPLOAD)
        } catch (e: Exception) { throw e } finally { activeJobs.remove(item.id) }
    }

    private fun updateItem(id: String, type: TransferType, transform: (TransferItem) -> TransferItem) { val listFlow = if (type == TransferType.UPLOAD) _sendTransfers else _receiveTransfers; listFlow.update { list -> list.map { if (it.id == id) transform(it) else it } } }
    private fun updateItemProgress(id: String, type: TransferType, transferred: Long, total: Long) { updateItem(id, type) { item -> val progress = (if (total > 0) transferred.toFloat() / total else 0f).coerceIn(0f, 1f); item.copy(transferredBytes = transferred, progress = progress) } }
    
    private fun checkStatsCompletion(type: TransferType) { 
        val statsFlow = if (type == TransferType.UPLOAD) _sendStats else _receiveStats; 
        val now = System.currentTimeMillis()
        statsFlow.update { stats ->
            if (stats.completedFiles >= stats.totalFiles && stats.totalFiles > 0 && stats.isTransferring) { 
                val finalActiveTime = stats.activeTimeMs + (if (!_isPaused.value) (now - stats.lastUpdateTime) else 0)
                val finalSec = (finalActiveTime / 1000).coerceAtLeast(0)
                // Set isTransferring to false, rawSpeed to 0, and ensure lastUpdateTime is set once for final state
                stats.copy(
                    isTransferring = false,
                    lastUpdateTime = now,
                    activeTimeMs = finalActiveTime,
                    rawSpeed = 0,
                    elapsedTime = "%02d:%02d".format(finalSec / 60, finalSec % 60) // Finalize elapsedTime here
                )
            } else stats
        }
    }

    fun stopAll() {
        TransferService.isCancelled = true
        activeJobs.values.forEach { it.cancel() }; activeJobs.clear()
        val cancelFunc = { list: List<TransferItem> -> list.map { if (it.status == TransferStatus.ACTIVE || it.status == TransferStatus.PAUSED) it.copy(status = TransferStatus.CANCELLED, speed = "Cancelled") else it } }
        _sendTransfers.update(cancelFunc); _receiveTransfers.update(cancelFunc)
        _sendStats.update { it.copy(isTransferring = false, overallSpeed = "Cancelled", rawSpeed = 0) }
        _receiveStats.update { it.copy(isTransferring = false, overallSpeed = "Cancelled", rawSpeed = 0) }
        val remoteIp = _remoteServerIp.value
        if (remoteIp != null) { viewModelScope.launch(Dispatchers.IO) { try { val cancelObj = JSONObject().put("session_id", sessionId ?: "none"); val req = Request.Builder().url("http://$remoteIp:5000/cancel_transfer").header("X-ARCFLASH-KEY", arcFlashKeyString).post(cancelObj.toString().toRequestBody("application/json".toMediaTypeOrNull())).build(); client.newCall(req).execute().use { }; socket?.emit("cancel_transfer", cancelObj) } catch (_: Exception) {} } }
    }

    fun clear(type: TransferType) { if (type == TransferType.UPLOAD) { _sendTransfers.value = emptyList(); _sendStats.value = TransferStats() } else { _receiveTransfers.value = emptyList(); _receiveStats.value = TransferStats() } }

    override fun onCleared() { socket?.disconnect(); networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }; discoveryJob?.cancel(); super.onCleared() }
}
