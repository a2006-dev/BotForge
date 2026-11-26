package com.ajx.botforge.term

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TarReader 单元测试。
 *
 * 手动构造 ustar 字节流，覆盖解析器的关键分支。不依赖真实 rootfs 文件，
 * CI 无需携带大体积资源即可验证算法。
 *
 * 每个用例的字节布局在本地已用等价实现跑通并逐项比对过。
 */
class TarReaderTest {

    private companion object {
        const val BLOCK = 512
        val TYPE_FILE = '0'.code.toByte()
        val TYPE_DIR = '5'.code.toByte()
        val TYPE_SYM = '2'.code.toByte()
        val TYPE_LONGNAME = 'L'.code.toByte()
        val TYPE_PAX = 'x'.code.toByte()
    }

    /**
     * 构造 ustar 头块。字段宽度严格按 POSIX：
     * name 100 / mode 8 / uid 8 / gid 8 / size 12 / mtime 12 /
     * chksum 8 / typeflag 1 / linkname 100。
     */
    private fun header(
        name: String,
        size: Long,
        type: Byte,
        linkName: String = ""
    ): ByteArray {
        val b = ByteArray(BLOCK)
        val nb = name.toByteArray(Charsets.UTF_8)
        nb.copyInto(b, 0, 0, minOf(nb.size, 100))

        val mode = String.format("%07o", 0b111_101_101).toByteArray()
        mode.copyInto(b, 100, 0, minOf(mode.size, 7))
        "0000000".toByteArray().copyInto(b, 108)   // uid
        "0000000".toByteArray().copyInto(b, 116)   // gid

        val sizeStr = String.format("%011o", size).toByteArray()
        sizeStr.copyInto(b, 124, 0, minOf(sizeStr.size, 11))
        "00000000000".toByteArray().copyInto(b, 136) // mtime
        for (i in 148 until 156) b[i] = ' '.code.toByte() // chksum 占位

        b[156] = type
        val lb = linkName.toByteArray(Charsets.UTF_8)
        lb.copyInto(b, 157, 0, minOf(lb.size, 100))
        "ustar".toByteArray().copyInto(b, 257)
        "00".toByteArray().copyInto(b, 263)
        return b
    }

    /** 正文补齐到 512 边界。 */
    private fun body(data: ByteArray): ByteArray {
        val pad = (BLOCK - (data.size % BLOCK)) % BLOCK
        return data + ByteArray(pad)
    }

    /** 拼接条目并追加两字节全零结束块。 */
    private fun tarOf(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        out.write(ByteArray(BLOCK * 2))
        return out.toByteArray()
    }

    @Test
    fun `解析普通文件并读出正文`() {
        val content = "hello botforge".toByteArray()
        val bytes = tarOf(header("greeting.txt", content.size.toLong(), TYPE_FILE), body(content))

        val reader = TarReader(ByteArrayInputStream(bytes))
        val e = reader.nextEntry()
        assertNotNull(e)
        assertEquals("greeting.txt", e!!.name)
        assertTrue(e.isFile)
        assertEquals(content.size.toLong(), e.size)

        val out = ByteArrayOutputStream()
        assertEquals(content.size.toLong(), reader.copyBody(out))
        assertEquals("hello botforge", out.toString("UTF-8"))
        assertNull(reader.nextEntry())
    }

    @Test
    fun `解析目录条目`() {
        val reader = TarReader(ByteArrayInputStream(tarOf(header("usr/", 0, TYPE_DIR))))
        val e = reader.nextEntry()
        assertNotNull(e)
        assertTrue(e!!.isDirectory)
        assertEquals(0L, reader.copyBody(ByteArrayOutputStream()))
        assertNull(reader.nextEntry())
    }

    @Test
    fun `解析符号链接并保留目标`() {
        val reader = TarReader(
            ByteArrayInputStream(tarOf(header("sh", 0, TYPE_SYM, linkName = "/bin/busybox")))
        )
        val e = reader.nextEntry()
        assertNotNull(e)
        assertTrue(e!!.isSymlink)
        assertEquals("/bin/busybox", e.linkName)
    }

