package com.ajx.botforge.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaUpload] 的回归测试。
 *
 * ## 为什么这段逻辑值得单独测
 *
 * 它是富媒体发送链路上的**唯一本地闸门**：类型判错会让服务端按错误 file_type 处理，
 * 体积放过会让一次注定失败的请求先占满上行带宽（手机流量场景尤其疼），
 * 文件名没洗干净则可能把 `../` 一路带进 HTTP body 与日志。
 *
 * 这三件事在真机上都表现为「图片发不出去」，但根因完全不同，
 * 所以这里按「判类型 / 判体积 / 洗名字」三组分开钉住边界。
 *
 * 本文件只依赖 JUnit，不 import android 与 org.json：
 * 一旦引入它们，JVM 单测会直接抛 not mocked。
 */
class MediaUploadTest {

    private val MB = 1024L * 1024L

    // ------------------------------------------------------------ 判类型

    /** 普通小写后缀要认得出图片 */
    @Test
    fun inferType_png_isImage() {
        assertEquals(FileType.IMAGE, MediaUpload.inferFileType("a.png", null))
    }

    /** 后缀大小写不敏感：a.MP4 与 a.mp4 必须同解 */
    @Test
    fun inferType_uppercaseMp4_isVideo() {
        assertEquals(FileType.VIDEO, MediaUpload.inferFileType("a.MP4", null))
    }

    /** 认不出的后缀归 FILE，不能瞎猜成图片 */
    @Test
    fun inferType_unknownExtension_isFile() {
        assertEquals(FileType.FILE, MediaUpload.inferFileType("a.unknownext", null))
    }

    /** 没有后缀时才退回 MIME；这条路径覆盖「上游只给 content-type」的情况 */
    @Test
    fun inferType_withoutExtension_fallsBackToMime() {
        assertEquals(FileType.IMAGE, MediaUpload.inferFileType("noext", "image/png"))
        assertEquals(FileType.VIDEO, MediaUpload.inferFileType("noext", "video/mp4"))
        assertEquals(FileType.VOICE, MediaUpload.inferFileType("noext", "audio/silk"))
        assertEquals(FileType.FILE, MediaUpload.inferFileType("noext", "application/octet-stream"))
    }

    /** 带路径的文件名也要能取到后缀，不能被目录里的点干扰 */
    @Test
    fun inferType_withDirectoryPath_isCorrect() {
        assertEquals(FileType.IMAGE, MediaUpload.inferFileType("/tmp/x.y/a.jpeg", null))
        assertEquals(FileType.VOICE, MediaUpload.inferFileType("C:\\rec\\v.silk", null))
    }

    /** 四个枚举值的 code 必须与接口约定一致，写错就是服务端按错类型处理 */
    @Test
    fun fileType_codes_matchContract() {
        assertEquals(1, FileType.IMAGE.code)
        assertEquals(2, FileType.VIDEO.code)
        assertEquals(3, FileType.VOICE.code)
        assertEquals(4, FileType.FILE.code)
    }

    // ------------------------------------------------------------ 判 MIME

    /** 任务书要求覆盖的九种后缀，逐个钉住 */
    @Test
    fun inferMime_coversCommonExtensions() {
        assertEquals("image/png", MediaUpload.inferMime("a.png"))
        assertEquals("image/jpeg", MediaUpload.inferMime("a.jpg"))
        assertEquals("image/jpeg", MediaUpload.inferMime("a.JPEG"))
        assertEquals("image/gif", MediaUpload.inferMime("a.gif"))
        assertEquals("image/webp", MediaUpload.inferMime("a.webp"))
        assertEquals("video/mp4", MediaUpload.inferMime("a.mp4"))
        assertEquals("text/plain", MediaUpload.inferMime("a.txt"))
        assertEquals("application/json", MediaUpload.inferMime("a.json"))
        assertEquals("application/zip", MediaUpload.inferMime("a.zip"))
    }

    /** 认不出时必须给一个合法的兜底值，不能返回空串 */
    @Test
    fun inferMime_unknown_fallsBackToOctetStream() {
        assertEquals("application/octet-stream", MediaUpload.inferMime("a.unknownext"))
        assertEquals("application/octet-stream", MediaUpload.inferMime("noext"))
    }

    // ------------------------------------------------------------ 判体积

    /** 图片边界：10MB 整要过，10MB+1 字节要拒 */
    @Test
    fun checkSize_imageBoundary() {
        assertNull(MediaUpload.checkSize(FileType.IMAGE, 10L * MB))
        assertNull(MediaUpload.checkSize(FileType.IMAGE, 10L * MB - 1))
        assertNotNull(MediaUpload.checkSize(FileType.IMAGE, 10L * MB + 1))
        assertNotNull(MediaUpload.checkSize(FileType.IMAGE, 11L * MB))
    }

