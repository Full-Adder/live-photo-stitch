package com.example.livephotostitch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.engine.PhotoEffect
import com.example.livephotostitch.utils.XiaomiIntegration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    initialEffects: List<PhotoEffect> = List(3) { PhotoEffect() },
    onEffectsChanged: (List<PhotoEffect>) -> Unit = {},
    onNextClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTool by remember { mutableStateOf<EditTool?>(null) }
    // 从全局状态读取，确保与外部同步；如果全局状态不足则用 initialEffects 补充
    var effects by remember {
        mutableStateOf(
            StitchProjectState.effects.takeIf { it.size >= 3 }
                ?: initialEffects.takeIf { it.size >= 3 }
                ?: List(3) { PhotoEffect() }
        )
    }

    val isXiaomi = XiaomiIntegration.isXiaomiDevice()
    val photos = StitchProjectState.photos
    val template = StitchProjectState.template

    // 同步效果到外部
    LaunchedEffect(effects) {
        onEffectsChanged(effects)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onNextClick) {
                        Text("预览")
                    }
                }
            )
        },
        bottomBar = {
            EditBottomBar(
                selectedTool = selectedTool,
                onToolSelected = { selectedTool = it },
                isXiaomi = isXiaomi
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 主编辑区域 —— 显示真实照片的拼接预览
            StitchPreviewArea(
                photos = photos,
                template = template,
                selectedPhotoIndex = selectedPhotoIndex,
                effects = effects,
                onPhotoSelected = { index ->
                    selectedPhotoIndex = if (selectedPhotoIndex == index) null else index
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 64.dp)
            )

            // 选中照片指示器
            if (selectedPhotoIndex != null) {
                SelectedPhotoIndicator(
                    photoIndex = selectedPhotoIndex!!,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                )
            }

            // 工具面板 — 位于底部工具栏上方
            if (selectedTool != null && selectedPhotoIndex != null) {
                ToolPanel(
                    tool = selectedTool!!,
                    currentEffect = effects[selectedPhotoIndex!!],
                    onEffectChange = { newEffect ->
                        effects = effects.toMutableList().apply {
                            set(selectedPhotoIndex!!, newEffect)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                )
            } else if (selectedTool != null && selectedPhotoIndex == null) {
                // 任何工具都需要先选中一张照片
                TipCard(
                    text = "请先点击选择一张照片，再使用「${selectedTool!!.displayName}」",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }

            // 小米专属提示
            if (isXiaomi && selectedTool == EditTool.CUTOUT && selectedPhotoIndex != null) {
                XiaomiFeatureHint(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                )
            }
        }
    }
}

/**
 * 拼接预览区域 —— 显示真实照片
 * 使用 BoxWithConstraints 来正确计算百分比布局
 */
@Composable
private fun StitchPreviewArea(
    photos: List<com.example.livephotostitch.data.model.LivePhoto>,
    template: com.example.livephotostitch.data.model.StitchTemplate?,
    selectedPhotoIndex: Int?,
    effects: List<PhotoEffect>,
    onPhotoSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (photos.isEmpty() || template == null) {
            // 没有照片时显示占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("请先选择照片", color = Color.Gray)
            }
        } else {
            // 显示真实照片的拼接预览
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(template.aspectRatio)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val boxWidthPx = constraints.maxWidth.toFloat()
                val boxHeightPx = constraints.maxHeight.toFloat()

                // 按照模板布局显示每张照片
                template.layout.forEachIndexed { index, item ->
                    if (index < photos.size) {
                        val isSelected = selectedPhotoIndex == index
                        val effect = effects.getOrNull(index) ?: PhotoEffect()

                        // 计算实际像素位置和大小，然后转换为 dp
                        val cellWidthDp = with(density) { (item.width * boxWidthPx).toDp() }
                        val cellHeightDp = with(density) { (item.height * boxHeightPx).toDp() }
                        val cellXDp = with(density) { (item.x * boxWidthPx).toDp() }
                        val cellYDp = with(density) { (item.y * boxHeightPx).toDp() }

                        Box(
                            modifier = Modifier
                                .absoluteOffset(
                                    x = cellXDp,
                                    y = cellYDp
                                )
                                .size(
                                    width = cellWidthDp,
                                    height = cellHeightDp
                                )
                                .clip(RoundedCornerShape(4.dp))
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable { onPhotoSelected(index) }
                        ) {
                            AsyncImage(
                                model = photos[index].staticImageUri,
                                contentDescription = "照片 ${index + 1}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // 与引擎一致的取景预览：先缩放，再按偏移平移
                                        scaleX = effect.backgroundZoom
                                        scaleY = effect.backgroundZoom
                                        translationX = -effect.cropOffsetX * size.width * 0.25f
                                        translationY = -effect.cropOffsetY * size.height * 0.25f
                                    },
                                contentScale = ContentScale.Crop
                            )

                            // 效果标识
                            if (effect.cutoutEnabled && effect.zoomLevel > 1.0f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                            CircleShape
                                        )
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "✨${(effect.zoomLevel * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPhotoIndicator(
    photoIndex: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "正在编辑照片 ${photoIndex + 1}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TipCard(
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun EditBottomBar(
    selectedTool: EditTool?,
    onToolSelected: (EditTool?) -> Unit,
    isXiaomi: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EditTool.values().forEach { tool ->
                ToolButton(
                    tool = tool,
                    isSelected = selectedTool == tool,
                    isXiaomi = isXiaomi,
                    onClick = {
                        onToolSelected(if (selectedTool == tool) null else tool)
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    tool: EditTool,
    isSelected: Boolean,
    isXiaomi: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = tool.displayName,
                tint = if (isSelected) Color.White else Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tool.displayName,
                fontSize = 12.sp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
            )

            if (tool == EditTool.CUTOUT && isXiaomi) {
                Text(
                    text = " 🌟",
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun ToolPanel(
    tool: EditTool,
    currentEffect: PhotoEffect,
    onEffectChange: (PhotoEffect) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        when (tool) {
            EditTool.CROP -> {
                CropPanel(
                    effect = currentEffect,
                    onEffectChange = onEffectChange
                )
            }

            EditTool.CUTOUT -> {
                CutoutPanel(
                    effect = currentEffect,
                    onEffectChange = onEffectChange
                )
            }

            EditTool.FILTER -> {
                FilterPanel()
            }

            EditTool.ROTATE -> {
                RotatePanel()
            }
        }
    }
}

/**
 * 裁剪面板 —— 每张照片单独的取景范围
 */
@Composable
private fun CropPanel(
    effect: PhotoEffect,
    onEffectChange: (PhotoEffect) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "裁剪 / 缩放",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "调整这张照片在格子里的取景范围",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 画面缩放
        Text(
            text = "缩放: ${(effect.backgroundZoom * 100).toInt()}%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        Slider(
            value = effect.backgroundZoom,
            onValueChange = { onEffectChange(effect.copy(backgroundZoom = it)) },
            valueRange = 1f..2f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 水平位置
        Text(text = "左右位置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = effect.cropOffsetX,
            onValueChange = { onEffectChange(effect.copy(cropOffsetX = it)) },
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        // 垂直位置
        Text(text = "上下位置", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = effect.cropOffsetY,
            onValueChange = { onEffectChange(effect.copy(cropOffsetY = it)) },
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        // 重置
        TextButton(
            onClick = {
                onEffectChange(
                    effect.copy(cropOffsetX = 0f, cropOffsetY = 0f, backgroundZoom = 1f)
                )
            }
        ) {
            Text("重置裁剪")
        }
    }
}

/**
 * AI 人物突出面板
 */
@Composable
private fun CutoutPanel(
    effect: PhotoEffect,
    onEffectChange: (PhotoEffect) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "AI 人物突出",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "自动识别人物，放大并超出边框",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 启用开关
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("启用 AI 突出", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = effect.cutoutEnabled,
                onCheckedChange = { enabled ->
                    onEffectChange(
                        effect.copy(
                            cutoutEnabled = enabled,
                            // 打开时给一个默认放大倍数，否则引擎会认为"没设置"而跳过
                            zoomLevel = if (enabled && effect.zoomLevel <= 1.0f) 1.4f
                            else effect.zoomLevel
                        )
                    )
                }
            )
        }

        if (effect.cutoutEnabled) {
            Spacer(modifier = Modifier.height(16.dp))

            // 放大倍数
            Text(
                text = "人物放大: ${(effect.zoomLevel * 100).toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Slider(
                value = effect.zoomLevel,
                onValueChange = { onEffectChange(effect.copy(zoomLevel = it)) },
                valueRange = 1.0f..3.0f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 人物位置微调
            Text(
                text = "人物位置",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("水平", fontSize = 12.sp, color = Color.Gray)
                Slider(
                    value = effect.personCenterX,
                    onValueChange = { onEffectChange(effect.copy(personCenterX = it)) },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text("${(effect.personCenterX * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("垂直", fontSize = 12.sp, color = Color.Gray)
                Slider(
                    value = effect.personCenterY,
                    onValueChange = { onEffectChange(effect.copy(personCenterY = it)) },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text("${(effect.personCenterY * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

/**
 * 滤镜面板
 */
@Composable
private fun FilterPanel() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "滤镜",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("原图", "清晰", "柔和", "复古").forEach { filter ->
                FilterChip(
                    selected = filter == "原图",
                    onClick = { },
                    label = { Text(filter) }
                )
            }
        }
    }
}

/**
 * 旋转面板
 */
@Composable
private fun RotatePanel() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "旋转",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { /* 左旋 */ }) {
                Icon(Icons.Default.RotateLeft, contentDescription = "左旋")
            }
            IconButton(onClick = { /* 右旋 */ }) {
                Icon(Icons.Default.RotateRight, contentDescription = "右旋")
            }
            IconButton(onClick = { /* 翻转 */ }) {
                Icon(Icons.Default.Flip, contentDescription = "翻转")
            }
        }
    }
}

@Composable
private fun XiaomiFeatureHint(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🌟", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "AI 人物突出",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "使用 ML Kit 人像分割，人物可超出边框",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

enum class EditTool(val displayName: String, val icon: ImageVector) {
    CROP("裁剪", Icons.Default.Crop),
    CUTOUT("AI突出", Icons.Default.ContentCut),
    FILTER("滤镜", Icons.Default.Filter),
    ROTATE("旋转", Icons.Default.RotateRight)
}
