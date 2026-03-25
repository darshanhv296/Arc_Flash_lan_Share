package com.example.filesharing

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.filesharing.ui.theme.FILESHARINGTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale
import kotlin.math.log
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val perms = mutableListOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissionLauncher.launch(perms.toTypedArray())
            setContent {
                val vm: FileSharingViewModel = viewModel()
                val themeMode by vm.themeMode.collectAsState()
                val isDark = themeMode == AppCompatDelegate.MODE_NIGHT_YES || (themeMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM && isSystemInDarkTheme())
                FILESHARINGTheme(darkTheme = isDark, dynamicColor = false) { Surface { MainScreen(vm) } }
            }
        } catch (e: Exception) { Log.e("ArcFlash", "MainActivity onCreate error", e) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: FileSharingViewModel) {
    val sendTransfers by vm.sendTransfers.collectAsState()
    val receiveTransfers by vm.receiveTransfers.collectAsState()
    val sendStats by vm.sendStats.collectAsState()
    val receiveStats by vm.receiveStats.collectAsState()
    val localIp by vm.localIp.collectAsState()
    val isPaused by vm.isPaused.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val remoteServerIp by vm.remoteServerIp.collectAsState()
    val isValidated by vm.isConnectionValidated.collectAsState()
    val discoveredDevices by vm.discoveredDevices.collectAsState()
    
    val currentTargetIp = remoteServerIp ?: ""
    val targetDisplayName = remember(remoteServerIp, discoveredDevices) {
        if (remoteServerIp == null) "Select a device" else discoveredDevices.find { it.ip == remoteServerIp }?.name ?: remoteServerIp
    }

    var tab by remember { mutableIntStateOf(0) }
    var showQr by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            Surface(shadowElevation = 8.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("ARC FLASH", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold); Text(" 296™", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(start = 2.dp)) }
                    Text("My IP: $localIp", fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        IconButton(onClick = { vm.togglePause() }) { Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null) }
                        IconButton(onClick = { showQr = true }) { Icon(Icons.Default.QrCode, null) }
                        IconButton(onClick = { showScanner = true }) { Icon(Icons.Default.QrCodeScanner, null) }
                        IconButton(onClick = { vm.refreshDiscovery() }) { Icon(Icons.Default.Refresh, null) }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, null) }
                        IconButton(onClick = { vm.stopAll() }) { Icon(Icons.Default.Stop, null, tint = Color.Red) }
                        IconButton(onClick = { vm.clear(if (tab == 0) TransferType.UPLOAD else TransferType.DOWNLOAD) }) { Icon(Icons.Default.DeleteSweep, null) }
                    }
                    TabRow(selectedTabIndex = tab, indicator = { tabPositions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tabPositions[tab]), color = MaterialTheme.colorScheme.primary) }) { 
                        Tab(selected = tab == 0, onClick = { tab = 0 }) { Text("Send", Modifier.padding(12.dp), fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                        Tab(selected = tab == 1, onClick = { tab = 1 }) { Text("Receive", Modifier.padding(12.dp), fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
        },
        bottomBar = { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { Text("296™ - Secure High Speed Transfer", fontSize = 10.sp, color = Color.Gray) } }
    ) {
        Column(modifier = Modifier.padding(it).padding(horizontal = 16.dp).fillMaxSize()) {
            Spacer(Modifier.height(16.dp))
            if (tab == 0) {
                Card(elevation = CardDefaults.cardElevation(4.dp), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isValidated) Color.Green else Color.Red))
                            Spacer(Modifier.width(8.dp))
                            Text("Target: $targetDisplayName", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        if (discoveredDevices.isNotEmpty()) {
                            Text("Nearby Devices:", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp), color = Color.Gray)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { 
                                discoveredDevices.forEach { device -> 
                                    FilterChip(
                                        selected = remoteServerIp == device.ip,
                                        onClick = { vm.validateAndConnect(device.ip) }, 
                                        label = { Text(device.name, fontSize = 12.sp) }, 
                                        leadingIcon = { Icon(Icons.Default.Devices, null, Modifier.size(16.dp)) }
                                    ) 
                                } 
                            }
                        }
                        Spacer(Modifier.height(12.dp)); Row(modifier = Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addFiles(ctx, uris, currentTargetIp) }
                            val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.addFolder(ctx, it, currentTargetIp) } }
                            Button(onClick = { if (isValidated) fileLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = isValidated, shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Add, null); Text(" Files") }
                            OutlinedButton(onClick = { if (isValidated) folderLauncher.launch(null) }, Modifier.weight(1f), enabled = isValidated, shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Folder, null); Text(" Folder") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Dashboard(sendStats, "SENDING")
            } else {
                Dashboard(receiveStats, "RECEIVING")
            }
            Spacer(Modifier.height(16.dp))
            Text(if (tab == 0) "Upload History" else "Download History", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) { 
                if (tab == 0) items(sendTransfers, key = { it.id }) { item -> TransferRow(item) } 
                else items(receiveTransfers, key = { it.id }) { item -> TransferRow(item) } 
            }
        }
    }
    if (showQr) QrDialog(localIp) { showQr = false }
    if (showScanner) ScannerDialog(onScan = { scanData -> vm.connectToScannedData(scanData); showScanner = false }, onDismiss = { showScanner = false })
    if (showSettings) { val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.setStoragePath(uri) } }; SettingsDialog(vm.storagePath.collectAsState().value, themeMode, onSetTheme = { mode -> vm.setTheme(mode) }, onSelectPath = { storageLauncher.launch(null) }) { showSettings = false } }
}

