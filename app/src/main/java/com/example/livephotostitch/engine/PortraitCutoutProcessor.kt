package com.example.livephotostitch.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 人物抠图处理器接口
 */
interface PortraitCutoutProcessor {
    /**
     * 对图片进行抠图，返回 mask 和人物图片
     * @return null 表示抠图失败
     */
    suspend fun cutout(bitmap: Bitmap): CutoutResult?

    /**
     * 将人物放大到指定倍数，背景保持不变
     * @return 放大后的人物图片（带透明背景）
     */
    fun zoomPerson(
        personBitmap: Bitmap,
        maskBitmap: Bitmap,
        zoomLevel: Float,
        centerX: Float = 0.5f,
        centerY: Float = 0.5f
    ): Bitmap
}

/**
 * 抠图结果
 */
data class CutoutResult(
    val maskBitmap: Bitmap,
    val personBitmap: Bitmap
)

/**
 * 基于 ML Kit 的人物抠图处理器
 *
 * 在小米手机上会优先使用小米相机AI引擎（通过系统Intent调用），
 * 其他手机使用 ML Kit。
 */
class MlKitPortraitCutoutProcessor(private val context: Context) : PortraitCutoutProcessor {

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    override suspend fun cutout(bitmap: Bitmap): CutoutResult? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val maskBuffer = suspendCancellableCoroutine<ByteBuffer> { continuation ->
                segmenter.process(inputImage)
                    .addOnSuccessListener { result ->
                        continuation.resume(result.buffer)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }

            // 关键：ML Kit 的 mask 是模型输入尺寸（通常是 256x256），
            // 不是原图尺寸。必须按 mask 自身尺寸解析，再放大到原图尺寸，
            // 否则会越界读取，抠图直接失败。
            val smallMask = maskBuffer.toMaskBitmap() ?: return null
            val maskBitmap = if (smallMask.width != bitmap.width || smallMask.height != bitmap.height) {
                Bitmap.createScaledBitmap(smallMask, bitmap.width, bitmap.height, true)
            } else {
                smallMask
            }

            val personBitmap = applyMask(bitmap, maskBitmap)

            CutoutResult(maskBitmap, personBitmap)
        } catch (e: Exception) {
            null
        }
    }

    override fun zoomPerson(
        personBitmap: Bitmap,
        maskBitmap: Bitmap,
        zoomLevel: Float,
        centerX: Float,
        centerY: Float
    ): Bitmap {
        val width = personBitmap.width
        val height = personBitmap.height

        // 创建与原图相同尺寸的输出（背景透明）
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 放大人物和mask
        val scaledPerson = Bitmap.createScaledBitmap(
            personBitmap,
            (width * zoomLevel).toInt(),
            (height * zoomLevel).toInt(),
            true
        )
        val scaledMask = Bitmap.createScaledBitmap(
            maskBitmap,
            (width * zoomLevel).toInt(),
            (height * zoomLevel).toInt(),
            true
        )

        // 计算偏移，使放大后的人物居中
        val offsetX = (width - scaledPerson.width) * centerX
        val offsetY = (height - scaledPerson.height) * centerY

        // 先画人物
        canvas.drawBitmap(scaledPerson, offsetX, offsetY, paint)

        // 用 mask 裁剪人物（DST_IN）
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(scaledMask, offsetX, offsetY, paint)
        paint.xfermode = null

        return outputBitmap
    }

    /**
     * 应用 mask 提取人物
     */
    private fun applyMask(bitmap: Bitmap, mask: Bitmap): Bitmap {
        val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask, 0f, 0f, paint)

        return outputBitmap
    }

    /**
     * ML Kit mask（FLOAT，每像素 0-1）→ 灰度透明 Bitmap
     *
     * 尺寸由 buffer 自身推断（模型固定 256x256），不能用原图尺寸。
     */
    private fun ByteBuffer.toMaskBitmap(): Bitmap? {
        this.rewind()

        val floatCount = this.remaining() / 4
        if (floatCount <= 0) return null

        val size = kotlin.math.sqrt(floatCount.toFloat()).toInt()
        if (size <= 0) return null

        val buffer = this.asFloatBuffer()
        val pixels = IntArray(size * size)

        for (i in 0 until size * size) {
            val value = buffer.get(i)
            val alpha = (value.coerceIn(0f, 1f) * 255).toInt()
            // 白色 + alpha 表示"这个像素是人物"的置信度
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
