package com.example.livephotostitch.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.example.livephotostitch.data.model.LivePhoto
import com.example.livephotostitch.data.model.StitchTemplate
import com.example.livephotostitch.data.model.TemplateItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 单张照片的效果配置
 */
data class PhotoEffect(
    /** 是否启用抠图+放大效果 */
    val cutoutEnabled: Boolean = false,
    /** 放大倍数 (1.0 = 不放大) */
    val zoomLevel: Float = 1.0f,
    /** 人物在照片中的水平中心位置 (0-1) */
    val personCenterX: Float = 0.5f,
    /** 人物在照片中的垂直中心位置 (0-1) */
    val personCenterY: Float = 0.5f
)

/**
 * 实况照片拼接引擎
 *
 * 核心逻辑：
 * 1. 固定输出比例（由模板决定）
 * 2. 每张照片裁剪填充到各自的格子（无间距）
 * 3. 输出静态图 + 短视频（实况照片）
 * 4. 可选：AI 抠图+放大（人物超出格子边界）
 */
class LivePhotoStitchEngine(private val context: Context) {

    /**
     * 拼接实况照片
     */
    suspend fun stitchLivePhotos(
        photos: List<LivePhoto>,
        template: StitchTemplate,
        outputDir: File,
        effects: List<PhotoEffect> = photos.map { PhotoEffect() },
        cutoutProcessor: PortraitCutoutProcessor? = null
    ): Result<StitchResult> = withContext(Dispatchers.IO) {
        try {
            require(photos.isNotEmpty()) { "至少需要1张照片" }
            require(effects.size == photos.size) { "effects 数量必须与 photos 一致" }

            // 1. 加载所有静态图片
            val bitmaps = photos.map { loadBitmap(it.staticImageUri) }

            // 2. 计算输出尺寸（固定宽度 1080，高度由比例决定）
            val outputWidth = 1080
            val outputHeight = (outputWidth / template.aspectRatio).toInt()

            // 3. 绘制背景层（所有照片裁剪填充到格子）
            val backgroundLayer = composeBackground(
                bitmaps = bitmaps,
                template = template,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )

            // 4. 处理前景层（抠图+放大，人物可超出格子）
            val foregroundLayer = processForeground(
                bitmaps = bitmaps,
                template = template,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                effects = effects,
                cutoutProcessor = cutoutProcessor
            )

            // 5. 合并图层
            val stitchedBitmap = mergeLayers(backgroundLayer, foregroundLayer)

            // 6. 保存静态图片
            val staticImageFile = File(outputDir, "static_${UUID.randomUUID()}.jpg")
            saveBitmap(stitchedBitmap, staticImageFile)

            // 7. 处理视频片段 —— 创建完整的拼接视频
            val videoFile = processStitchedVideo(
                photos = photos,
                bitmaps = bitmaps,
                template = template,
                outputDir = outputDir,
                effects = effects,
                cutoutProcessor = cutoutProcessor,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )

            // 8. 创建结果
            val result = StitchResult(
                staticImageUri = Uri.fromFile(staticImageFile),
                videoUri = videoFile?.let { Uri.fromFile(it) },
                width = outputWidth,
                height = outputHeight,
                isLivePhoto = videoFile != null
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 绘制背景层 —— 所有照片裁剪填充到各自格子（紧凑，无间距）
     */
    private fun composeBackground(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap {
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawColor(android.graphics.Color.BLACK)

        template.layout.forEachIndexed { index, item ->
            if (index < bitmaps.size) {
                val bitmap = bitmaps[index]
                val destRect = item.toRectF(outputWidth, outputHeight)
                drawBitmapCropToFill(canvas, bitmap, destRect, paint)
            }
        }

        return outputBitmap
    }

    /**
     * 处理前景层 —— 对启用了抠图+放大的照片，提取人物并放大
     */
    private suspend fun processForeground(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputWidth: Int,
        outputHeight: Int,
        effects: List<PhotoEffect>,
        cutoutProcessor: PortraitCutoutProcessor?
    ): Bitmap? {
        if (cutoutProcessor == null) return null

        val foregroundBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(foregroundBitmap)
        var hasForeground = false

        effects.forEachIndexed { index, effect ->
            if (!effect.cutoutEnabled || effect.zoomLevel <= 1.0f) return@forEachIndexed
            if (index >= bitmaps.size || index >= template.layout.size) return@forEachIndexed

            val bitmap = bitmaps[index]
            val item = template.layout[index]
            val destRect = item.toRectF(outputWidth, outputHeight)

            val cutoutResult = cutoutProcessor.cutout(bitmap) ?: return@forEachIndexed

            val zoomedPerson = cutoutProcessor.zoomPerson(
                personBitmap = cutoutResult.personBitmap,
                maskBitmap = cutoutResult.maskBitmap,
                zoomLevel = effect.zoomLevel,
                centerX = effect.personCenterX,
                centerY = effect.personCenterY
            )

            val (scaleFactor, offsetX, offsetY) = calcPlacement(bitmap, destRect)

            val scaledPersonWidth = (zoomedPerson.width * scaleFactor).toInt()
            val scaledPersonHeight = (zoomedPerson.height * scaleFactor).toInt()
            val scaledPerson = Bitmap.createScaledBitmap(
                zoomedPerson, scaledPersonWidth, scaledPersonHeight, true
            )

            canvas.drawBitmap(scaledPerson, offsetX, offsetY, Paint(Paint.ANTI_ALIAS_FLAG))
            hasForeground = true
        }

        return if (hasForeground) foregroundBitmap else null
    }

    /**
     * 合并背景层和前景层
     */
    private fun mergeLayers(background: Bitmap, foreground: Bitmap?): Bitmap {
        if (foreground == null) return background

        val outputBitmap = Bitmap.createBitmap(background.width, background.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawBitmap(background, 0f, 0f, null)
        canvas.drawBitmap(foreground, 0f, 0f, null)
        return outputBitmap
    }

    /**
     * 处理拼接视频 —— 创建完整的拼接视频
     *
     * 关键：从所有照片的视频中提取帧，每帧都拼接成完整的画布
     */
    private suspend fun processStitchedVideo(
        photos: List<LivePhoto>,
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputDir: File,
        effects: List<PhotoEffect>,
        cutoutProcessor: PortraitCutoutProcessor?,
        outputWidth: Int,
        outputHeight: Int
    ): File? {
        val videoPhotos = photos.mapIndexedNotNull { index, photo ->
            photo.videoUri?.let { index to it }
        }
        if (videoPhotos.isEmpty()) return null

        val outputFile = File(outputDir, "video_${UUID.randomUUID()}.mp4")

        // 检查是否需要应用效果
        val needsEffect = effects.any { it.cutoutEnabled && it.zoomLevel > 1.0f }

        return try {
            if (!needsEffect) {
                // 不需要效果：直接拼接第一帧静态图作为视频
                createStaticFrameVideo(bitmaps, template, outputFile, outputWidth, outputHeight)
            } else {
                // 需要效果：从视频中逐帧提取并处理
                createEffectVideo(
                    photos = photos,
                    bitmaps = bitmaps,
                    template = template,
                    outputFile = outputFile,
                    effects = effects,
                    cutoutProcessor = cutoutProcessor,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight
                )
            }
            outputFile
        } catch (e: Exception) {
            // 降级：创建静态帧视频
            try {
                createStaticFrameVideo(bitmaps, template, outputFile, outputWidth, outputHeight)
                outputFile
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * 创建静态帧视频（从静态图生成短视频）
     */
    private fun createStaticFrameVideo(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputFile: File,
        outputWidth: Int,
        outputHeight: Int
    ) {
        val background = composeBackground(bitmaps, template, outputWidth, outputHeight)
        encodeFramesToVideo(listOf(background), outputFile)
    }

    /**
     * 创建带效果的视频 —— 从所有视频中提取帧并拼接
     */
    private suspend fun createEffectVideo(
        photos: List<LivePhoto>,
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputFile: File,
        effects: List<PhotoEffect>,
        cutoutProcessor: PortraitCutoutProcessor?,
        outputWidth: Int,
        outputHeight: Int
    ) {
        // 找到第一个有视频的照片
        val firstVideoPhoto = photos.firstOrNull { it.videoUri != null } ?: return
        val videoUri = firstVideoPhoto.videoUri!!

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L

        if (durationMs == 0L) {
            retriever.release()
            // 降级：静态帧视频
            val background = composeBackground(bitmaps, template, outputWidth, outputHeight)
            encodeFramesToVideo(listOf(background), outputFile)
            return
        }

        // 提取帧（约15fps，减少处理量）
        val frameIntervalUs = 66_000L
        val frames = mutableListOf<Bitmap>()

        var timeUs = 0L
        while (timeUs < durationMs * 1000) {
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                frames.add(frame)
            }
            timeUs += frameIntervalUs
        }
        retriever.release()

        if (frames.isEmpty()) {
            val background = composeBackground(bitmaps, template, outputWidth, outputHeight)
            encodeFramesToVideo(listOf(background), outputFile)
            return
        }

        // 对每一帧：使用静态背景 + 动态前景
        val firstVideoIndex = photos.indexOf(firstVideoPhoto)

        val processedFrames = frames.map { frame ->
            // 如果当前帧来自有视频的照片，替换该位置的静态图
            val frameBitmaps = bitmaps.toMutableList()
            if (firstVideoIndex < frameBitmaps.size) {
                frameBitmaps[firstVideoIndex] = frame
            }

            // 重新绘制背景
            val frameBackground = composeBackground(frameBitmaps, template, outputWidth, outputHeight)

            // 应用效果
            val foreground = processForeground(
                bitmaps = frameBitmaps,
                template = template,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                effects = effects,
                cutoutProcessor = cutoutProcessor
            )

            mergeLayers(frameBackground, foreground)
        }

        encodeFramesToVideo(processedFrames, outputFile)
    }

    /**
     * 将帧序列编码为 MP4 视频
     * 使用 MediaCodec + MediaMuxer 编码
     */
    private fun encodeFramesToVideo(frames: List<Bitmap>, outputFile: File) {
        if (frames.isEmpty()) return

        val width = frames[0].width
        val height = frames[0].height
        val frameRate = 30

        // 确保尺寸是偶数（编码器要求）
        val encWidth = if (width % 2 == 0) width else width - 1
        val encHeight = if (height % 2 == 0) height else height - 1

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // 创建编码器 — 使用 ByteBuffer 输入方式
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var muxerStarted = false
            var trackIndex = -1

            // 将每帧转换为 YUV420SP (NV12) 并输入编码器
            val frameDurationUs = 1_000_000L / frameRate
            var frameIndex = 0

            frames.forEach { frame ->
                // 缩放帧到编码尺寸
                val scaled = if (frame.width != encWidth || frame.height != encHeight) {
                    Bitmap.createScaledBitmap(frame, encWidth, encHeight, true)
                } else {
                    frame
                }

                // 转换为 NV12 (YUV420SP)
                val nv12 = bitmapToNV12(scaled, encWidth, encHeight)

                // 输入到编码器
                val inputBufferId = codec.dequeueInputBuffer(10_000)
                if (inputBufferId >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferId)
                    inputBuffer?.clear()
                    inputBuffer?.put(nv12)
                    val ptsUs = frameIndex * frameDurationUs
                    codec.queueInputBuffer(inputBufferId, 0, nv12.size, ptsUs, 0)
                }
                frameIndex++

                // 输出编码数据
                var outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputBufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outputBufferId >= 0) {
                        val encodedData = codec.getOutputBuffer(outputBufferId)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                    outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                }
            }

            // 发送 EOS
            val inputBufferId = codec.dequeueInputBuffer(10_000)
            if (inputBufferId >= 0) {
                val eosPtsUs = frameIndex * frameDurationUs
                codec.queueInputBuffer(inputBufferId, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            // 读取剩余输出
            var outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outputBufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outputBufferId >= 0 && bufferInfo.size > 0 && muxerStarted) {
                    val encodedData = codec.getOutputBuffer(outputBufferId)
                    if (encodedData != null) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                }
                outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            }

            codec.stop()
            muxer.stop()

        } catch (e: Exception) {
            // 降级：保存第一帧为图片
            try {
                frames.firstOrNull()?.let { saveBitmap(it, outputFile) }
            } catch (e2: Exception) {
                // 忽略
            }
        } finally {
            try { codec?.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    /**
     * 将 Bitmap 转换为 NV12 (YUV420SP) 格式
     */
    private fun bitmapToNV12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)

        // 转换 ARGB -> YUV
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Y
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // UV (每 2x2 采样一次)
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuv
    }

    /**
     * 将图片裁剪填充到指定矩形区域（居中裁剪，填满整个格子）
     */
    private fun drawBitmapCropToFill(canvas: Canvas, bitmap: Bitmap, destRect: RectF, paint: Paint) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height
        val destAspect = destRect.width() / destRect.height()

        val matrix = Matrix()

        if (srcAspect > destAspect) {
            val scale = destRect.height() / bitmap.height
            matrix.setScale(scale, scale)
            val scaledWidth = bitmap.width * scale
            val offsetX = (destRect.width() - scaledWidth) / 2
            matrix.postTranslate(destRect.left + offsetX, destRect.top)
        } else {
            val scale = destRect.width() / bitmap.width
            matrix.setScale(scale, scale)
            val scaledHeight = bitmap.height * scale
            val offsetY = (destRect.height() - scaledHeight) / 2
            matrix.postTranslate(destRect.left, destRect.top + offsetY)
        }

        canvas.drawBitmap(bitmap, matrix, paint)
    }

    /**
     * 计算原图在拼接画布中的放置参数
     */
    private fun calcPlacement(bitmap: Bitmap, destRect: RectF): Triple<Float, Float, Float> {
        val srcAspect = bitmap.width.toFloat() / bitmap.height
        val destAspect = destRect.width() / destRect.height()

        return if (srcAspect > destAspect) {
            val scale = destRect.height() / bitmap.height
            val scaledWidth = bitmap.width * scale
            val offsetX = destRect.left + (destRect.width() - scaledWidth) / 2
            Triple(scale, offsetX, destRect.top)
        } else {
            val scale = destRect.width() / bitmap.width
            val scaledHeight = bitmap.height * scale
            val offsetY = destRect.top + (destRect.height() - scaledHeight) / 2
            Triple(scale, destRect.left, offsetY)
        }
    }

    /**
     * 加载 Bitmap
     */
    private fun loadBitmap(uri: Uri): Bitmap {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: throw IllegalArgumentException("Failed to load bitmap from $uri")
    }

    /**
     * 保存 Bitmap
     */
    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
    }
}

/**
 * 拼接结果
 */
data class StitchResult(
    val staticImageUri: Uri,
    val videoUri: Uri?,
    val width: Int,
    val height: Int,
    val isLivePhoto: Boolean
)

/**
 * 将 TemplateItem 的相对坐标转换为画布上的绝对坐标
 */
private fun TemplateItem.toRectF(outputWidth: Int, outputHeight: Int): RectF {
    return RectF(
        x * outputWidth,
        y * outputHeight,
        (x + width) * outputWidth,
        (y + height) * outputHeight
    )
}
