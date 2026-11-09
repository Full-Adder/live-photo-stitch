package com.example.livephotostitch.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
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
 * 单张照片的处理配置
 */
data class PhotoEffect(
    /** 是否启用抠图+放大效果 */
    val cutoutEnabled: Boolean = false,
    /** 放大倍数 (1.0 = 不放大) */
    val zoomLevel: Float = 1.0f,
    /** 人物在照片中的水平中心位置 (0-1)，默认居中 */
    val personCenterX: Float = 0.5f,
    /** 人物在照片中的垂直中心位置 (0-1)，默认居中 */
    val personCenterY: Float = 0.5f,
    /** 背景虚化程度 (0-1)，0 = 不虚化 */
    val backgroundBlur: Float = 0f
)

/**
 * 实况照片拼接引擎
 *
 * 图层结构：
 *   1. 背景层 —— 3 张照片按模板拼接（保持原图大小，不超出边界）
 *   2. 前景层 —— 被选中照片的人物（抠图后放大，可超出原图边界，覆盖到相邻照片上）
 */
class LivePhotoStitchEngine(private val context: Context) {

    /**
     * 拼接实况照片（带单张照片的抠图+放大效果）
     */
    suspend fun stitchLivePhotos(
        photos: List<LivePhoto>,
        template: StitchTemplate,
        outputDir: File,
        effects: List<PhotoEffect> = photos.map { PhotoEffect() },
        cutoutProcessor: PortraitCutoutProcessor? = null
    ): Result<LivePhoto> = withContext(Dispatchers.IO) {
        try {
            require(photos.size == 3) { "需要恰好3张照片" }
            require(effects.size == photos.size) { "effects 数量必须与 photos 一致" }

            // 1. 加载所有静态图片
            val bitmaps = photos.map { loadBitmap(it.staticImageUri) }

            // 2. 计算输出尺寸
            val outputWidth = 1080
            val outputHeight = (outputWidth / template.aspectRatio).toInt()

            // 3. 绘制背景层（所有照片按模板拼接，不超出边界）
            val backgroundLayer = composeBackground(bitmaps, template, outputWidth, outputHeight)

            // 4. 处理需要抠图+放大的照片（前景层）
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

            // 7. 处理视频片段（应用同样的效果到每一帧，保持实况照片效果）
            val videoFile = processVideoSegments(
                photos = photos,
                template = template,
                outputDir = outputDir,
                effects = effects,
                cutoutProcessor = cutoutProcessor
            )

            // 8. 创建新的实况照片
            val livePhoto = LivePhoto(
                id = UUID.randomUUID().toString(),
                staticImageUri = Uri.fromFile(staticImageFile),
                videoUri = videoFile?.let { Uri.fromFile(it) },
                videoDuration = calculateTotalDuration(photos),
                creationDate = System.currentTimeMillis(),
                width = outputWidth,
                height = outputHeight,
                orientation = 0
            )

            Result.success(livePhoto)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 绘制背景层 —— 所有照片按模板拼接，不超出各自边界
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

        canvas.drawColor(android.graphics.Color.WHITE)

        template.layout.forEachIndexed { index, item ->
            if (index < bitmaps.size) {
                val bitmap = bitmaps[index]
                val destRect = item.toRectF(outputWidth, outputHeight)
                drawBitmapIntoRect(canvas, bitmap, destRect, paint)
            }
        }

        return outputBitmap
    }

    /**
     * 处理前景层 —— 对启用了抠图+放大的照片，提取人物并放大绘制
     *
     * 关键效果：
     *   - 背景（原图的其余部分）保持原图大小，不超出边界
     *   - 人物放大后可以超出原图边界，覆盖到相邻照片上
     */
    private suspend fun processForeground(
        bitmaps: List<Bitmap>,
        template: StitchTemplate,
        outputWidth: Int,
        outputHeight: Int,
        effects: List<PhotoEffect>,
        cutoutProcessor: PortraitCutoutProcessor?
    ): Bitmap? {
        val foregroundBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(foregroundBitmap)
        var hasForeground = false

        effects.forEachIndexed { index, effect ->
            if (!effect.cutoutEnabled || effect.zoomLevel <= 1.0f) return@forEachIndexed
            if (index >= bitmaps.size || index >= template.layout.size) return@forEachIndexed
            if (cutoutProcessor == null) return@forEachIndexed

            val bitmap = bitmaps[index]
            val item = template.layout[index]
            val destRect = item.toRectF(outputWidth, outputHeight)

            // 抠图（suspend 调用）
            val cutoutResult = cutoutProcessor.cutout(bitmap) ?: return@forEachIndexed

            // 在原图坐标系中放大人物
            val zoomedPerson = cutoutProcessor.zoomPerson(
                personBitmap = cutoutResult.personBitmap,
                maskBitmap = cutoutResult.maskBitmap,
                zoomLevel = effect.zoomLevel,
                centerX = effect.personCenterX,
                centerY = effect.personCenterY
            )

            // 将放大后的人物映射到拼接画布的对应位置
            val (scaleFactor, offsetX, offsetY) = calcPlacement(bitmap, destRect)

            val scaledPersonWidth = (zoomedPerson.width * scaleFactor).toInt()
            val scaledPersonHeight = (zoomedPerson.height * scaleFactor).toInt()
            val scaledPerson = Bitmap.createScaledBitmap(
                zoomedPerson, scaledPersonWidth, scaledPersonHeight, true
            )

            // 绘制放大后的人物到画布
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
     * 处理视频片段 —— 将抠图+放大效果应用到每一帧
     *
     * 这是保持实况照片效果的关键：静态图片用了什么效果，视频每一帧也要用同样的效果。
     */
    private suspend fun processVideoSegments(
        photos: List<LivePhoto>,
        template: StitchTemplate,
        outputDir: File,
        effects: List<PhotoEffect>,
        cutoutProcessor: PortraitCutoutProcessor?
    ): File? {
        val videosWithUri = photos.filter { it.videoUri != null }
        if (videosWithUri.isEmpty()) return null

        // 简化实现：只处理第一个有视频的照片
        val firstVideo = videosWithUri.first()
        val videoUri = firstVideo.videoUri ?: return null

        // 检查是否需要对这帧视频应用效果
        val photoIndex = photos.indexOf(firstVideo)
        val effect = effects.getOrNull(photoIndex) ?: PhotoEffect()
        val needsEffect = effect.cutoutEnabled && effect.zoomLevel > 1.0f

        return try {
            val outputFile = File(outputDir, "video_${UUID.randomUUID()}.mp4")

            if (!needsEffect) {
                // 不需要效果，直接复制视频
                copyVideo(videoUri, outputFile)
            } else {
                // 需要效果：逐帧处理视频
                processVideoWithEffect(
                    videoUri = videoUri,
                    outputFile = outputFile,
                    effect = effect,
                    template = template,
                    photoIndex = photoIndex,
                    cutoutProcessor = cutoutProcessor
                )
            }

            outputFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 逐帧处理视频，应用抠图+放大效果
     */
    private suspend fun processVideoWithEffect(
        videoUri: Uri,
        outputFile: File,
        effect: PhotoEffect,
        template: StitchTemplate,
        photoIndex: Int,
        cutoutProcessor: PortraitCutoutProcessor?
    ) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L

        if (durationMs == 0L) {
            retriever.release()
            copyVideo(videoUri, outputFile)
            return
        }

        // 每 33ms 提取一帧（约30fps）
        val frameIntervalUs = 33_000L
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
            copyVideo(videoUri, outputFile)
            return
        }

        // 对每一帧应用效果
        val processedFrames = frames.map { frame ->
            applyEffectToFrame(
                frame = frame,
                effect = effect,
                template = template,
                photoIndex = photoIndex,
                cutoutProcessor = cutoutProcessor
            )
        }

        // 将处理后的帧编码为视频
        encodeFramesToVideo(processedFrames, outputFile)
    }

    /**
     * 对单帧应用效果
     */
    private suspend fun applyEffectToFrame(
        frame: Bitmap,
        effect: PhotoEffect,
        template: StitchTemplate,
        photoIndex: Int,
        cutoutProcessor: PortraitCutoutProcessor?
    ): Bitmap {
        val outputWidth = 1080
        val outputHeight = (outputWidth / template.aspectRatio).toInt()

        // 1. 绘制背景层
        val background = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(background)
        bgCanvas.drawColor(android.graphics.Color.WHITE)

        // 只绘制这一帧对应的区域
        if (photoIndex < template.layout.size) {
            val item = template.layout[photoIndex]
            val destRect = item.toRectF(outputWidth, outputHeight)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            drawBitmapIntoRect(bgCanvas, frame, destRect, paint)
        }

        // 2. 应用抠图+放大
        if (cutoutProcessor != null && effect.cutoutEnabled && effect.zoomLevel > 1.0f) {
            val cutoutResult = cutoutProcessor.cutout(frame)

            if (cutoutResult != null) {
                val zoomedPerson = cutoutProcessor.zoomPerson(
                    personBitmap = cutoutResult.personBitmap,
                    maskBitmap = cutoutResult.maskBitmap,
                    zoomLevel = effect.zoomLevel,
                    centerX = effect.personCenterX,
                    centerY = effect.personCenterY
                )

                val destRect = if (photoIndex < template.layout.size) {
                    template.layout[photoIndex].toRectF(outputWidth, outputHeight)
                } else {
                    return background
                }

                val (scaleFactor, offsetX, offsetY) = calcPlacement(frame, destRect)

                val scaledPersonWidth = (zoomedPerson.width * scaleFactor).toInt()
                val scaledPersonHeight = (zoomedPerson.height * scaleFactor).toInt()
                val scaledPerson = Bitmap.createScaledBitmap(
                    zoomedPerson, scaledPersonWidth, scaledPersonHeight, true
                )

                bgCanvas.drawBitmap(scaledPerson, offsetX, offsetY, Paint(Paint.ANTI_ALIAS_FLAG))
            }
        }

        return background
    }

    /**
     * 将处理后的帧编码为视频
     *
     * 简化实现：将帧序列编码为 MP4
     * 生产环境建议使用 Media3 Transformer
     */
    private fun encodeFramesToVideo(frames: List<Bitmap>, outputFile: File) {
        if (frames.size <= 1) {
            frames.firstOrNull()?.let { saveBitmap(it, outputFile) }
            return
        }

        val width = frames[0].width
        val height = frames[0].height
        val frameRate = 30

        try {
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x00000017) // COLOR_FormatSurface
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val videoTrackIndex = muxer.addTrack(format)
            muxer.start()

            val bufferInfo = MediaCodec.BufferInfo()

            // 逐帧绘制到 Surface
            frames.forEachIndexed { index, frame ->
                val canvas = surface.lockCanvas(null)
                canvas.drawBitmap(frame, 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)

                // 等待编码完成
                var outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outputBufferId >= 0) {
                    val encodedData = codec.getOutputBuffer(outputBufferId)
                    if (encodedData != null && bufferInfo.size > 0) {
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                    outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                }
            }

            // 发送结束信号
            codec.signalEndOfInputStream()
            codec.dequeueOutputBuffer(bufferInfo, 10_000)

            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()

        } catch (e: Exception) {
            // 降级：保存第一帧为静态图片
            frames.firstOrNull()?.let { saveBitmap(it, outputFile) }
        }
    }

    /**
     * 直接复制视频文件
     */
    private fun copyVideo(videoUri: Uri, outputFile: File) {
        context.contentResolver.openInputStream(videoUri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 将图片绘制到指定矩形区域（居中裁剪填充）
     */
    private fun drawBitmapIntoRect(canvas: Canvas, bitmap: Bitmap, destRect: RectF, paint: Paint) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height
        val destAspect = destRect.width() / destRect.height()

        val matrix = Matrix()

        if (srcAspect > destAspect) {
            // 源图片更宽，按高度缩放，裁剪左右
            val scale = destRect.height() / bitmap.height
            matrix.setScale(scale, scale)
            val scaledWidth = bitmap.width * scale
            val offsetX = (destRect.width() - scaledWidth) / 2
            matrix.postTranslate(destRect.left + offsetX, destRect.top)
        } else {
            // 源图片更高，按宽度缩放，裁剪上下
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
     * @return Triple(缩放因子, 画布X偏移, 画布Y偏移)
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
     * 加载Bitmap
     */
    private fun loadBitmap(uri: Uri): Bitmap {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            android.graphics.BitmapFactory.decodeStream(stream)
        } ?: throw IllegalArgumentException("Failed to load bitmap from $uri")
    }

    /**
     * 保存Bitmap
     */
    private fun saveBitmap(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
    }

    /**
     * 计算总时长
     */
    private fun calculateTotalDuration(photos: List<LivePhoto>): Long {
        return photos.sumOf { it.videoDuration }
    }
}

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
