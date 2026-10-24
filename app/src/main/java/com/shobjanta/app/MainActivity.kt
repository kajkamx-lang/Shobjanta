package com.shobjanta.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

class Pend(val src: Bitmap, q0: FloatArray) {
    var q by mutableStateOf(q0)
    var f by mutableIntStateOf(1)
    var rot by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9DB7FF))
                else lightColorScheme(primary = Color(0xFF2347C5))
            ) { App() }
        }
    }
}

fun share(c: Context, files: List<File>, mime: String) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(c, c.packageName + ".fp", it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = mime; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, "Share"))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("home") }
    val pend = remember { mutableStateListOf<Pend>() }
    val extra = remember { mutableStateListOf<String>() }
    var open by remember { mutableStateOf<File?>(null) }
    var target by remember { mutableStateOf<File?>(null) }
    var folder by remember { mutableStateOf("") }
    var idMode by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf<Pend?>(null) }
    val docs = remember(tick, screen) { Store.all() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            withContext(Dispatchers.Default) {
                for (u in uris) {
                    val f = File(ctx.cacheDir, "i" + System.nanoTime() + ".jpg")
                    ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    val b = Scan.load(f)
                    if (b != null) { val q = Scan.detect(b); withContext(Dispatchers.Main) { pend.add(Pend(b, q)) } }
                    f.delete()
                }
            }
            busy = false; if (pend.isNotEmpty()) screen = "review"
        }
    }

    fun save() {
        scope.launch {
            busy = true
            val d = withContext(Dispatchers.Default) {
                val dir = target ?: Store.create("Scan " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), folder)
                val outs = if (idMode) pend.chunked(2).map { g -> Scan.idCard(g.map { Scan.render(it.src, it.q, it.f, it.rot, 1400) }) }
                else pend.map { Scan.render(it.src, it.q, it.f, it.rot, 2000) }
                outs.forEach { Store.addPage(dir, it) }
                dir
            }
            pend.clear(); target = null; open = d; busy = false; tick++; screen = "doc"
        }
    }

    BackHandler(screen != "home") {
        when (screen) {
            "cam" -> { pend.clear(); screen = if (target != null) "doc" else "home" }
            "review" -> screen = "cam"
            else -> screen = "home"
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (screen) {
            "home" -> {
                var newF by remember { mutableStateOf(false) }
                val folders = (docs.map { it.folder }.filter { it.isNotEmpty() } + extra).distinct()
                val shown = docs.filter { folder.isEmpty() || it.folder == folder }
                Scaffold(containerColor = Color.Transparent, floatingActionButton = {
                    ExtendedFloatingActionButton(onClick = { target = null; screen = "cam" },
                        icon = { Icon(Icons.Default.Add, null) }, text = { Text("Scan") })
                }) { pad ->
                    Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Shobjanta", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            OutlinedButton({ target = null; pick.launch("image/*") }) { Text("Import") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(folder.isEmpty(), { folder = "" }, { Text("All") }) }
                            items(folders) { f -> FilterChip(folder == f, { folder = f }, { Text(f) }) }
                            item { AssistChip({ newF = true }, { Text("New folder") }) }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("No documents yet. Tap Scan to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
                            items(shown, key = { it.dir.name }) { d ->
                                Card(Modifier.clickable { open = d.dir; screen = "doc" }) {
                                    Thumb(d.pages.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(.75f))
                                    Column(Modifier.padding(10.dp)) {
                                        Text(d.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                        Text("${d.pages.size} pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                if (newF) {
                    var t by remember { mutableStateOf("") }
                    AlertDialog(onDismissRequest = { newF = false }, title = { Text("New folder") },
                        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
                        confirmButton = { TextButton({ if (t.isNotBlank()) { extra.add(t.trim()); folder = t.trim() }; newF = false }) { Text("Create") } })
                }
            }
            "cam" -> Cam(pend.size, idMode, { idMode = it }, { f ->
                scope.launch(Dispatchers.Default) {
                    val b = Scan.load(f); f.delete()
                    if (b != null) { val q = Scan.detect(b); withContext(Dispatchers.Main) { pend.add(Pend(b, q)) } }
                }
            }, { if (pend.isNotEmpty()) screen = "review" }, { pend.clear(); screen = if (target != null) "doc" else "home" })
            "review" -> Column(Modifier.fillMaxSize()) {
                Text("Review (${pend.size})", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(pend.toList()) { p ->
                        Card { Column(Modifier.padding(12.dp)) {
                            Pv(p)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Original", "Color", "B&W", "Gray").forEachIndexed { k, n -> FilterChip(p.f == k, { p.f = k }, { Text(n) }) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ crop = p }) { Text("Crop") }
                                OutlinedButton({ p.rot = (p.rot + 1) % 4 }) { Text("Rotate") }
                                OutlinedButton({ pend.remove(p); if (pend.isEmpty()) screen = "cam" }) { Text("Remove") }
                            }
                        } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ screen = "cam" }, Modifier.weight(1f)) { Text("Add more") }
                    Button({ save() }, Modifier.weight(1f)) { Text("Save") }
                }
            }
            "doc" -> {
                val d = docs.firstOrNull { it.dir == open }
                if (d == null) LaunchedEffect(Unit) { screen = "home" } else {
                    var name by remember(d.dir) { mutableStateOf(d.name) }
                    var fd by remember { mutableStateOf(false) }
                    var del by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ screen = "home" }) { Icon(Icons.Default.ArrowBack, "Back") }
                            OutlinedTextField(name, { name = it; Store.meta(d.dir, "name", it) }, Modifier.weight(1f), singleLine = true)
                        }
                        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.pages, key = { it.name }) { p ->
                                Box {
                                    Thumb(p, Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(12.dp)))
                                    IconButton({ Store.removePage(p); tick++ }, Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Remove page", tint = Color.White) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton({ target = d.dir; screen = "cam" }, Modifier.weight(1f)) { Text("Add pages") }
                            Button({
                                scope.launch {
                                    busy = true
                                    val f = withContext(Dispatchers.IO) {
                                        val o = File(ctx.cacheDir, "share").apply { mkdirs() }
                                        File(o, name.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "scan" } + ".pdf").also { Scan.pdf(d.pages, it) }
                                    }
                                    busy = false; share(ctx, listOf(f), "application/pdf")
                                }
                            }, Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("PDF") }
                            OutlinedButton({ share(ctx, d.pages, "image/jpeg") }, Modifier.weight(1f)) { Text("Images") }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ fd = true }, Modifier.weight(1f)) { Text("Folder") }
                            OutlinedButton({ del = true }, Modifier.weight(1f)) { Text("Delete") }
                        }
                    }
                    if (fd) {
                        var t by remember { mutableStateOf(d.folder) }
                        AlertDialog(onDismissRequest = { fd = false }, title = { Text("Move to folder") },
                            text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Empty = no folder") }) },
                            confirmButton = { TextButton({ Store.meta(d.dir, "folder", t.trim()); fd = false; tick++ }) { Text("Save") } })
                    }
                    if (del) AlertDialog(onDismissRequest = { del = false }, title = { Text("Delete this document?") },
                        dismissButton = { TextButton({ del = false }) { Text("Cancel") } },
                        confirmButton = { TextButton({ Store.delete(d.dir); del = false; tick++; screen = "home" }) { Text("Delete") } })
                }
            }
        }
        crop?.let { p ->
            Dialog({ crop = null }, DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { CropView(p) { crop = null } }
            }
        }
        if (busy) Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = {}), Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun Thumb(f: File?, m: Modifier) {
    val b by produceState<Bitmap?>(null, f) {
        value = f?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
    }
    Box(m.background(MaterialTheme.colorScheme.surfaceVariant)) {
        b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun Pv(p: Pend) {
    val b by produceState<Bitmap?>(null, p.q, p.f, p.rot) {
        value = withContext(Dispatchers.Default) { Scan.render(p.src, p.q, p.f, p.rot, 900) }
    }
    b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth) }
}

@Composable
fun CropView(p: Pend, onDone: () -> Unit) {
    var q by remember { mutableStateOf(p.q.copyOf()) }
    val img = remember(p) { p.src.asImageBitmap() }
    var act by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
            fun geo(): FloatArray {
                val sc = minOf(size.width / img.width.toFloat(), size.height / img.height.toFloat())
                return floatArrayOf(sc, (size.width - img.width * sc) / 2, (size.height - img.height * sc) / 2)
            }
            detectDragGestures(onDragStart = { o ->
                val g = geo()
                act = (0..3).minByOrNull { i -> hypot(g[1] + q[i * 2] * img.width * g[0] - o.x, g[2] + q[i * 2 + 1] * img.height * g[0] - o.y) } ?: 0
            }, onDrag = { c, _ ->
                c.consume(); val g = geo(); val n = q.copyOf()
                n[act * 2] = ((c.position.x - g[1]) / (img.width * g[0])).coerceIn(0f, 1f)
                n[act * 2 + 1] = ((c.position.y - g[2]) / (img.height * g[0])).coerceIn(0f, 1f)
                q = n
            })
        }) {
            val sc = minOf(size.width / img.width, size.height / img.height)
            val ox = (size.width - img.width * sc) / 2; val oy = (size.height - img.height * sc) / 2
            drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((img.width * sc).toInt(), (img.height * sc).toInt()))
            val pts = (0..3).map { Offset(ox + q[it * 2] * img.width * sc, oy + q[it * 2 + 1] * img.height * sc) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..3) lineTo(pts[i].x, pts[i].y); close() }
            drawPath(path, Color(0x331F8BFF)); drawPath(path, Color(0xFF4DA3FF), style = Stroke(4f))
            pts.forEach { drawCircle(Color.White, 22f, it); drawCircle(Color(0xFF2347C5), 16f, it) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton({ q = Scan.detect(p.src) }) { Text("Auto") }
            TextButton({ q = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f) }) { Text("Full image") }
            Button({ p.q = q; onDone() }) { Text("Done") }
        }
    }
}

@Composable
fun Cam(count: Int, idMode: Boolean, onId: (Boolean) -> Unit, onShot: (File) -> Unit, onDone: () -> Unit, onCancel: () -> Unit) {
    val act = LocalContext.current as ComponentActivity
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) { onDispose { try { ProcessCameraProvider.getInstance(act).get().unbindAll() } catch (e: Exception) {} } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) AndroidView({ c ->
                PreviewView(c).also { pv ->
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        val pr = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        fut.get().unbindAll()
                        fut.get().bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, pr, capture)
                    }, ContextCompat.getMainExecutor(c))
                }
            }, Modifier.fillMaxSize())
            else Text("Camera permission is required", color = Color.White, modifier = Modifier.align(Alignment.Center))
            FilterChip(idMode, { onId(!idMode) }, { Text("ID card mode") }, Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onCancel, Modifier.width(96.dp)) { Text("Cancel", color = Color.White) }
            Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable {
                val f = File(act.cacheDir, "c" + System.nanoTime() + ".jpg")
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(act),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { onShot(f) }
                        override fun onError(e: ImageCaptureException) {}
                    })
            })
            TextButton(onDone, Modifier.width(96.dp)) { Text("Done ($count)", color = Color.White) }
        }
    }
}