@Composable fun Dashboard(s: TransferStats, label: String) {
    val isDark = isSystemInDarkTheme()
    val progress = (if (s.totalSize > 0) s.transferredSize.toFloat() / s.totalSize else 0f).coerceIn(0f, 1f) // Ensure progress is between 0f and 1f
    val animatedProgress by animateFloatAsState(progress, label = "overallProgress")
    val color = if (label == "SENDING") (if (isDark) Color(0xFF311B92) else Color(0xFFE8EAF6)) else (if (isDark) Color(0xFF1B5E20) else Color(0xFFE8F5E9))
    val accentColor = if (label == "SENDING") MaterialTheme.colorScheme.primary else Color(0xFF4CAF50)
    
    val eta = remember(s.transferredSize, s.rawSpeed, s.totalSize) {
        if (s.rawSpeed > 0 && s.isTransferring && s.totalSize > s.transferredSize) {
            val remainingBytes = s.totalSize - s.transferredSize
            val seconds = remainingBytes / s.rawSpeed
            val minutes = (seconds / 60).toInt()
            val remainingSeconds = (seconds % 60).toInt()
            String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
        } else {
            "--:--"
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = accentColor, letterSpacing = 1.sp)
                Text(s.overallSpeed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = accentColor, trackColor = accentColor.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatColumn("Progress", "${(progress * 100).toInt()}%")
                StatColumn("Total Size", format(s.totalSize))
                StatColumn("Files", "${s.completedFiles}/${s.totalFiles}")
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatColumn("Elapsed", s.elapsedTime)
                StatColumn("Remaining", format((s.totalSize - s.transferredSize).coerceAtLeast(0)))
                StatColumn("ETA", eta)
            }
        }
    }
}

@Composable fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Color.Gray)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable fun TransferRow(item: TransferItem) {
    val progress by animateFloatAsState(item.progress, label = "progress")
    val statusColor = when(item.status) {
        TransferStatus.SUCCESS -> Color(0xFF4CAF50)
        TransferStatus.FAILED -> Color(0xFFF44336)
        TransferStatus.CANCELLED -> Color.Gray
        TransferStatus.PAUSED -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.primary
    }
    
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(statusColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    Icon(imageVector = when {
                        item.status == TransferStatus.SUCCESS -> Icons.Default.Check
                        item.status == TransferStatus.FAILED -> Icons.Default.PriorityHigh
                        item.isFolder -> Icons.Default.Folder
                        else -> if (item.type == TransferType.UPLOAD) Icons.Default.FileUpload else Icons.Default.FileDownload
                    }, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (item.status == TransferStatus.SUCCESS) "Completed" else item.speed, fontSize = 11.sp, color = Color.Gray)
                        Text(" • ", fontSize = 11.sp, color = Color.Gray)
                        Text("${format(item.transferredBytes)} / ${format(item.size)}", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                if (item.status == TransferStatus.ACTIVE) {
                    Text("${(item.progress * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = statusColor)
                }
            }
            if (item.status == TransferStatus.ACTIVE || item.status == TransferStatus.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = statusColor, trackColor = statusColor.copy(alpha = 0.1f))
            }
        }
    }
}

