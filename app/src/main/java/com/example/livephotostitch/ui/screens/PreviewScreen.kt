package com.example.livephotostitch.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.engine.LivePhotoStitchEngine
import com.example.livephotostitch.engine.MlKitPortraitCutoutProcessor
import com.example.livephotostitch.engine.StitchResult
import com.example.livephotostitch.utils.MotionPhotoWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBackClick: () -> Unit,
    onDoneClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isProcessing by remember { mutableStateOf(true) }
    var processingProgress by remember { mutableStateOf(0f) }
    var stitchedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var result by remember { mutableStateOf<StitchResult?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var saveRequested by remember { mutableStateOf(false) }

    // Android 8/9 需要写外部存储权限；10+ 走 MediaStore 不需要
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveRequested = true
        } else {
            errorMessage = "没有存储权限，无法保存到相册"
        }
    }

    fun startSave() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        saveRequested = true
    }

    // 真正的保存动作
    LaunchedEffect(saveRequested) {
        if (!saveRequested) return@LaunchedEffect
        saveRequested = false

        val current = result ?: return@LaunchedEffect

        isSaving = true
        try {
            val saved = saveResult(context = context, result = current)
            if (saved) {
                isSaved = true
            } else {
                errorMessage = "保存失败，请重试"
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "保存失败"
        } finally {
            isSaving = false
        }
    }

    // 进入页面自动开始处理，retryKey 变化时重新处理
    LaunchedEffect(retryKey) {
        isProcessing = true
        processingProgress = 0f
        isSaved = false
        errorMessage = null
        stitchedBitmap = null
        result = null

        // 进度条：处理期间缓慢推进到 90%，完成后再补到 100%（不做假等待）
        val progressJob = launch {
            var p = 0f
            while (p < 0.9f) {
                delay(150)
                p = (p + 0.03f).coerceAtMost(0.9f)
                processingProgress = p
            }
        }

        try {
            val processResult = processStitch(context)

            if (processResult != null) {
                result = processResult
                stitchedBitmap = loadBitmap(context, processResult.staticImageUri)
                processingProgress = 1f
            } else {
                errorMessage = "处理失败，请重试"
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "处理失败"
        } finally {
            progressJob.cancel()
            isProcessing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("预览") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.Save, contentDescription = "保存")
                    }
                    IconButton(onClick = onDoneClick) {
                        Icon(Icons.Default.Check, contentDescription = "完成")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = onBackClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("返回")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing && !isSaving && result != null
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(if (isSaved) "已保存 ✓" else "保存")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when {
                isProcessing -> {
                    ProcessingIndicator(progress = processingProgress)
                }
                errorMessage != null -> {
                    ErrorMessage(
                        message = errorMessage!!,
                        onRetry = {
                            retryKey++
                        }
                    )
                }
                stitchedBitmap != null -> {
                    ResultPreview(
                        bitmap = stitchedBitmap!!,
                        isLivePhoto = result?.isLivePhoto == true,
                        isSaved = isSaved
                    )
                }
                else -> {
                    Text("等待处理...")
                }
            }
        }
    }

    // 保存对话框
    if (showSaveDialog && result != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存到相册") },
            text = {
                Column {
                    Text("保存拼接后的实况照片到系统相册")
                    if (result?.isLivePhoto == true) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✅ 包含动态效果",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        startSave()
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ProcessingIndicator(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            strokeWidth = 6.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "正在拼接处理...",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${(progress * 100).toInt()}%",
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun ErrorMessage(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

@Composable
private fun ResultPreview(
    bitmap: android.graphics.Bitmap,
    isLivePhoto: Boolean,
    isSaved: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            AsyncImage(
                model = bitmap,
                contentDescription = "拼接结果",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // 实况照片标识
            if (isLivePhoto) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "实况照片",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 保存成功提示
            if (isSaved) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(
                            Color(0xFF4CAF50).copy(alpha = 0.9f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "✅ 已保存到相册",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 执行拼接处理
 */
private suspend fun processStitch(context: Context): StitchResult? = withContext(Dispatchers.IO) {
    val photos = StitchProjectState.photos
    val template = StitchProjectState.template ?: return@withContext null
    val effects = StitchProjectState.effects

    if (photos.isEmpty()) return@withContext null

    val engine = LivePhotoStitchEngine(context)
    val cutoutProcessor = MlKitPortraitCutoutProcessor(context)

    val outputDir = File(context.cacheDir, "output").apply { mkdirs() }

    val result = engine.stitchLivePhotos(
        photos = photos,
        template = template,
        outputDir = outputDir,
        effects = effects,
        cutoutProcessor = cutoutProcessor
    )

    result.getOrNull()
}

/**
 * 加载 Bitmap
 */
private fun loadBitmap(context: Context, uri: Uri): android.graphics.Bitmap? {
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        android.graphics.BitmapFactory.decodeStream(stream)
    }
}

/**
 * 保存结果到相册
 *
 * 有视频时写成真正的 Motion Photo（JPEG + 内嵌 MP4 + XMP），
 * 这样系统相册会把它识别成「实况照片」并播放动态效果；
 * 而不是把 JPG 和 MP4 拆成两个文件（那样相册里只是张静态图）。
 */
private suspend fun saveResult(
    context: Context,
    result: StitchResult
): Boolean = withContext(Dispatchers.IO) {
    try {
        val jpegBytes = context.contentResolver.openInputStream(result.staticImageUri)
            ?.use { it.readBytes() }
            ?: return@withContext false

        val mp4Bytes = result.videoUri?.let { videoUri ->
            context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
        }

        var savedAsMotionPhoto = false

        if (mp4Bytes != null && mp4Bytes.isNotEmpty()) {
            val merged = MotionPhotoWriter.createMotionPhoto(jpegBytes, mp4Bytes)
            if (merged != null) {
                savedAsMotionPhoto = saveBytesToGallery(
                    context = context,
                    fileName = "LivePhoto_${System.currentTimeMillis()}.jpg",
                    bytes = merged
                )
            }
        }

        if (savedAsMotionPhoto) {
            return@withContext true
        }

        // 没有视频，或合成失败 → 至少把静态图存进去，并如实返回结果
        saveBytesToGallery(
            context = context,
            fileName = "LivePhoto_${System.currentTimeMillis()}.jpg",
            bytes = jpegBytes
        )
    } catch (e: Exception) {
        false
    }
}

/**
 * 把字节写入系统相册（Images）
 */
private fun saveBytesToGallery(context: Context, fileName: String, bytes: ByteArray): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val uri = try {
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    } ?: return false

    return try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
            output.flush()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, done, null, null)
        }
        true
    } catch (e: Exception) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e2: Exception) {
            // 忽略
        }
        false
    }
}
