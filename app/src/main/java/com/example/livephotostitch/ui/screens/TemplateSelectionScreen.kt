package com.example.livephotostitch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.livephotostitch.data.StitchProjectState
import com.example.livephotostitch.data.model.StitchTemplate
import com.example.livephotostitch.data.model.TemplateItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSelectionScreen(
    onNextClick: () -> Unit,
    onBackClick: () -> Unit
) {
    // 从全局状态读取默认选择，支持返回时保留
    var selectedTemplate by remember {
        mutableStateOf(
            StitchProjectState.template ?: TemplateSelectionScreenDefaults.defaultTemplate
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择模板") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            StitchProjectState.template = selectedTemplate
                            onNextClick()
                        },
                        enabled = true
                    ) {
                        Text("下一步")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "选择拼接模板",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "选择你喜欢的照片布局",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(TemplateData.templates) { template ->
                    TemplateCard(
                        template = template,
                        isSelected = selectedTemplate.id == template.id,
                        onClick = { selectedTemplate = template }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: StitchTemplate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 模板预览
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            ) {
                TemplatePreview(
                    template = template,
                    modifier = Modifier.fillMaxSize()
                )
                
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已选择",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = template.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TemplatePreview(
    template: StitchTemplate,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current

    BoxWithConstraints(modifier = modifier.padding(8.dp)) {
        val boxWidthPx = constraints.maxWidth.toFloat()
        val boxHeightPx = constraints.maxHeight.toFloat()

        template.layout.forEachIndexed { index, item ->
            val colors = listOf(
                Color(0xFFE3F2FD),
                Color(0xFFF3E5F5),
                Color(0xFFE8F5E9)
            )

            val cellWidthDp = with(density) { (item.width * boxWidthPx).toDp() }
            val cellHeightDp = with(density) { (item.height * boxHeightPx).toDp() }
            val cellXDp = with(density) { (item.x * boxWidthPx).toDp() }
            val cellYDp = with(density) { (item.y * boxHeightPx).toDp() }

            Box(
                modifier = Modifier
                    .absoluteOffset(x = cellXDp, y = cellYDp)
                    .size(width = cellWidthDp, height = cellHeightDp)
                    .background(
                        colors[index % colors.size],
                        RoundedCornerShape(4.dp)
                    )
                    .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            )
        }
    }
}

// 模板数据
object TemplateData {
    val templates = listOf(
        StitchTemplate(
            id = "horizontal",
            name = "横向三格",
            layout = listOf(
                TemplateItem(0f, 0f, 1f/3f, 1f),
                TemplateItem(1f/3f, 0f, 1f/3f, 1f),
                TemplateItem(2f/3f, 0f, 1f/3f, 1f)
            ),
            aspectRatio = 3f / 1f,
            thumbnailResId = 0
        ),
        StitchTemplate(
            id = "vertical",
            name = "纵向三格",
            layout = listOf(
                TemplateItem(0f, 0f, 1f, 1f/3f),
                TemplateItem(0f, 1f/3f, 1f, 1f/3f),
                TemplateItem(0f, 2f/3f, 1f, 1f/3f)
            ),
            aspectRatio = 1f / 3f,
            thumbnailResId = 0
        ),
        StitchTemplate(
            id = "center_large",
            name = "中间大两边小",
            layout = listOf(
                TemplateItem(0f, 0f, 0.25f, 1f),
                TemplateItem(0.25f, 0f, 0.5f, 1f),
                TemplateItem(0.75f, 0f, 0.25f, 1f)
            ),
            aspectRatio = 2f / 1f,
            thumbnailResId = 0
        ),
        StitchTemplate(
            id = "left_large",
            name = "左大右小",
            layout = listOf(
                TemplateItem(0f, 0f, 0.6f, 1f),
                TemplateItem(0.6f, 0f, 0.4f, 0.5f),
                TemplateItem(0.6f, 0.5f, 0.4f, 0.5f)
            ),
            aspectRatio = 16f / 9f,
            thumbnailResId = 0
        ),
        StitchTemplate(
            id = "right_large",
            name = "右大左小",
            layout = listOf(
                TemplateItem(0f, 0f, 0.4f, 0.5f),
                TemplateItem(0f, 0.5f, 0.4f, 0.5f),
                TemplateItem(0.4f, 0f, 0.6f, 1f)
            ),
            aspectRatio = 16f / 9f,
            thumbnailResId = 0
        ),
        StitchTemplate(
            id = "grid",
            name = "四宫格布局",
            layout = listOf(
                TemplateItem(0f, 0f, 0.5f, 0.5f),
                TemplateItem(0.5f, 0f, 0.5f, 0.5f),
                TemplateItem(0f, 0.5f, 1f, 0.5f)
            ),
            aspectRatio = 4f / 3f,
            thumbnailResId = 0
        )
    )
}

object TemplateSelectionScreenDefaults {
    val defaultTemplate = TemplateData.templates.first()
}