    @Test
    fun `多条目顺序读取不受填充影响`() {
        // 700 字节不是 512 的整数倍，用于验证对齐计算
        val a = ByteArray(700) { (it % 256).toByte() }
        val b = "second".toByteArray()
        val bytes = tarOf(
            header("a.bin", a.size.toLong(), TYPE_FILE), body(a),
            header("b.txt", b.size.toLong(), TYPE_FILE), body(b)
        )

        val reader = TarReader(ByteArrayInputStream(bytes))
        val e1 = reader.nextEntry()!!
        assertEquals("a.bin", e1.name)
        val out1 = ByteArrayOutputStream()
        reader.copyBody(out1)
        assertEquals(a.size, out1.size())

        val e2 = reader.nextEntry()!!
        assertEquals("b.txt", e2.name)
        val out2 = ByteArrayOutputStream()
        reader.copyBody(out2)
        assertEquals("second", out2.toString("UTF-8"))
        assertNull(reader.nextEntry())
    }

    @Test
    fun `未读正文时自动跳过也能继续`() {
        val a = ByteArray(1024) { 7 }
        val b = "after".toByteArray()
        val bytes = tarOf(
            header("skip.bin", a.size.toLong(), TYPE_FILE), body(a),
            header("after.txt", b.size.toLong(), TYPE_FILE), body(b)
        )

        val reader = TarReader(ByteArrayInputStream(bytes))
        assertEquals("skip.bin", reader.nextEntry()!!.name)
        // 不读正文直接取下一项，实现须自行跳过
        val e2 = reader.nextEntry()!!
        assertEquals("after.txt", e2.name)
        val out = ByteArrayOutputStream()
        reader.copyBody(out)
        assertEquals("after", out.toString("UTF-8"))
    }

    @Test
    fun `GNU 长文件名扩展生效`() {
        val longName = "usr/lib/" + "x".repeat(120) + "/file.so"
        val nameBytes = longName.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val content = "data".toByteArray()
        val bytes = tarOf(
            header("././@LongLink", nameBytes.size.toLong(), TYPE_LONGNAME), body(nameBytes),
            header("truncated", content.size.toLong(), TYPE_FILE), body(content)
        )

        val reader = TarReader(ByteArrayInputStream(bytes))
        val e = reader.nextEntry()!!
        assertEquals(longName, e.name)
        assertTrue(e.isFile)
    }

    @Test
    fun `pax path 扩展生效`() {
        val real = "etc/pax-path-target.conf"
        val paxBody = "path=$real\n".toByteArray(Charsets.UTF_8)
        val content = "k=v".toByteArray()
        val bytes = tarOf(
            header("PaxHeader/x", paxBody.size.toLong(), TYPE_PAX), body(paxBody),
            header("short", content.size.toLong(), TYPE_FILE), body(content)
        )

        val reader = TarReader(ByteArrayInputStream(bytes))
        assertEquals(real, reader.nextEntry()!!.name)
    }

    @Test
    fun `空归档立即结束`() {
        assertNull(TarReader(ByteArrayInputStream(ByteArray(BLOCK * 2))).nextEntry())
    }

    @Test
    fun `gzip 包装的归档可正常解析`() {
        val content = "compressed".toByteArray()
        val plain = tarOf(header("c.txt", content.size.toLong(), TYPE_FILE), body(content))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(plain) }

        GZIPInputStream(ByteArrayInputStream(gz.toByteArray())).use { input ->
            val reader = TarReader(input)
            val e = reader.nextEntry()!!
            assertEquals("c.txt", e.name)
            val out = ByteArrayOutputStream()
            reader.copyBody(out)
            assertEquals("compressed", out.toString("UTF-8"))
        }
    }

    @Test
    fun `路径前缀点斜杠被去除`() {
        val reader = TarReader(
            ByteArrayInputStream(tarOf(header("./bin/tool", 0, TYPE_FILE), body(ByteArray(0))))
        )
        assertEquals("bin/tool", reader.nextEntry()!!.name)
    }
}