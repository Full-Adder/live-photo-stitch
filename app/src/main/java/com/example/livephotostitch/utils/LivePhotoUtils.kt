package com.example.livephotostitch.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.livephotostitch.data.model.LivePhoto
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 实况照片工具类
 */
object LivePhotoUtils {

    /**
     * 从 Uri 创建 LivePhoto。
     *
     * 支持三种来源：
     * 1. 普通图片 —— 直接作为静态图
     * 2. 视频文件 —— 抽取首帧作为静态图，视频本身作为动态部分
     * 3. 实况照片（JPEG + 内嵌 MP4）—— 目前按普通图片处理（内嵌视频暂不解析）
     *
     * 关键：选择器用 ImageAndVideo 时用户可能选中视频，如果不做处理，
     * 后面的 BitmapFactory.decodeStream 会返回 null 直接导致整套流程崩溃。
     */
    fun createLivePhotoFromUri(context: Context, uri: Uri): LivePhoto {
        val isVideo = isVideoUri(context, uri)

        return if (isVideo) {
            createFromVideo(context, uri)
        } else {
            createFromImage(context, uri)
        }
    }

    /**
     * 判断 Uri 指向的是否为视频文件
     */
    private fun isVideoUri(context: Context, uri: Uri): Boolean {
        // 1. 先看 MIME
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        if (mime != null) {
            return mime.startsWith("video/")
        }

        // 2. 再看文件名后缀
        val name = getDisplayName(context, uri)?.lowercase()
        if (name != null) {
            return name.endsWith(".mp4") || name.endsWith(".mov") ||
                name.endsWith(".3gp") || name.endsWith(".mkv") ||
                name.endsWith(".webm") || name.endsWith(".avi") ||
                name.endsWith(".m4v")
        }

        return false
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 视频 → 抽取首帧作为静态图
     */
    private fun createFromVideo(context: Context, uri: Uri): LivePhoto {
        val retriever = MediaMetadataRetriever()
        var width = 0
        var height = 0
        var duration = 0L
        var frameFile: File? = null

        try {
            retriever.setDataSource(context, uri)

            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            // 旋转信息
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val tmp = width
                width = height
                height = tmp
            }

            // 抽首帧
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime

            if (frame != null) {
                val outFile = File(context.cacheDir, "video_frame_${UUID.randomUUID()}.jpg")
                FileOutputStream(outFile).use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                frameFile = outFile
                if (width == 0 || height == 0) {
                    width = frame.width
                    height = frame.height
                }
            }
        } catch (e: Exception) {
            // 抽取失败，下面兜底
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }

        return LivePhoto(
            id = UUID.randomUUID().toString(),
            // 抽帧失败时退回原 uri（后续加载会失败，但至少不会在这里崩溃）
            staticImageUri = frameFile?.let { Uri.fromFile(it) } ?: uri,
            videoUri = uri,
            videoDuration = duration,
            creationDate = System.currentTimeMillis(),
            width = width,
            height = height,
            orientation = 0
        )
    }

    /**
     * 图片 → 读取尺寸与 EXIF 方向
     */
    private fun createFromImage(context: Context, uri: Uri): LivePhoto {
        var width = 0
        var height = 0
        var orientation = 0

        // 尺寸
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            width = options.outWidth
            height = options.outHeight
        } catch (e: Exception) {
            // 忽略
        }

        // EXIF 方向
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            orientation = ExifInterface.ORIENTATION_NORMAL
        }

        // EXIF 旋转 90/270 时宽高互换
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            val tmp = width
            width = height
            height = tmp
        }

        // 判断是否实况照片：图片本身没有视频轨道，这里按静态图处理
        return LivePhoto(
            id = UUID.randomUUID().toString(),
            staticImageUri = uri,
            videoUri = null,
            videoDuration = 0L,
            creationDate = System.currentTimeMillis(),
            width = width,
            height = height,
            orientation = orientation
        )
    }

    /**
     * 检查是否为实况照片（带视频轨道）
     */
    fun isLivePhoto(context: Context, uri: Uri): Boolean {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            duration > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 按照 EXIF 方向旋转 Bitmap
     */
    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * 读取 EXIF 方向
     */
    fun readExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * 获取图片尺寸
     */
    fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return Pair(options.outWidth, options.outHeight)
    }

    /**
     * 计算最佳采样率
     */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
