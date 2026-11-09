package com.example.livephotostitch.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.livephotostitch.data.model.LivePhoto
import com.example.livephotostitch.utils.LivePhotoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSelectionScreen(
    onNextClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    // 从全局状态初始化，支持返回时保留选择
    var selectedPhotos by remember {
        mutableStateOf(
            List(3) { index ->
                StitchProjectState.photos.getOrNull(index)
            }
        )
    }
    var currentSlotIndex by remember { mutableIntStateOf(0) }
    
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            val livePhoto = LivePhotoUtils.createLivePhotoFromUri(context, it)
            selectedPhotos = selectedPhotos.toMutableList().apply {
                set(currentSlotIndex, livePhoto)
            }
            // 自动跳到下一个空位
            val nextEmpty = selectedPhotos.indexOfFirst { it == null }
            if (nextEmpty != -1) {
                currentSlotIndex = nextEmpty
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择照片") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            // 保存到全局状态
                            StitchProjectState.photos = selectedPhotos.filterNotNull()
                            onNextClick()
                        },
                        enabled = selectedPhotos.all { it != null }
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
            // 说明文字
            Text(
                text = "请选择3张实况照片",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "支持实况照片、普通照片",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 照片槽位
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                selectedPhotos.forEachIndexed { index, photo ->
                    PhotoSlot(
                        photo = photo,
                        isSelected = currentSlotIndex == index,
                        onClick = {
                            // 空位：直接选图；已选：点击可替换
                            currentSlotIndex = index
                            pickMedia.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        onRemove = {
                            selectedPhotos = selectedPhotos.toMutableList().apply {
                                set(index, null)
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 已选照片预览
            if (selectedPhotos.any { it != null }) {
                Text(
                    text = "已选 ${selectedPhotos.count { it != null }}/3 张照片",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(selectedPhotos) { index, photo ->
                        if (photo != null) {
                            PhotoThumbnail(
                                photo = photo,
                                index = index,
                                onRemove = {
                                    selectedPhotos = selectedPhotos.toMutableList().apply {
                                        set(index, null)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 提示信息
            if (selectedPhotos.all { it != null }) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "✅ 已选择3张照片，点击'下一步'选择模板",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoSlot(
    photo: LivePhoto?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            AsyncImage(
                model = photo.staticImageUri,
                contentDescription = "照片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // 实况照片标识
            if (photo.videoUri != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            CircleShape
                        )
                        .padding(4.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "实况照片",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            
            // 删除按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Red, CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        } else {
            Icon(
                Icons.Default.Add,
                contentDescription = "添加照片",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(
    photo: LivePhoto,
    index: Int,
    onRemove: () -> Unit
) {
    Box {
        Card(
            modifier = Modifier.size(80.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            AsyncImage(
                model = photo.staticImageUri,
                contentDescription = "照片 ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        // 删除按钮
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color.Red, CircleShape)
                .clickable(onClick = onRemove)
                .padding(4.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "删除",
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
