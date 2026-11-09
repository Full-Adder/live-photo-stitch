package com.example.livephotostitch.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.engine.LivePhotoStitchEngine
import com.example.livephotostitch.engine.MlKitPortraitCutoutProcessor
import com.example.livephotostitch.utils.XiaomiIntegration
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBackClick: () -> Unit,
    onHomeClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isProcessing by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableFloatStateOf(0f) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }

    val isXiaomi = XiaomiIntegration.isXiaomiDevice()

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
                    IconButton(onClick = { /* 分享 */ }) {
                        Icon(Icons.Default.Share, contentDescription = "分享")
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onHomeClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Home, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("首页")
                    }

                    Button(
                        onClick = {
                            showSaveDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isProcessing) "保存中..." else "保存")
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 预览区域
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (resultUri != null) {
                    // 显示处理后的结果
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        AsyncImage(
                            model = resultUri,
                            contentDescription = "拼接结果",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // 未处理时的占位
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.LightGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "实况照片预览",
                                    color = Color.Gray,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "长按查看动态效果",
                                    color = Color.Gray.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 小米优化提示
            if (isXiaomi) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🌟", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "小米AI优化已启用",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 处理中指示器
            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("正在生成实况照片...")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { processingProgress },
                                modifier = Modifier.width(200.dp)
                            )
                        }
                    }
                }
            }

            // 保存成功提示
            if (isSaved) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    isSaved = false
                }

                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "保存成功！",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // 保存选项对话框
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存选项") },
            text = {
                Column {
                    Text("选择保存格式：")
                    Spacer(modifier = Modifier.height(12.dp))

                    var saveAsLivePhoto by remember { mutableStateOf(true) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = saveAsLivePhoto,
                            onCheckedChange = { saveAsLivePhoto = it }
                        )
                        Text("保存为实况照片")
                    }

                    if (saveAsLivePhoto) {
                        Text(
                            text = "包含静态图片和短视频",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 36.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        isProcessing = true
                        processingProgress = 0f

                        scope.launch {
                            try {
                                // 执行拼接
                                val result = processStitch(context)
                                if (result != null) {
                                    resultUri = result
                                    isSaved = true
                                }
                            } finally {
                                isProcessing = false
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

/**
 * 执行拼接处理
 */
private suspend fun processStitch(context: Context): Uri? {
    val photos = StitchProjectState.photos
    val template = StitchProjectState.template ?: return null
    val effects = StitchProjectState.effects

    if (photos.size != 3) return null

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

    return result.getOrNull()?.staticImageUri
}
