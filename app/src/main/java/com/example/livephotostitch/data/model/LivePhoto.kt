package com.example.livephotostitch.data.model

import android.graphics.RectF
import android.net.Uri

/**
 * 实况照片数据模型
 */
data class LivePhoto(
    val id: String,
    val staticImageUri: Uri,
    val videoUri: Uri?,
    val videoDuration: Long,
    val creationDate: Long,
    val width: Int,
    val height: Int,
    val orientation: Int
) {
    fun getAspectRatio(): Float = width.toFloat() / height.toFloat()
}

/**
 * 拼接模板数据模型
 */
data class StitchTemplate(
    val id: String,
    val name: String,
    val layout: List<TemplateItem>,
    val aspectRatio: Float,
    val thumbnailResId: Int
)

data class TemplateItem(
    val x: Float, // 相对位置 0-1
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f
)

/**
 * 项目数据模型
 */
data class StitchProject(
    val id: String,
    val photos: List<LivePhoto>,
    val template: StitchTemplate,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 处理选项
 */
data class ProcessingOptions(
    val zoomLevel: Float = 1.0f,
    val backgroundZoom: Boolean = false,
    val cropArea: RectF? = null,
    val applyFilter: Boolean = false,
    val filterType: FilterType = FilterType.NONE
)

enum class FilterType {
    NONE,
    BLUR,
    GRAYSCALE,
    SEPIA,
    VINTAGE
}
