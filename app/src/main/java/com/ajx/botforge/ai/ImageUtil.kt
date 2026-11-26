package com.ajx.botforge.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ajx.botforge.data.Logger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 图片处理：下载 QQ 附件 → 压缩 → base64 data URL
 *
 * QQ 官方机器人图片消息里 content 为空，图片在 attachments[].url，
 * 该 URL 有时效性且可能要求特定 UA，所以必须即时下载，不能缓存 URL。
 */
object ImageUtil {

    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** QQ 官方下载域名对非 QQ UA 容易挂住不返回，优先用 QQBot UA */
    private const val UA_QQ = "QQBot/1.0 (Android 12)"

    /** 单张图总耗时上限，超过立即放弃，避免阻塞消息线程 */
    private const val TOTAL_TIMEOUT_MS = 8000L

    /** 单张图最大边长，超过就等比缩放；控制 token 与传输体积 */
    private const val MAX_SIDE = 1280

    /** 压缩后 JPEG 质量 */
    private const val QUALITY = 85

    data class Loaded(val dataUrl: String, val width: Int, val height: Int)

    /** 下载并转成 data:image/jpeg;base64,... */
    fun load(url: String): Loaded? {
        val raw = download(url) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
            ?: run {
                Logger.w("Img", "解码失败，非图片或格式不支持")
                return null
            }
        val scaled = scale(bmp)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val bytes = out.toByteArray()
        val srcW = bmp.width
        val srcH = bmp.height
        val outW = scaled.width
        val outH = scaled.height
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()

        val b64 = Base64.getEncoder().encodeToString(bytes)
        Logger.i("Img", "已加载图片 ${srcW}x$srcH -> ${outW}x$outH, ${bytes.size / 1024}KB")
        return Loaded("data:image/jpeg;base64,$b64", outW, outH)
    }

    private fun download(url: String): ByteArray? {
        // 用独立线程 + 总超时兜底，防止 QQ 图片域名挂住不返回拖死消息线程
        val task = java.util.concurrent.FutureTask<ByteArray?> { downloadInner(url) }
        val th = Thread(task, "img-dl")
        th.isDaemon = true
        th.start()
        return try {
            task.get(TOTAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Logger.w("Img", "下载超时 ${TOTAL_TIMEOUT_MS}ms，放弃")
            runCatching { task.cancel(true) }
            runCatching { th.interrupt() }
            null
        } catch (e: Exception) {
            Logger.w("Img", "下载异常: ${e.message}")
            null
        }
    }

    /** 依次尝试 QQBot UA 与浏览器 UA，任一成功即返回 */
    private fun downloadInner(url: String): ByteArray? {
        for (ua in listOf(UA_QQ, UA)) {
            val data = tryOnce(url, ua)
            if (data != null) return data
        }
        return null
    }

    private fun tryOnce(url: String, ua: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "image/*,*/*")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.w("Img", "下载失败 HTTP $code (ua=${ua.take(8)})")
                return null
            }
            val ct = conn.contentType ?: ""
            if (ct.isNotEmpty() && !ct.startsWith("image/") && !ct.contains("octet-stream")) {
                Logger.w("Img", "返回非图片类型: $ct")
                return null
            }
            val data = conn.inputStream.use { it.readBytes() }
            if (data.size > 12 * 1024 * 1024) {
                Logger.w("Img", "图片过大 ${data.size / 1024 / 1024}MB，跳过")
                return null
            }
            if (data.isEmpty()) {
                Logger.w("Img", "下载到空数据")
                return null
            }
            data
        } catch (e: Exception) {
            Logger.w("Img", "下载异常(${ua.take(8)}): ${e.message}")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= MAX_SIDE) return src
        val ratio = MAX_SIDE.toFloat() / max
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** 批量加载，任一张失败就跳过 */
    fun loadAll(urls: List<String>, limit: Int = 3): List<Loaded> {
        val out = mutableListOf<Loaded>()
        for (u in urls.take(limit)) {
            val l = load(u) ?: continue
            out.add(l)
        }
        return out
    }
}