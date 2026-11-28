package com.ajx.botforge.qq

import java.util.Locale

/**
 * 富媒体业务类型，code 直接就是 QQ 接口里的 file_type 取值。
 *
 * 依据等级 A（官方 SDK 源码可查）：botpy 的 post_group_file / post_c2c_file 与
 * botgo 的 RichMediaMessage 都写着「1 图片、2 视频、3 语音（目前只支持 silk）、4 文件」。
 * 注意 4（文件）在官方注释里标着「暂不开放」，真机很可能直接拒绝。
 */
enum class FileType(val code: Int) {
    IMAGE(1),
    VIDEO(2),
    VOICE(3),
    FILE(4)
}

/**
 * QQ 富媒体（图片 / 视频 / 语音 / 文件）的纯逻辑助手。
 *
 * 本文件刻意不依赖 android、androidx 与 org.json：它只负责「看文件名猜类型、
 * 猜 MIME、验体积、洗文件名」这些纯判断，好让它们能在 JVM 单测里直接跑
 * （本项目 Android 单测不做 android.jar mock，一碰 android 类就抛异常）。
 * 真正发 HTTP 的部分在 QqApi.uploadMedia / sendImage / sendFile。
 *
 * ## 依据等级
 *
 * A 级：file_type 的取值与「语音只支持 silk」来自官方 SDK 注释。
 *
 * D 级（**无官方硬性数字，下面是保守估值，需真机验证**）：
 * 四个体积上限。它们唯一的作用是「在浪费一次网络往返之前给出可读的拒绝原因」，
 * 不是权威约束 —— 通过本地预检的文件服务端仍可能拒绝，服务端也可能比这里更宽松。
 * 真机验证后请回来改这四个常量。
 */
object MediaUpload {

    /** 文件名长度上限：太长会让请求体、日志与 UI 都不好看 */
    const val MAX_NAME_LEN = 100

    private const val MB = 1024L * 1024L

    /** 图片上限：保守估值，需真机验证 */
    private const val IMAGE_MAX = 10L * MB

    /** 视频上限：保守估值，需真机验证 */
    private const val VIDEO_MAX = 30L * MB

    /** 语音上限：保守估值，需真机验证 */
    private const val VOICE_MAX = 2L * MB

    /** 文件上限：保守估值，需真机验证（且 file_type=4 官方标注暂不开放） */
    private const val FILE_MAX = 100L * MB

