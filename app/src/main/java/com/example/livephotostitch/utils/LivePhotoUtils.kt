package com.example.livephotostitch.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import com.example.livephotostitch.data.model.LivePhoto
import java.util.UUID

/**
 * 实况照片工具类
 */
object LivePhotoUtils {
    
    /**
     * 从Uri创建LivePhoto对象
     */
    fun createLivePhotoFromUri(context: Context, uri: Uri): LivePhoto {
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(context, uri)
            
            // 获取图片尺寸
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            // 如果没有尺寸信息，尝试从Bitmap获取
            val actualWidth: Int
            val actualHeight: Int
            
            if (width > 0 && height > 0) {
                actualWidth = width
                actualHeight = height
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                actualWidth = options.outWidth
                actualHeight = options.outHeight
            }
            
            // 获取时长
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            
            // 检查是否为实况照片（有时长信息）
            val isLivePhoto = duration > 0
            
            // 获取日期
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.toLongOrNull() 
                ?: System.currentTimeMillis()
            
            return LivePhoto(
                id = UUID.randomUUID().toString(),
                staticImageUri = uri,
                videoUri = if (isLivePhoto) uri else null,
                videoDuration = duration,
                creationDate = date,
                width = actualWidth,
                height = actualHeight,
                orientation = 0
            )
        } catch (e: Exception) {
            // 降级处理：作为普通图片
            return LivePhoto(
                id = UUID.randomUUID().toString(),
                staticImageUri = uri,
                videoUri = null,
                videoDuration = 0,
                creationDate = System.currentTimeMillis(),
                width = 0,
                height = 0,
                orientation = 0
            )
        } finally {
            retriever.release()
        }
    }
    
    /**
     * 检查是否为实况照片
     */
    fun isLivePhoto(context: Context, uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            duration > 0
        } catch (e: Exception) {
            false
        } finally {
            retriever.release()
        }
    }
    
    /**
     * 从MediaStore获取实况照片
     */
    fun getLivePhotosFromGallery(context: Context, limit: Int = 50): List<LivePhoto> {
        val livePhotos = mutableListOf<LivePhoto>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            
            var count = 0
            while (it.moveToNext() && count < limit) {
                val id = it.getLong(idColumn)
                val date = it.getLong(dateColumn)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                
                val livePhoto = createLivePhotoFromUri(context, uri)
                if (livePhoto.videoUri != null) {
                    livePhotos.add(livePhoto)
                    count++
                }
            }
        }
        
        return livePhotos
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