@Composable fun SettingsDialog(path: Uri?, theme: Int, onSetTheme: (Int) -> Unit, onSelectPath: () -> Unit, onDismiss: () -> Unit) { Dialog(onDismissRequest = onDismiss) { Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(24.dp)) { Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp); Spacer(Modifier.height(16.dp)); Text("Theme", style = MaterialTheme.typography.titleSmall); Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = theme == AppCompatDelegate.MODE_NIGHT_NO, onClick = { onSetTheme(AppCompatDelegate.MODE_NIGHT_NO) }); Text("Light"); Spacer(Modifier.width(8.dp)); RadioButton(selected = theme == AppCompatDelegate.MODE_NIGHT_YES, onClick = { onSetTheme(AppCompatDelegate.MODE_NIGHT_YES) }); Text("Dark") }; Spacer(Modifier.height(16.dp)); Text("Storage Path:", style = MaterialTheme.typography.bodySmall); Text(path?.path ?: "Internal ARC_FLASH", fontSize = 12.sp, color = Color.Gray); Button(onClick = onSelectPath, Modifier.padding(top = 8.dp)) { Text("Change") }; HorizontalDivider(Modifier.padding(vertical = 16.dp)); Text("About", fontWeight = FontWeight.Bold); Text("ARC FLASH High-Speed LAN Transfer", fontSize = 12.sp); Text("296™", fontSize = 10.sp, color = Color.Gray); Button(onClick = onDismiss, Modifier.align(Alignment.End).padding(top = 16.dp)) { Text("Close") } } } } }
@Composable fun QrDialog(ip: String, onDismiss: () -> Unit) { val fullUrl = "http://$ip:5000"; Dialog(onDismissRequest = onDismiss) { Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("Scan to Connect", fontWeight = FontWeight.Bold); Text(fullUrl, fontSize = 12.sp, color = Color.Gray); Spacer(Modifier.height(8.dp)); val bitmap = remember(fullUrl) { generateQr(fullUrl) }; if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(240.dp)); Button(onClick = onDismiss, Modifier.padding(top = 16.dp)) { Text("Close") } } } } }
@OptIn(ExperimentalGetImage::class) @Composable fun ScannerDialog(onScan: (String) -> Unit, onDismiss: () -> Unit) { val owner = LocalLifecycleOwner.current; var perm by remember { mutableStateOf(false) }; val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }; LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }; Dialog(onDismissRequest = onDismiss) { Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(400.dp)) { if (perm) AndroidView(factory = { c -> val pv = PreviewView(c); val p = ProcessCameraProvider.getInstance(c); p.addListener({ val provider = p.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }; val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build(); val scanner = BarcodeScanning.getClient(); analysis.setAnalyzer(ContextCompat.getMainExecutor(c)) { proxy -> val media = proxy.image; if (media != null) { val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees); scanner.process(image).addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let { onScan(it) } }.addOnCompleteListener { proxy.close() } } else proxy.close() }; try { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) } catch (e: Exception) { Log.e("ScannerDialog", "Camera initialization error", e) } }, ContextCompat.getMainExecutor(c)); pv }, modifier = Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Need Camera") } } } }

fun generateQr(text: String): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        Log.e("QRGen", "QR Generation error", e)
        null
    }
}

fun format(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val exp = (log(size.toDouble(), 1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(exp.toDouble()), units[exp])
}