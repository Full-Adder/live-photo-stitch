package com.example.livephotostitch.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import coil.compose.AsyncImage
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.engine.LivePhotoStitchEngine
import com.example.livephotostitch.engine.MlKitPortraitCutoutProcessor
import com.example.livephotostitch.engine.StitchResult
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

    // 进入页面自动开始处理，retryKey 变化时重新处理
    LaunchedEffect(retryKey) {
        isProcessing = true
        processingProgress = 0f
        isSaved = false
        errorMessage = null

        scope.launch {
            try {
                // 模拟进度
                for (i in 1..10) {
                    delay(200)
                    processingProgress = i / 10f
                }

                val processResult = processStitch(context)

                if (processResult != null) {
                    result = processResult
                    stitchedBitmap = loadBitmap(context, processResult.staticImageUri)
                    isProcessing = false
                } else {
                    errorMessage = "处理失败，请重试"
                    isProcessing = false
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "处理失败"
                isProcessing = false
            }
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
                        isSaving = true

                        scope.launch {
                            try {
                                val saved = saveResult(
                                    context = context,
                                    result = result!!
                                )
                                if (saved) {
                                    isSaved = true
                                } else {
                                    errorMessage = "保存失败，请重试"
                                }
                            } finally {
                                isSaving = false
                            }
                        }
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
 */
private suspend fun saveResult(
    context: Context,
    result: StitchResult
): Boolean = withContext(Dispatchers.IO) {
    try {
        // 保存静态图
        saveImageToGallery(context, result.staticImageUri)

        // 保存视频（实况照片的动态部分）
        result.videoUri?.let { videoUri ->
            saveVideoToGallery(context, videoUri)
        }

        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 保存图片到相册
 */
private fun saveImageToGallery(context: Context, imageUri: Uri) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "LivePhoto_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LivePhotoStitch")
        }
    }

    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { output ->
            context.contentResolver.openInputStream(imageUri)?.use { input ->
                input.copyTo(output)
            }
        }
    }
}

/**
 * 保存视频到相册
 */
private fun saveVideoToGallery(context: Context, videoUri: Uri) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "LivePhoto_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LivePhotoStitch")
        }
    }

    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { output ->
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                input.copyTo(output)
            }
        }
    }
}
