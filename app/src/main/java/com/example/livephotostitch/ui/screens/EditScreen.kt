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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.livephotostitch.engine.PhotoEffect
import com.example.livephotostitch.utils.XiaomiIntegration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    /** 初始效果配置 */
    initialEffects: List<PhotoEffect> = List(3) { PhotoEffect() },
    onEffectsChanged: (List<PhotoEffect>) -> Unit = {},
    onNextClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var selectedPhotoIndex by remember { mutableStateOf<Int?>(null) }
    var selectedTool by remember { mutableStateOf<EditTool?>(null) }
    var effects by remember { mutableStateOf(initialEffects) }

    val isXiaomi = XiaomiIntegration.isXiaomiDevice()

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
                    IconButton(onClick = { /* 撤销 */ }) {
                        Icon(Icons.Default.Undo, contentDescription = "撤销")
                    }
                    IconButton(onClick = { /* 重做 */ }) {
                        Icon(Icons.Default.Redo, contentDescription = "重做")
                    }
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
            // 主编辑区域 —— 点击选中某张照片
            StitchPreviewArea(
                selectedPhotoIndex = selectedPhotoIndex,
                effects = effects,
                onPhotoSelected = { index ->
                    selectedPhotoIndex = if (selectedPhotoIndex == index) null else index
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp)
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

            // 工具面板
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
                        .padding(bottom = 80.dp)
                )
            } else if (selectedTool == CUTOUT_TOOL && selectedPhotoIndex == null) {
                // 提示用户先选择照片
                TipCard(
                    text = "请先点击选择一张照片，再使用抠图功能",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }

            // 小米专属提示
            if (isXiaomi && selectedTool == CUTOUT_TOOL) {
                XiaomiFeatureHint(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (selectedPhotoIndex != null) 64.dp else 16.dp)
                )
            }
        }
    }
}

/**
 * 拼接预览区域 —— 支持点击选中某张照片
 */
@Composable
private fun StitchPreviewArea(
    selectedPhotoIndex: Int?,
    effects: List<PhotoEffect>,
    onPhotoSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 使用模板布局显示3个区域
        // 这里简化为横向三格布局，实际应根据模板渲染
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) { index ->
                val isSelected = selectedPhotoIndex == index
                val effect = effects[index]

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (effect.cutoutEnabled && effect.zoomLevel > 1.0f)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                Color.Gray.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onPhotoSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "照片 ${index + 1}",
                            tint = Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "照片 ${index + 1}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        if (effect.cutoutEnabled && effect.zoomLevel > 1.0f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "✨ ${(effect.zoomLevel * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
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

            if (tool == CUTOUT_TOOL && isXiaomi) {
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
            EditTool.ZOOM -> {
                ZoomPanel(
                    zoomLevel = currentEffect.zoomLevel,
                    onZoomLevelChange = { onEffectChange(currentEffect.copy(zoomLevel = it)) }
                )
            }

            EditTool.CUTOUT -> {
                CutoutPanel(
                    effect = currentEffect,
                    onEffectChange = onEffectChange
                )
            }

            EditTool.FILTER -> {
                FilterPanel(
                    effect = currentEffect,
                    onEffectChange = onEffectChange
                )
            }

            EditTool.ROTATE -> {
                RotatePanel()
            }
        }
    }
}

/**
 * 缩放面板
 */
@Composable
private fun ZoomPanel(
    zoomLevel: Float,
    onZoomLevelChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "缩放",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ZoomOut, contentDescription = "缩小")
            Slider(
                value = zoomLevel,
                onValueChange = onZoomLevelChange,
                valueRange = 0.5f..2.0f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Icon(Icons.Default.ZoomIn, contentDescription = "放大")
        }

        Text(
            text = "${(zoomLevel * 100).toInt()}%",
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

/**
 * 抠图面板 —— 控制抠图+放大效果
 */
@Composable
private fun CutoutPanel(
    effect: PhotoEffect,
    onEffectChange: (PhotoEffect) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "智能抠图 + 放大",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "背景保持不变，人物放大并超出边界",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 启用开关
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("启用抠图效果", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = effect.cutoutEnabled,
                onCheckedChange = { enabled ->
                    onEffectChange(effect.copy(cutoutEnabled = enabled))
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

            Spacer(modifier = Modifier.height(12.dp))

            // 背景虚化
            Text(
                text = "背景虚化",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Slider(
                value = effect.backgroundBlur,
                onValueChange = { onEffectChange(effect.copy(backgroundBlur = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 滤镜面板
 */
@Composable
private fun FilterPanel(
    effect: PhotoEffect,
    onEffectChange: (PhotoEffect) -> Unit
) {
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
                    text = "小米AI抠图已启用",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "使用小米相机AI引擎，抠图更精准",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * 抠图工具常量
 */
val CUTOUT_TOOL = EditTool.CUTOUT

enum class EditTool(val displayName: String, val icon: ImageVector) {
    ZOOM("缩放", Icons.Default.ZoomIn),
    CUTOUT("抠图", Icons.Default.ContentCut),
    FILTER("滤镜", Icons.Default.Filter),
    ROTATE("旋转", Icons.Default.RotateRight)
}
