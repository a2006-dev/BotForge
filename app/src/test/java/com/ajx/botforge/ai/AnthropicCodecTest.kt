package com.ajx.botforge.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 协议编解码单测。
 *
 * ## 为什么这些测试不 import org.json
 *
 * Android 的 JVM 单测里 `org.json` 是 not mocked（只有桩，调用即抛
 * RuntimeException），一旦测试碰到它，CI 直接红。所以本组测试只碰
 * [AnthropicCodec] 暴露的普通 Kotlin 结构，断言也只看 Map / List 的形状。
 *
 * 唯一例外是 `Tool.toAnthropicSchema()` —— 它返回 `JSONObject`，不构造
 * `JSONObject` 就没法调用。这里沿用仓库已有的做法（term/SourceGuardTest、
 * AgentLoopTailGuardTest）：对源码做文本断言，确认它确实产出了 `input_schema`
 * 且没有 `parameters` / `type:function` 这类 OpenAI 残留。
 *
 * 局限说清楚：源码断言只能证明「实现里写的是这些键」，不能证明「运行时 JSON
 * 字符串长这样」。真正的运行时验证要靠编译期类型 + 真机联调。
 */
class AnthropicCodecTest {

    // ------------------------------------------------------------------ 工具

    private fun readSource(rel: String): String {
        val candidates = listOf(
            File("src/main/java/com/ajx/botforge/$rel"),
            File("app/src/main/java/com/ajx/botforge/$rel")
        )
        val f = candidates.firstOrNull { it.exists() }
        assertNotNull("找不到源文件 $rel，尝试过：${candidates.joinToString { it.absolutePath }}", f)
        return f!!.readText()
    }

