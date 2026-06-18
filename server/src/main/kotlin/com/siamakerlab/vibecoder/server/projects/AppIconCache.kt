package com.siamakerlab.vibecoder.server.projects

import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val log = KotlinLogging.logger {}

/**
 * v1.137.3 — Project list launcher icon resize + in-memory cache.
 *
 * Downsizes source icons to [ICON_SIZE]px PNG (2× display size for HiDPI) and
 * caches the result in memory. Automatically regenerates when the source file
 * (path, mtime, size) changes. Falls back to original bytes on decode failure.
 */
object AppIconCache {

    const val ICON_SIZE = 64

    class Entry(
        val bytes: ByteArray,
        val contentType: String,
        val etag: String,
        internal val srcPath: String,
        internal val srcMtime: Long,
        internal val srcSize: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(projectId: String, src: Path): Entry? {
        val attrs = runCatching { Files.readAttributes(src, java.nio.file.attribute.BasicFileAttributes::class.java) }
            .getOrNull() ?: return null
        val mtime = attrs.lastModifiedTime().toMillis()
        val size = attrs.size()
        val srcPath = src.toString()
        cache[projectId]?.let { e ->
            if (e.srcPath == srcPath && e.srcMtime == mtime && e.srcSize == size) return e
        }
        val raw = runCatching { Files.readAllBytes(src) }.getOrNull() ?: return null
        val (bytes, contentType) = resize(raw, srcPath)
        val etag = "\"${java.lang.Long.toHexString(mtime)}-${java.lang.Long.toHexString(size)}-$ICON_SIZE\""
        val entry = Entry(bytes, contentType, etag, srcPath, mtime, size)
        cache[projectId] = entry
        return entry
    }

    private fun resize(raw: ByteArray, srcPath: String): Pair<ByteArray, String> {
        val fallbackType = if (srcPath.endsWith(".webp", ignoreCase = true)) "image/webp" else "image/png"
        val img = runCatching { ImageIO.read(raw.inputStream()) }.getOrNull()
            ?: return raw to fallbackType
        if (img.width <= ICON_SIZE && img.height <= ICON_SIZE) return raw to fallbackType
        return runCatching {
            val scale = ICON_SIZE.toDouble() / maxOf(img.width, img.height)
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(img, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }
            val buf = ByteArrayOutputStream(8 * 1024)
            ImageIO.write(out, "png", buf)
            buf.toByteArray() to "image/png"
        }.getOrElse { e ->
            log.debug(e) { "app icon resize failed ($srcPath); serving original" }
            raw to fallbackType
        }
    }
}
