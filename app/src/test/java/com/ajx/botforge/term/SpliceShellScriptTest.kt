package com.ajx.botforge.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpliceShellScriptTest {

    private val bash = "/data/data/com.termux/files/usr/bin/bash"

    @Test
    fun `只保留一个 bash，且紧跟 -c`() {
        val base = listOf("proot", "-r", "/rootfs", "-w", "/home", bash, "-c", "OLD")
        val out = TerminalEnvironment.spliceShellScript(base, bash, "NEW")
        assertEquals(listOf("proot", "-r", "/rootfs", "-w", "/home", bash, "-c", "NEW"), out)
        assertEquals("bash 不能出现两次，否则第二个会被当成脚本文件", 1, out.count { it == bash })
    }

    @Test
    fun `末尾结构不符时直接报错而不是悄悄拼出坏 argv`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalEnvironment.spliceShellScript(listOf("proot", "-c", "x"), bash, "NEW")
        }
    }
}
