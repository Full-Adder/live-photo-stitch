package com.example.livephotostitch.utils

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Motion Photo（动态照片）写入器
 *
 * 格式说明（Google Motion Photo / 小米相机同款）：
 *   [JPEG 数据（含 XMP）][MP4 数据]
 *
 * JPEG 的 APP1(XMP) 段里写入：
 *   GCamera:MicroVideo="1"
 *   GCamera:MicroVideoOffset="<mp4 字节数>"
 *   GCamera:MicroVideoPresentationTimestampUs="0"
 *
 * MicroVideoOffset 的定义是：从文件末尾往回数，到 MP4 起始位置的字节数。
 * 因为 MP4 直接接在 JPEG 后面，所以这个值就等于 MP4 的长度。
 *
 * 只有按这个格式写出去，系统相册（小米/华为/Google 相册）才会把这张 JPG
 * 识别成"实况照片"并显示动态效果；否则只是张普通静态图。
 */
object MotionPhotoWriter {

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/"

    /**
     * 把 JPEG 与 MP4 合成为一个 Motion Photo（仍然是 .jpg 文件）
     *
     * @return 合成后的完整字节；失败返回 null
     */
    fun createMotionPhoto(jpegBytes: ByteArray, mp4Bytes: ByteArray): ByteArray? {
        if (jpegBytes.size < 4 || mp4Bytes.isEmpty()) return null

        // 校验是否是 JPEG（FF D8 开头）
        if ((jpegBytes[0].toInt() and 0xFF) != 0xFF || (jpegBytes[1].toInt() and 0xFF) != 0xD8) {
            return null
        }

        return try {
            val xmpPacket = buildXmpPacket(mp4Bytes.size)
            val app1 = buildApp1Segment(xmpPacket)

            val out = ByteArrayOutputStream(jpegBytes.size + mp4Bytes.size + app1.size)
            // SOI
            out.write(jpegBytes, 0, 2)
            // 插入 XMP
            out.write(app1)
            // 原始 JPEG 剩余部分（含 EOI）
            out.write(jpegBytes, 2, jpegBytes.size - 2)
            // 追加 MP4
            out.write(mp4Bytes)
            out.flush()

            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把 Bitmap 编码成 JPEG 字节
     */
    fun bitmapToJpeg(bitmap: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * 直接写入输出流（避免大文件多次拷贝）
     */
    fun writeMotionPhoto(
        jpegBytes: ByteArray,
        mp4Bytes: ByteArray,
        output: OutputStream
    ): Boolean {
        val merged = createMotionPhoto(jpegBytes, mp4Bytes) ?: return false
        return try {
            output.write(merged)
            output.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 构建 XMP 数据包
     */
    private fun buildXmpPacket(mp4Length: Int): ByteArray {
        val xmp = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("  <rdf:Description rdf:about=\"\"\n")
            append("    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
            append("    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
            append("    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
            append("    GCamera:MicroVideo=\"1\"\n")
            append("    GCamera:MicroVideoVersion=\"1\"\n")
            append("    GCamera:MicroVideoOffset=\"$mp4Length\"\n")
            append("    GCamera:MicroVideoPresentationTimestampUs=\"0\">\n")
            append("   <Container:Directory>\n")
            append("    <rdf:Seq>\n")
            append("     <rdf:li rdf:parseType=\"Resource\">\n")
            append("      <Container:Item\n")
            append("        Item:Mime=\"video/mp4\"\n")
            append("        Item:Semantic=\"MotionPhoto\"\n")
            append("        Item:Length=\"$mp4Length\"\n")
            append("        Item:Padding=\"0\"/>\n")
            append("     </rdf:li>\n")
            append("    </rdf:Seq>\n")
            append("   </Container:Directory>\n")
            append("  </rdf:Description>\n")
            append(" </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
        return xmp.toByteArray(Charsets.UTF_8)
    }

    /**
     * 构建 APP1 段（含长度字段）
     */
    private fun buildApp1Segment(xmpPacket: ByteArray): ByteArray {
        val payloadSize = XMP_NAMESPACE.toByteArray(Charsets.US_ASCII).size + xmpPacket.size
        // 段总长度 = 2(长度字段本身) + payload
        val segmentLength = payloadSize + 2

        val segment = ByteArray(2 + segmentLength)
        var pos = 0
        segment[pos++] = 0xFF.toByte()
        segment[pos++] = 0xE1.toByte()                  // APP1 marker
        segment[pos++] = ((segmentLength shr 8) and 0xFF).toByte()
        segment[pos++] = (segmentLength and 0xFF).toByte()

        val nsBytes = XMP_NAMESPACE.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nsBytes, 0, segment, pos, nsBytes.size)
        pos += nsBytes.size

        System.arraycopy(xmpPacket, 0, segment, pos, xmpPacket.size)

        return segment
    }
}