    /** 四类各自的边界都要卡住，避免只测图片漏掉别的常量 */
    @Test
    fun checkSize_allTypesAtOwnLimit() {
        assertNull(MediaUpload.checkSize(FileType.VIDEO, 30L * MB))
        assertNotNull(MediaUpload.checkSize(FileType.VIDEO, 30L * MB + 1))
        assertNull(MediaUpload.checkSize(FileType.VOICE, 2L * MB))
        assertNotNull(MediaUpload.checkSize(FileType.VOICE, 2L * MB + 1))
        assertNull(MediaUpload.checkSize(FileType.FILE, 100L * MB))
        assertNotNull(MediaUpload.checkSize(FileType.FILE, 100L * MB + 1))
    }

    /** 空文件必须拒：0 字节发出去没有任何意义，只会浪费一次请求 */
    @Test
    fun checkSize_emptyOrNegative_isRejected() {
        assertNotNull(MediaUpload.checkSize(FileType.IMAGE, 0L))
        assertNotNull(MediaUpload.checkSize(FileType.FILE, -1L))
    }

    /** 拒绝原因要是能直接展示给用户的中文，不能是英文异常串 */
    @Test
    fun checkSize_reason_isReadableChinese() {
        val reason = MediaUpload.checkSize(FileType.IMAGE, 11L * MB)
        assertNotNull(reason)
        assertTrue(reason!!.contains("图片"))
        assertTrue(reason.contains("11.0MB"))
        assertTrue(reason.contains("10.0MB"))
    }

    /** 上限表本身也要可查，避免 limitOf 与 checkSize 两处常量漂移 */
    @Test
    fun limitOf_matchesDeclaredCeilings() {
        assertEquals(10L * MB, MediaUpload.limitOf(FileType.IMAGE))
        assertEquals(30L * MB, MediaUpload.limitOf(FileType.VIDEO))
        assertEquals(2L * MB, MediaUpload.limitOf(FileType.VOICE))
        assertEquals(100L * MB, MediaUpload.limitOf(FileType.FILE))
    }

    // ------------------------------------------------------------ 洗名字

    /** 目录穿越：洗完之后不能残留斜杠，也不能残留两个连续的点 */
    @Test
    fun sanitize_traversal_isNeutralized() {
        val out = MediaUpload.sanitizeFileName("../../etc/passwd")
        assertEquals("passwd", out)
        assertTrue(!out.contains("/"))
        assertTrue(!out.contains(".."))
    }

    /** Windows 风格反斜杠同样要压平 */
    @Test
    fun sanitize_backslashPath_isFlattened() {
        val out = MediaUpload.sanitizeFileName("..\\..\\windows\\evil.exe")
        assertEquals("evil.exe", out)
        assertTrue(!out.contains("\\"))
        assertTrue(!out.contains(".."))
    }

    /** 夹在中间的点对也要抹掉：`a..b.png` 不能留 `..` */
    @Test
    fun sanitize_embeddedDotDot_isRemoved() {
        assertEquals("ab.png", MediaUpload.sanitizeFileName("a..b.png"))
    }

    /** 超长名字要截断到上限内，并且保住后缀，方便看日志认文件 */
    @Test
    fun sanitize_tooLong_isTruncatedKeepingExtension() {
        val longName = "a".repeat(200) + ".png"
        val out = MediaUpload.sanitizeFileName(longName)
        assertTrue(out.length <= MediaUpload.MAX_NAME_LEN)
        assertTrue(out.endsWith(".png"))
    }

    /** 没有后缀的超长名字也要截断，不能原样放过 */
    @Test
    fun sanitize_tooLongWithoutExtension_isTruncated() {
        val out = MediaUpload.sanitizeFileName("b".repeat(200))
        assertEquals(MediaUpload.MAX_NAME_LEN, out.length)
    }

    /** 空串 / 纯路径 / 控制字符清洗后必须给出可用名字，不能返回空串 */
    @Test
    fun sanitize_emptyOrDirty_fallsBackToPlaceholder() {
        assertEquals("file", MediaUpload.sanitizeFileName(""))
        assertEquals("file", MediaUpload.sanitizeFileName("/"))
        assertEquals("file", MediaUpload.sanitizeFileName("\n\t "))
    }

    /**
     * 只剩点号的名字必须换成占位名。
     *
     * `"..."` 先抹掉 `..` 会剩下 `"."`，而 `"."` 在文件系统里是当前目录 ——
     * 比空串更危险，必须被拦住。
     */
    @Test
    fun sanitize_onlyDots_fallsBackToPlaceholder() {
        assertEquals("file", MediaUpload.sanitizeFileName("."))
        assertEquals("file", MediaUpload.sanitizeFileName(".."))
        assertEquals("file", MediaUpload.sanitizeFileName("..."))
        assertEquals("file", MediaUpload.sanitizeFileName("....."))
    }

    /** 正常名字不能被过度清洗：中文、空格、连字符都要原样保留 */
    @Test
    fun sanitize_normalName_isPreserved() {
        assertEquals("我的 图片-1.png", MediaUpload.sanitizeFileName("我的 图片-1.png"))
    }
}