    /** 图片扩展名：官方 doc 只点名 png/jpg，其余是常见格式，服务端未必都收 */
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    /** 视频扩展名：官方 doc 只点名 mp4，其余是常见格式，服务端未必都收 */
    private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "avi", "mkv", "flv")

    /**
     * 语音扩展名：官方只支持 silk，其余归到 VOICE 只为了让本地提示更准确。
     * 把 mp3/amr 归成 VOICE 意味着发出去大概率被服务端拒绝（依据等级 D）。
     */
    private val VOICE_EXT = setOf("silk", "slk", "amr", "mp3", "wav", "m4a", "ogg")

    /** 扩展名到 MIME。audio/silk 是非标准 MIME，仅用于给上传请求一个像样的头 */
    private val MIME_BY_EXT = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "m4v" to "video/x-m4v",
        "silk" to "audio/silk",
        "slk" to "audio/silk",
        "amr" to "audio/amr",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "application/xml",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive"
    )

    /**
     * 按文件名（必要时退回 MIME）判定业务类型。
     *
     * 先看扩展名再看 MIME：扩展名是上传方自己的意图，MIME 常常是
     * application/octet-stream 这种没信息量的兜底值。
     * 扩展名大小写不敏感（a.MP4 与 a.mp4 等价）。
     */
    fun inferFileType(fileName: String, mime: String?): FileType {
        when (extensionOf(fileName)) {
            in IMAGE_EXT -> return FileType.IMAGE
            in VIDEO_EXT -> return FileType.VIDEO
            in VOICE_EXT -> return FileType.VOICE
        }
        // 扩展名认不出时才看 MIME：有些来源文件名没有后缀，只给 MIME
        val m = mime?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            m.startsWith("image/") -> FileType.IMAGE
            m.startsWith("video/") -> FileType.VIDEO
            m.startsWith("audio/") -> FileType.VOICE
            else -> FileType.FILE
        }
    }

    /**
     * 按文件名推断 MIME；认不出时返回 application/octet-stream。
     *
     * 这个值只会用在 PUT 上传的 Content-Type 上，不参与业务判定。
     */
    fun inferMime(fileName: String): String =
        MIME_BY_EXT[extensionOf(fileName)] ?: "application/octet-stream"

    /**
     * 体积预检：通过返回 null，不通过返回中文原因。
     *
     * 上限是保守估值（依据等级 D，需真机验证），见本文件顶部说明。
     * 0 字节与负数一律视为空文件拒绝：QQ 侧对空文件没有意义，
     * 提前拒掉可以省一次注定失败的请求。
     */
    fun checkSize(type: FileType, bytes: Long): String? {
        if (bytes <= 0L) return "文件内容为空（0 字节）"
        val limit = limitOf(type)
        if (bytes <= limit) return null
        // 同时给出字节数：上限只有 1MB 级时，四舍五入到 0.1MB 会出现
        // 「10.0MB 超过 10.0MB」这种自相矛盾的提示
        return "文件过大：${humanSize(bytes)} 超过${labelOf(type)}上限 ${humanSize(limit)}" +
            "（实际 $bytes 字节 / 上限 $limit 字节；本地保守预检，需真机验证）"
    }

    /**
     * 清洗文件名：去掉路径分隔符、抹掉 `..`、去掉控制字符、限制长度。
     *
     * 为什么必须做：文件名最终会进 HTTP body 与日志。带着 `../` 的名字
     * 一旦被下游（服务端落盘、或本地写缓存）当成路径用，就是目录穿越；
     * 带着换行的名字会把日志排版搅烂，也容易被当成头注入。
     *
     * 行为：
     *  - 只取最后一段路径（`a/b/c.png` 与 `C:\x\c.png` 都压成 `c.png`）
     *  - 残留的 `..` 一律抹掉（`..a` 变 `a`，`a..b` 变 `ab`）
     *  - 去控制字符、去首尾空白
     *  - 清成空串或只剩点号时返回 "file"，绝不返回 "." / ".." / 空字符串
     *     （`.` 与 `..` 在文件系统里就是当前目录与上级目录，比空串更危险）
     *  - 超过 [MAX_NAME_LEN] 时截断，且**优先保留扩展名**，方便看日志认文件
     */
    fun sanitizeFileName(name: String): String {
        var s = name.substringAfterLast('/').substringAfterLast('\\')
        s = s.replace("..", "")
        s = s.filter { it.code >= 0x20 && it.code != 0x7F }
        s = s.trim()
        // 只剩点号（输入是 "..." 或 "." 这类）等同无效名字：
        // 处理顺序是先抹 ".." 再判空，所以 "..." 会先变成 "."，必须在这里拦截
        if (s.isEmpty() || s.all { it == '.' }) return "file"
        if (s.length <= MAX_NAME_LEN) return s
        val ext = extensionOf(s)
        if (ext.isEmpty()) return s.substring(0, MAX_NAME_LEN)
        val keep = MAX_NAME_LEN - ext.length - 1
        if (keep <= 0) return s.substring(0, MAX_NAME_LEN)
        return s.substring(0, keep) + "." + ext
    }

    /** 平铺的体积上限查询，便于单测直接校验边界 */
    fun limitOf(type: FileType): Long = when (type) {
        FileType.IMAGE -> IMAGE_MAX
        FileType.VIDEO -> VIDEO_MAX
        FileType.VOICE -> VOICE_MAX
        FileType.FILE -> FILE_MAX
    }

    /** 取小写扩展名；无扩展名或以点结尾时返回空串 */
    private fun extensionOf(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        if (dot < 0 || dot == base.length - 1) return ""
        return base.substring(dot + 1).lowercase(Locale.ROOT)
    }

    private fun labelOf(type: FileType): String = when (type) {
        FileType.IMAGE -> "图片"
        FileType.VIDEO -> "视频"
        FileType.VOICE -> "语音"
        FileType.FILE -> "文件"
    }

    private fun humanSize(bytes: Long): String =
        String.format(Locale.ROOT, "%.1fMB", bytes.toDouble() / (1024.0 * 1024.0))
}
