package com.example.livephotostitch.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.example.livephotostitch.data.model.LivePhoto
import com.example.livephotostitch.data.model.StitchTemplate
import com.example.livephotostitch.data.model.TemplateItem
import com.example.livephotostitch.utils.LivePhotoUtils
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
    val personCenterY: Float = 0.5f,
    /** 手动裁剪位置 - 水平 (-1 靠左 .. 0 居中 .. 1 靠右) */
    val cropOffsetX: Float = 0f,
    /** 手动裁剪位置 - 垂直 (-1 靠上 .. 0 居中 .. 1 靠下) */
    val cropOffsetY: Float = 0f,
    /** 背景画面缩放 (1.0 = 刚好铺满格子；>1 放大裁切更多) */
    val backgroundZoom: Float = 1f
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
                outputHeight = outputHeight,
                effects = effects
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
     *
     * @param effects 用于读取每张照片的手动裁剪位置
     */
    private fun composeBackground(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputWidth: Int,
        outputHeight: Int,
        effects: List<PhotoEffect> = emptyList()
    ): Bitmap {
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.drawColor(android.graphics.Color.BLACK)

        template.layout.forEachIndexed { index, item ->
            if (index < bitmaps.size) {
                val bitmap = bitmaps[index]
                val effect = effects.getOrNull(index)
                val destRect = item.toRectF(outputWidth, outputHeight)
                drawBitmapCropToFill(
                    canvas = canvas,
                    bitmap = bitmap,
                    destRect = destRect,
                    paint = paint,
                    offsetX = effect?.cropOffsetX ?: 0f,
                    offsetY = effect?.cropOffsetY ?: 0f,
                    zoom = effect?.backgroundZoom ?: 1f
                )
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

            // 直接把「未裁剪的人物」按矩阵画到整张画布上，
            // 人物放大后可以自然溢出到相邻格子（不会被自己的格子裁掉）
            drawZoomedPerson(
                canvas = canvas,
                personBitmap = cutoutResult.personBitmap,
                photoBitmap = bitmap,
                destRect = destRect,
                zoomLevel = effect.zoomLevel,
                centerX = effect.personCenterX,
                centerY = effect.personCenterY,
                cropOffsetX = effect.cropOffsetX,
                cropOffsetY = effect.cropOffsetY,
                backgroundZoom = effect.backgroundZoom
            )
            hasForeground = true
        }

        return if (hasForeground) foregroundBitmap else null
    }

    /**
     * 把抠出来的人物按「裁剪填充 + 以人物中心放大」的矩阵画到画布上
     *
     * 之前用 zoomPerson 返回同尺寸 bitmap，人物会被自身格子裁掉，
     * 永远不可能超出边界。改成直接对整个画布做矩阵变换即可自然溢出。
     */
    private fun drawZoomedPerson(
        canvas: Canvas,
        personBitmap: Bitmap,
        photoBitmap: Bitmap,
        destRect: RectF,
        zoomLevel: Float,
        centerX: Float,
        centerY: Float,
        cropOffsetX: Float = 0f,
        cropOffsetY: Float = 0f,
        backgroundZoom: Float = 1f
    ) {
        val photoW = photoBitmap.width.toFloat()
        val photoH = photoBitmap.height.toFloat()
        if (photoW <= 0f || photoH <= 0f) return

        // 1) 裁剪填充：photo 填满 destRect（与背景层保持完全一致的裁剪位置与缩放）
        val bgZoom = if (backgroundZoom < 1f) 1f else backgroundZoom
        val scale = maxOf(destRect.width() / photoW, destRect.height() / photoH) * bgZoom
        val extraW = destRect.width() - photoW * scale    // <= 0
        val extraH = destRect.height() - photoH * scale   // <= 0
        val fx = ((cropOffsetX.coerceIn(-1f, 1f) + 1f) / 2f)
        val fy = ((cropOffsetY.coerceIn(-1f, 1f) + 1f) / 2f)

        val dx = destRect.left + extraW * fx
        val dy = destRect.top + extraH * fy

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)

        // 2) 以人物中心为锚点放大（锚点换算到画布坐标）
        val anchorX = dx + centerX * photoW * scale
        val anchorY = dy + centerY * photoH * scale
        val zoom = if (zoomLevel < 1f) 1f else zoomLevel
        matrix.postScale(zoom, zoom, anchorX, anchorY)

        canvas.drawBitmap(
            personBitmap,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
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

        val outputFile = File(outputDir, "video_${UUID.randomUUID()}.mp4")

        // 检查是否需要应用效果
        val needsEffect = effects.any { it.cutoutEnabled && it.zoomLevel > 1.0f }

        return try {
            when {
                // 没有任何照片带视频 → 用静态图合成一段短视频，保证输出仍是「实况照片」
                videoPhotos.isEmpty() -> {
                    createStaticFrameVideo(bitmaps, template, outputFile, outputWidth, outputHeight)
                }
                // 有视频但不需要效果 → 静态帧即可（保留拼接画面）
                !needsEffect -> {
                    createStaticFrameVideo(bitmaps, template, outputFile, outputWidth, outputHeight)
                }
                // 有视频且需要效果 → 逐帧提取并处理
                else -> {
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
     *
     * 关键：单帧视频如果时长只有 1/30 秒，相册不会把它识别为实况照片。
     * 所以按最小时长（300ms）重复帧，保证输出是有效的动态照片。
     */
    private fun createStaticFrameVideo(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputFile: File,
        outputWidth: Int,
        outputHeight: Int
    ) {
        val background = composeBackground(bitmaps, template, outputWidth, outputHeight)
        encodeFramesToVideo(listOf(background), outputFile, minDurationMs = 300)
    }

    /**
     * 创建带效果的视频 —— 从第一个有视频的照片提取帧并拼接
     *
     * 性能要点：ML Kit 抠图结果按「照片」缓存，每帧只重新合成，不重复跑分割。
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
        val firstVideoPhoto = photos.firstOrNull { it.videoUri != null }
            ?: run {
                createStaticFrameVideo(bitmaps, template, outputFile, outputWidth, outputHeight)
                return
            }
        val videoUri = firstVideoPhoto.videoUri!!

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L

        // 提取帧（约15fps，减少处理量）
        val frameIntervalUs = 66_000L
        val frames = mutableListOf<Bitmap>()

        if (durationMs > 0L) {
            var timeUs = 0L
            while (timeUs < durationMs * 1000) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    frames.add(frame)
                }
                timeUs += frameIntervalUs
            }
        }
        retriever.release()

        val firstVideoIndex = photos.indexOf(firstVideoPhoto)

        // 关键优化：把每张照片的「人物」只抠一次，之后每帧都用矩阵重绘
        data class PersonLayer(
            val person: Bitmap,
            val photo: Bitmap,
            val destRect: RectF,
            val zoom: Float,
            val cx: Float,
            val cy: Float,
            val cropX: Float,
            val cropY: Float,
            val bgZoom: Float
        )

        val personLayers = mutableListOf<PersonLayer?>()

        if (cutoutProcessor != null) {
            effects.forEachIndexed { index, effect ->
                if (!effect.cutoutEnabled || effect.zoomLevel <= 1.0f ||
                    index >= bitmaps.size || index >= template.layout.size
                ) {
                    personLayers.add(null)
                    return@forEachIndexed
                }

                val bitmap = bitmaps[index]
                val cutoutResult = cutoutProcessor.cutout(bitmap)
                if (cutoutResult == null) {
                    personLayers.add(null)
                    return@forEachIndexed
                }

                personLayers.add(
                    PersonLayer(
                        person = cutoutResult.personBitmap,
                        photo = bitmap,
                        destRect = template.layout[index].toRectF(outputWidth, outputHeight),
                        zoom = effect.zoomLevel,
                        cx = effect.personCenterX,
                        cy = effect.personCenterY,
                        cropX = effect.cropOffsetX,
                        cropY = effect.cropOffsetY,
                        bgZoom = effect.backgroundZoom
                    )
                )
            }
        }

        fun composeFrame(frameBitmaps: List<Bitmap>): Bitmap {
            val merged = composeBackground(
                frameBitmaps, template, outputWidth, outputHeight, effects
            )
            val canvas = Canvas(merged)

            // 叠加人物层（与静态图使用同一套矩阵逻辑，保证效果一致）
            personLayers.forEach { layer ->
                if (layer != null) {
                    drawZoomedPerson(
                        canvas = canvas,
                        personBitmap = layer.person,
                        photoBitmap = layer.photo,
                        destRect = layer.destRect,
                        zoomLevel = layer.zoom,
                        centerX = layer.cx,
                        centerY = layer.cy,
                        cropOffsetX = layer.cropX,
                        cropOffsetY = layer.cropY,
                        backgroundZoom = layer.bgZoom
                    )
                }
            }
            return merged
        }

        if (frames.isEmpty()) {
            encodeFramesToVideo(listOf(composeFrame(bitmaps)), outputFile, minDurationMs = 300)
            return
        }

        val processedFrames = frames.map { frame ->
            val frameBitmaps = bitmaps.toMutableList()
            if (firstVideoIndex < frameBitmaps.size) {
                frameBitmaps[firstVideoIndex] = frame
            }
            composeFrame(frameBitmaps)
        }

        encodeFramesToVideo(processedFrames, outputFile)
    }

    /**
     * 将帧序列编码为 MP4 视频
     * 使用 MediaCodec + MediaMuxer 编码
     */
    private fun encodeFramesToVideo(
        frames: List<Bitmap>,
        outputFile: File,
        minDurationMs: Int = 0
    ) {
        if (frames.isEmpty()) return

        val width = frames[0].width
        val height = frames[0].height
        val frameRate = 30

        // 保证视频时长不小于 minDurationMs（否则相册不认为是实况照片）
        val effectiveFrames = if (minDurationMs > 0) {
            val minFrameCount = ((minDurationMs * frameRate) / 1000.0).toInt().coerceAtLeast(1)
            if (frames.size < minFrameCount) {
                val last = frames.last()
                frames + List(minFrameCount - frames.size) { last }
            } else {
                frames
            }
        } else {
            frames
        }

        // 确保尺寸是偶数（编码器要求）
        val encWidth = if (width % 2 == 0) width else width - 1
        val encHeight = if (height % 2 == 0) height else height - 1

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // 创建编码器 — 使用 ByteBuffer 输入方式
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // 明确指定编码器真正支持的色彩格式，避免用 Flexible 时出现红蓝反色
            val colorFormat = pickSupportedColorFormat(codec)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
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

            // 将每帧转换为编码器支持的 YUV 格式并输入编码器
            val frameDurationUs = 1_000_000L / frameRate
            var frameIndex = 0

            effectiveFrames.forEach { frame ->
                // 缩放帧到编码尺寸
                val scaled = if (frame.width != encWidth || frame.height != encHeight) {
                    Bitmap.createScaledBitmap(frame, encWidth, encHeight, true)
                } else {
                    frame
                }

                // 按编码器支持的格式转换
                val yuv = bitmapToYuv(scaled, encWidth, encHeight, colorFormat)

                // 输入到编码器
                val inputBufferId = codec.dequeueInputBuffer(10_000)
                if (inputBufferId >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferId)
                    inputBuffer?.clear()
                    inputBuffer?.put(yuv)
                    val ptsUs = frameIndex * frameDurationUs
                    codec.queueInputBuffer(inputBufferId, 0, yuv.size, ptsUs, 0)
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
     * 选出编码器真正支持的 YUV 色彩格式
     *
     * 用 COLOR_FormatYUV420Flexible 时，实际内存布局由编码器决定，
     * 我们按 NV12 写进去在某些设备上会出现红蓝反色。
     * 所以优先选明确的 SemiPlanar(NV12) / Planar(I420)。
     */
    private fun pickSupportedColorFormat(codec: MediaCodec): Int {
        val supported = try {
            codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
        } catch (e: Exception) {
            intArrayOf()
        }

        val semiPlanar = 0x15   // COLOR_FormatYUV420SemiPlanar (NV12)
        val planar = 0x13       // COLOR_FormatYUV420Planar (I420)

        return when {
            supported.contains(semiPlanar) -> semiPlanar
            supported.contains(planar) -> planar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
    }

    /**
     * Bitmap → YUV420
     *
     * @param colorFormat 编码器支持的色彩格式
     *   - 0x15 SemiPlanar(NV12)：YYYY + UVUV...
     *   - 0x13 Planar(I420)：YYYY + UUUU + VVVV
     *   - Flexible：按 NV12 处理
     */
    private fun bitmapToYuv(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        colorFormat: Int
    ): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val ySize = width * height
        val uvSize = ySize / 4
        val yuv = ByteArray(ySize + uvSize * 2)

        val isPlanar = colorFormat == 0x13

        var yIndex = 0
        var uIndex = if (isPlanar) ySize else ySize
        var vIndex = if (isPlanar) ySize + uvSize else ySize + 1

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Y 分量
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // U/V 分量：每 2x2 采样一次
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uIndex] = u.coerceIn(0, 255).toByte()
                    yuv[vIndex] = v.coerceIn(0, 255).toByte()
                    if (isPlanar) {
                        uIndex++
                        vIndex++
                    } else {
                        uIndex += 2
                        vIndex += 2
                    }
                }
            }
        }

        return yuv
    }

    /**
     * 将图片裁剪填充到指定矩形区域
     *
     * @param offsetX 手动裁剪位置 -1(靠左) .. 0(居中) .. 1(靠右)
     * @param offsetY 手动裁剪位置 -1(靠上) .. 0(居中) .. 1(靠下)
     */
    private fun drawBitmapCropToFill(
        canvas: Canvas,
        bitmap: Bitmap,
        destRect: RectF,
        paint: Paint,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        zoom: Float = 1f
    ) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height
        val destAspect = destRect.width() / destRect.height()

        // -1..1 → 0..1（0 = 居中）
        val fx = ((offsetX.coerceIn(-1f, 1f) + 1f) / 2f)
        val fy = ((offsetY.coerceIn(-1f, 1f) + 1f) / 2f)
        val bgZoom = if (zoom < 1f) 1f else zoom

        val matrix = Matrix()

        if (srcAspect > destAspect) {
            // 图片更宽 → 按高度铺满，水平方向有多余，可左右移动
            val scale = destRect.height() / bitmap.height * bgZoom
            matrix.setScale(scale, scale)
            val scaledWidth = bitmap.width * scale
            val extra = destRect.width() - scaledWidth     // 负数
            val vertical = (destRect.height() - bitmap.height * scale) / 2f
            matrix.postTranslate(destRect.left + extra * fx, destRect.top + vertical)
        } else {
            // 图片更高 → 按宽度铺满，垂直方向有多余，可上下移动
            val scale = destRect.width() / bitmap.width * bgZoom
            matrix.setScale(scale, scale)
            val scaledHeight = bitmap.height * scale
            val extra = destRect.height() - scaledHeight   // 负数
            val horizontal = (destRect.width() - bitmap.width * scale) / 2f
            matrix.postTranslate(destRect.left + horizontal, destRect.top + extra * fy)
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
     *
     * 注意：必须处理 EXIF 方向，否则竖拍照片会被当成横向渲染（画面被裁歪）。
     * 同时限制最大边长，避免超大图（如 8000px）导致 OOM。
     */
    private fun loadBitmap(uri: Uri): Bitmap {
        // 1. 先读尺寸与方向
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            }
        } catch (e: Exception) {
            // 忽略，下面正常解码会抛出更明确的信息
        }

        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                orientation = ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            // 忽略
        }

        // 2. 计算采样率（最大边 2560，足够 1080 宽输出）
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxSide / sampleSize > 2560) {
            sampleSize *= 2
        }

        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalArgumentException("Failed to load bitmap from $uri")

        // 3. 应用 EXIF 旋转
        return LivePhotoUtils.applyExifOrientation(decoded, orientation)
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