    private val demoSpec = AnthropicCodec.ToolSpec(
        name = "get_weather",
        description = "查询天气",
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("city" to mapOf("type" to "string")),
            "required" to listOf("city")
        )
    )

    // ------------------------------------------------------------ 1. schema

    @Test
    fun `工具 schema 使用 input_schema 而不是 parameters`() {
        val schema = AnthropicCodec.toolSchema(demoSpec)

        assertEquals("get_weather", schema["name"])
        assertEquals("查询天气", schema["description"])
        assertTrue("必须有 input_schema 键", schema.containsKey("input_schema"))
        assertFalse("不得出现 OpenAI 的 parameters 键", schema.containsKey("parameters"))
        assertFalse("Anthropic 外层没有 type:function 包裹", schema.containsKey("type"))
        assertFalse("Anthropic 没有 function 子对象", schema.containsKey("function"))

        @Suppress("UNCHECKED_CAST")
        val input = schema["input_schema"] as Map<String, Any?>
        assertEquals("object", input["type"])
    }

    @Test
    fun `Tool 源码里的 toAnthropicSchema 产出 input_schema 且无 OpenAI 残留`() {
        val src = readSource("ai/tool/Tool.kt")

        // 新增的函数存在
        assertTrue(
            "Tool.kt 应新增 toAnthropicSchema()",
            src.contains("fun Tool.toAnthropicSchema()")
        )
        // 取该函数那段文本（到下一个顶层声明之前）
        val start = src.indexOf("fun Tool.toAnthropicSchema()")
        val rest = src.substring(start)
        val end = rest.indexOf("\nfun ")
        val body = if (end < 0) rest else rest.substring(0, end)

        assertTrue("必须写 input_schema", body.contains("input_schema"))
        assertFalse("不得写 parameters", body.contains("\"parameters\""))
        assertFalse("不得写 type:function 包裹", body.contains("\"function\""))

        // 原有 OpenAI 版本必须原样保留，不许被顺手改掉
        assertTrue(
            "toOpenAiSchema 必须保留原样",
            src.contains("fun Tool.toOpenAiSchema(): JSONObject")
        )
        assertTrue(
            "toOpenAiSchema 仍应写 parameters",
            src.contains("\"parameters\"")
        )
    }

    // ------------------------------------------------------------ 2. 请求体

    @Test
    fun `tools 为空时不发送 tools 与 tool_choice 键`() {
        val body = AnthropicCodec.buildRequestBody(
            model = "claude-3-5-sonnet",
            system = "你是助手",
            history = emptyList(),
            user = "你好",
            temperature = 0.7,
            maxTokens = 400,
            tools = emptyList()
        )

        assertFalse("空工具时不得出现 tools 键", body.containsKey("tools"))
        assertFalse("空工具时不得出现 tool_choice 键", body.containsKey("tool_choice"))
        assertEquals("claude-3-5-sonnet", body["model"])
        assertEquals(400, body["max_tokens"])
        assertEquals(0.7, (body["temperature"] as Double), 0.0001)
    }

    @Test
    fun `tools 非空时 tool_choice 是对象而不是字符串`() {
        val body = AnthropicCodec.buildRequestBody(
            model = "claude-3-5-sonnet",
            system = "你是助手",
            history = emptyList(),
            user = "北京天气",
            temperature = 0.7,
            maxTokens = 400,
            tools = listOf(demoSpec)
        )

        @Suppress("UNCHECKED_CAST")
        val tools = body["tools"] as List<Any?>
        assertEquals(1, tools.size)

        @Suppress("UNCHECKED_CAST")
        val first = tools[0] as Map<String, Any?>
        assertTrue(first.containsKey("input_schema"))
        assertFalse(first.containsKey("parameters"))

        val choice = body["tool_choice"]
        assertTrue("tool_choice 必须是对象，不能是裸字符串", choice is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val choiceMap = choice as Map<String, Any?>
        assertEquals("auto", choiceMap["type"])
    }

    @Test
    fun `system 为空时不发送 system 键`() {
        val body = AnthropicCodec.buildRequestBody(
            model = "m",
            system = "   ",
            history = emptyList(),
            user = "hi",
            temperature = 0.0,
            maxTokens = 16
        )
        assertFalse(body.containsKey("system"))
    }

    @Test
    fun `system 不进 messages 且历史里只允许 user assistant`() {
        val body = AnthropicCodec.buildRequestBody(
            model = "m",
            system = "系统提示",
            history = listOf(
                AnthropicCodec.HistoryTurn("system", "越权历史"),
                AnthropicCodec.HistoryTurn("assistant", "上一轮回答"),
                AnthropicCodec.HistoryTurn("user", "上一轮提问")
            ),
            user = "本轮提问",
            temperature = 0.0,
            maxTokens = 16
        )

        assertEquals("系统提示", body["system"])

        @Suppress("UNCHECKED_CAST")
        val msgs = body["messages"] as List<Any?>
        assertEquals(4, msgs.size)
        msgs.forEach { raw ->
            @Suppress("UNCHECKED_CAST")
            val m = raw as Map<String, Any?>
            val role = m["role"]
            assertTrue("role 只能是 user 或 assistant，实际 $role", role == "user" || role == "assistant")
        }

        @Suppress("UNCHECKED_CAST")
        val first = msgs[0] as Map<String, Any?>
        assertEquals("system 历史应被降级为 user", "user", first["role"])

        @Suppress("UNCHECKED_CAST")
        val last = msgs[3] as Map<String, Any?>
        assertEquals("user", last["role"])
        @Suppress("UNCHECKED_CAST")
        val lastBlocks = last["content"] as List<Any?>
        assertEquals(1, lastBlocks.size)
        @Suppress("UNCHECKED_CAST")
        val lastText = lastBlocks[0] as Map<String, Any?>
        assertEquals("text", lastText["type"])
        assertEquals("本轮提问", lastText["text"])
    }

    @Test
    fun `user content 一律是块数组 文字在首个 text 块`() {
        val plain = AnthropicCodec.buildRequestBody(
            model = "m", system = "", history = emptyList(), user = "看这个",
            temperature = 0.0, maxTokens = 16
        )
        @Suppress("UNCHECKED_CAST")
        val plainMsgs = plain["messages"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val plainLast = plainMsgs.last() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val plainBlocks = plainLast["content"] as List<Any?>
        assertEquals("无图时也是块数组（与改造前行为一致）", 1, plainBlocks.size)
        @Suppress("UNCHECKED_CAST")
        val plainText = plainBlocks[0] as Map<String, Any?>
        assertEquals("text", plainText["type"])
        assertEquals("看这个", plainText["text"])

        val img = AnthropicCodec.imageFromDataUrl("data:image/png;base64,QUJD")
        assertNotNull(img)
        assertEquals("image/png", img!!.mediaType)
        assertEquals("QUJD", img.data)

        val withImage = AnthropicCodec.buildRequestBody(
            model = "m", system = "", history = emptyList(), user = "看这个",
            temperature = 0.0, maxTokens = 16, images = listOf(img)
        )
        @Suppress("UNCHECKED_CAST")
        val imgMsgs = withImage["messages"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val imgLast = imgMsgs.last() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val blocks = imgLast["content"] as List<Any?>
        assertEquals(2, blocks.size)

        @Suppress("UNCHECKED_CAST")
        val textBlock = blocks[0] as Map<String, Any?>
        assertEquals("text", textBlock["type"])

        @Suppress("UNCHECKED_CAST")
        val imageBlock = blocks[1] as Map<String, Any?>
        assertEquals("image", imageBlock["type"])
        @Suppress("UNCHECKED_CAST")
        val source = imageBlock["source"] as Map<String, Any?>
        assertEquals("base64", source["type"])
        assertEquals("image/png", source["media_type"])
        assertEquals("QUJD", source["data"])
    }

    @Test
    fun `有图但用户没写文字时用兜底提示`() {
        val img = AnthropicCodec.imageFromDataUrl("data:image/jpeg;base64,QQ==")
        val body = AnthropicCodec.buildRequestBody(
            model = "m", system = "", history = emptyList(), user = "   ",
            temperature = 0.0, maxTokens = 16, images = listOf(img!!)
        )
        @Suppress("UNCHECKED_CAST")
        val msgs = body["messages"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val last = msgs.last() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val blocks = last["content"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val textBlock = blocks[0] as Map<String, Any?>
        assertEquals(AnthropicCodec.DEFAULT_IMAGE_USER_TEXT, textBlock["text"])
    }

    @Test
    fun `非法 data URL 返回 null`() {
        assertNull(AnthropicCodec.imageFromDataUrl("https://example.com/a.png"))
        assertNull(AnthropicCodec.imageFromDataUrl("data:image/png;base64,"))
    }

    // ------------------------------------------------------------ 3. 响应解析

    private fun textBlock(t: String): Map<String, Any?> =
        mapOf("type" to "text", "text" to t)

    private fun toolUseBlock(id: String, name: String, input: Any?): Map<String, Any?> =
        mapOf("type" to "tool_use", "id" to id, "name" to name, "input" to input)

    @Test
    fun `纯 text 块解析为无工具调用`() {
        val reply = AnthropicCodec.parseResponse(
            mapOf(
                "id" to "msg_1",
                "model" to "claude-3-5-sonnet",
                "stop_reason" to "end_turn",
                "content" to listOf(textBlock("你好，"), textBlock("有什么可以帮你？"))
            )
        )

        assertNotNull(reply)
        assertEquals("msg_1", reply!!.id)
        assertEquals("end_turn", reply.stopReason)
        assertEquals("你好，有什么可以帮你？", reply.text)
        assertTrue(reply.toolUses.isEmpty())
        assertFalse(reply.isToolUse)
        assertFalse(reply.isEmpty)
    }

    @Test
    fun `tool_use 块解析出 id name 与 input 对象`() {
        val reply = AnthropicCodec.parseResponse(
            mapOf(
                "id" to "msg_2",
                "stop_reason" to "tool_use",
                "content" to listOf(
                    toolUseBlock("toolu_01", "get_weather", mapOf("city" to "北京"))
                )
            )
        )

        assertNotNull(reply)
        val uses = reply!!.toolUses
        assertEquals(1, uses.size)
        assertEquals("toolu_01", uses[0].id)
        assertEquals("get_weather", uses[0].name)
        assertEquals("北京", uses[0].input["city"])
        assertTrue("官方 input 是对象", uses[0].inputIsObject)
        assertEquals("", reply.text)
        assertTrue("只有工具调用时 text 为空但整体不算空", !reply.isEmpty)
    }

    @Test
    fun `text 与 tool_use 混合时两者都保留`() {
        val reply = AnthropicCodec.parseResponse(
            mapOf(
                "stop_reason" to "tool_use",
                "content" to listOf(
                    textBlock("我先查一下天气。"),
                    toolUseBlock("toolu_02", "get_weather", mapOf("city" to "上海"))
                )
            )
        )

        assertNotNull(reply)
        assertEquals("我先查一下天气。", reply!!.text)
        assertEquals(1, reply.toolUses.size)
        assertEquals("上海", reply.toolUses[0].input["city"])
    }

    @Test
    fun `stop_reason 为 tool_use 时被识别为工具调用`() {
        val toolReply = AnthropicCodec.parseResponse(
            mapOf("stop_reason" to "tool_use", "content" to listOf(toolUseBlock("t", "n", emptyMap<String, Any?>())))
        )
        assertTrue(toolReply!!.isToolUse)
        assertEquals(AnthropicCodec.STOP_TOOL_USE, toolReply.stopReason)

        val plainReply = AnthropicCodec.parseResponse(
            mapOf("stop_reason" to "end_turn", "content" to listOf(textBlock("x")))
        )
        assertFalse(plainReply!!.isToolUse)
    }

    @Test
    fun `input 被写成字符串时标记 inputIsObject 为 false`() {
        val reply = AnthropicCodec.parseResponse(
            mapOf(
                "stop_reason" to "tool_use",
                "content" to listOf(toolUseBlock("toolu_03", "get_weather", "{\"city\":\"深圳\"}"))
            )
        )

        val use = reply!!.toolUses[0]
        assertEquals("get_weather", use.name)
        assertFalse("字符串形式的 input 必须被标记出来，不能被当成正常对象", use.inputIsObject)
        assertTrue("无法解析成对象时给空参数，由调用方决定报错", use.input.isEmpty())
    }

    @Test
    fun `未知块类型被记录且不影响文本解析`() {
        val reply = AnthropicCodec.parseResponse(
            mapOf(
                "stop_reason" to "end_turn",
                "content" to listOf(
                    textBlock("正文"),
                    mapOf("type" to "thinking", "thinking" to "内部推理")
                )
            )
        )

        assertEquals("正文", reply!!.text)
        assertEquals(listOf("thinking"), reply.unknownTypes)
    }

    @Test
    fun `响应为 null 时返回 null`() {
        assertNull(AnthropicCodec.parseResponse(null))
    }

    // ------------------------------------------------------- 4. 回填结构

    @Test
    fun `tool_result 回填成 user 消息里的块 不是 role tool`() {
        val turn = AnthropicCodec.toolResultTurn(
            listOf(AnthropicCodec.ToolResultBlock("toolu_01", "晴，26 度"))
        )

        assertEquals("必须是 user 角色", "user", turn["role"])
        assertFalse("Anthropic 没有 role:tool", turn.containsKey("tool_call_id"))

        @Suppress("UNCHECKED_CAST")
        val blocks = turn["content"] as List<Any?>
        assertEquals(1, blocks.size)

        @Suppress("UNCHECKED_CAST")
        val block = blocks[0] as Map<String, Any?>
        assertEquals("tool_result", block["type"])
        assertEquals("toolu_01", block["tool_use_id"])
        assertEquals("晴，26 度", block["content"])
        assertFalse("成功结果不带 is_error", block.containsKey("is_error"))
    }

    @Test
    fun `失败的 tool_result 带 is_error 标记`() {
        val turn = AnthropicCodec.toolResultTurn(
            listOf(AnthropicCodec.ToolResultBlock("toolu_09", "【执行失败】超时", isError = true))
        )

        @Suppress("UNCHECKED_CAST")
        val blocks = turn["content"] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val block = blocks[0] as Map<String, Any?>
        assertEquals(true, block["is_error"])
    }

    @Test
    fun `assistant 轮次原样带回 tool_use 块`() {
        val uses = listOf(
            AnthropicCodec.ToolUseBlock("toolu_01", "get_weather", mapOf("city" to "北京"))
        )
        val turn = AnthropicCodec.assistantTurn(uses, "我查一下")

        assertEquals("assistant", turn["role"])

        @Suppress("UNCHECKED_CAST")
        val blocks = turn["content"] as List<Any?>
        assertEquals(2, blocks.size)

        @Suppress("UNCHECKED_CAST")
        val textBlock = blocks[0] as Map<String, Any?>
        assertEquals("text", textBlock["type"])

        @Suppress("UNCHECKED_CAST")
        val useBlock = blocks[1] as Map<String, Any?>
        assertEquals("tool_use", useBlock["type"])
        assertEquals("toolu_01", useBlock["id"])
        assertEquals("get_weather", useBlock["name"])
        @Suppress("UNCHECKED_CAST")
        val input = useBlock["input"] as Map<String, Any?>
        assertEquals("北京", input["city"])
    }

    @Test
    fun `assistant 空文本时不塞空 text 块`() {
        val turn = AnthropicCodec.assistantTurn(
            listOf(AnthropicCodec.ToolUseBlock("t1", "n1", emptyMap<String, Any?>()))
        )
        @Suppress("UNCHECKED_CAST")
        val blocks = turn["content"] as List<Any?>
        assertEquals(1, blocks.size)
        @Suppress("UNCHECKED_CAST")
        val only = blocks[0] as Map<String, Any?>
        assertEquals("tool_use", only["type"])
    }

    // ------------------------------------------------------- 5. 依赖守卫

    @Test
    fun `AnthropicCodec 不得依赖 android 或 org json`() {
        val src = readSource("ai/AnthropicCodec.kt")

        assertFalse("不得 import android", src.contains("import android."))
        assertFalse("不得 import androidx", src.contains("import androidx."))
        assertFalse("不得 import org.json", src.contains("import org.json"))
        assertFalse("不得直接做网络请求", src.contains("HttpURLConnection"))
        assertTrue("必须存在", src.contains("object AnthropicCodec"))
    }
}
