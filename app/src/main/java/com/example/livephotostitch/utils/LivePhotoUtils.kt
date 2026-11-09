package com.example.livephotostitch.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.livephotostitch.data.model.LivePhoto
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 实况照片工具类
 */
object LivePhotoUtils {

    /**
     * 从Uri创建LivePhoto对象
     *
     * 支持：
     * - Android 实况照片（通过 MediaMetadataRetriever 检测视频）
     * - 普通图片
     */
    fun createLivePhotoFromUri(context: Context, uri: Uri): LivePhoto {
        var width = 0
        var height = 0
        var duration = 0L
        var videoUri: Uri? = null
        var creationDate = System.currentTimeMillis()

        // 尝试用 MediaMetadataRetriever 读取
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (dateStr != null) {
                // 尝试解析日期
                creationDate = try {
                    dateStr.toLongOrNull() ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }
            }

            retriever.release()
        } catch (e: Exception) {
            // 忽略，使用 BitmapFactory 获取尺寸
        }

        // 如果 MediaMetadataRetriever 没有获取到尺寸，用 BitmapFactory
        if (width == 0 || height == 0) {
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
        }

        // 判断是否为实况照片
        val isLivePhoto = duration > 0
        if (isLivePhoto) {
            videoUri = uri
        }

        return LivePhoto(
            id = UUID.randomUUID().toString(),
            staticImageUri = uri,
            videoUri = videoUri,
            videoDuration = duration,
            creationDate = creationDate,
            width = width,
            height = height,
            orientation = 0
        )
    }

    /**
     * 检查是否为实况照片
     */
    fun isLivePhoto(context: Context, uri: Uri): Boolean {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            duration > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从Uri中提取视频轨道（用于实况照片）
     * 返回视频文件的Uri
     */
    fun extractVideoTrack(context: Context, uri: Uri): File? {
        var extractor: MediaExtractor? = null
        var outputFile: File? = null
        var outputStream: FileOutputStream? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                return null
            }

            extractor.selectTrack(videoTrackIndex)

            // 创建临时文件
            outputFile = File.createTempFile("live_video_", ".mp4", context.cacheDir)
            outputStream = FileOutputStream(outputFile)

            // 读取并写入视频数据
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                outputStream.write(buffer.array(), 0, sampleSize)
                extractor.advance()
            }

            extractor.release()
            extractor = null
            outputStream.close()
            outputStream = null

            outputFile
        } catch (e: Exception) {
            extractor?.release()
            try { outputStream?.close() } catch (e2: Exception) {}
            outputFile?.delete()
            null
        } finally {
            try { extractor?.release() } catch (e: Exception) {}
            try { outputStream?.close() } catch (e: Exception) {}
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
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
