package com.ajx.botforge.ai

import com.ajx.botforge.data.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网搜索：解析 Bing HTML 的 b_algo 结果块
 * 思路来源：Operit various_search（链接清洗/去噪），但重写为纯服务端可解析版本
 */
object WebSearch {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    data class Item(val title: String, val url: String, val snippet: String)

    private val JUNK_HOST = listOf(
        "bing.com", "microsoft.com", "msn.com", "go.microsoft.com", "support.microsoft"
    )

    fun search(query: String, limit: Int = 5): List<Item> {
        val r = searchBing(query, limit)
        if (r.isNotEmpty()) return r
        Logger.w("Search", "Bing 无结果，改试搜狗")
        return searchSogou(query, limit)
    }

    private fun http(url: String, referer: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Logger.w("Search", "HTTP $code $url")
                return null
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
        } catch (e: Exception) {
            Logger.w("Search", "请求异常: ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ 清洗
    private val TAG_RE = Regex("<[^>]+>")
    private val CDATA_RE = Regex("//<!\\[CDATA\\[|//\\]\\]>")
    private val JS_OBJ_RE = Regex("window\\[\"[^\"]*\"\\]\\s*=\\s*\\{")
    private val URL_TAIL_RE = Regex("^\\s*[\\w.-]+\\.[a-z]{2,}\\s+https?://\\S+(\\s.*)?$")
    private val SPACE_RE = Regex("\\s+")

    private fun scrub(raw: String): String {
        var s = raw.replace(CDATA_RE, " ")
        s = TAG_RE.replace(s, " ")
        s = JS_OBJ_RE.replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(""", "\"").replace("&#39;", "'")
            .replace("&hellip;", "…").replace("&mdash;", "—")
        s = SPACE_RE.replace(s, " ").trim()
        if (URL_TAIL_RE.matches(s)) return ""
        return s.trim(' ', '.', '…', '›', '»', '-', '|')
    }

    private fun hostOf(url: String): String = try {
        URL(url).host.removePrefix("www.")
    } catch (e: Exception) {
        ""
    }

    private val LOW_SCORE = listOf(
        "zhidao.baidu.com", "wenwen.sogou.com", "iask.sina", "docin.com",
        "doc88.com", "taobao.com", "tmall.com", "jd.com", "douyin.com", "kuaishou.com"
    )
    private val HIGH_SCORE = listOf("gov.cn", "edu.cn", "baike.baidu", "wikipedia", "zhihu.com")

    private fun rank(items: List<Item>): List<Item> {
        return items.map { it to score(it) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(i: Item): Int {
        var s = i.snippet.length.coerceAtMost(200) / 10
        val h = hostOf(i.url)
        if (HIGH_SCORE.any { h.contains(it) }) s += 12
        if (LOW_SCORE.any { h.contains(it) }) s -= 15
        if (i.title.length < 6) s -= 5
        if (i.snippet.length < 20) s -= 8
        return s
    }

    // ------------------------------------------------------------ Bing
    private fun searchBing(query: String, limit: Int): List<Item> {
        val url = "https://cn.bing.com/search?q=${enc(query)}&ensearch=0&setlang=zh-CN"
        val html = http(url) ?: return emptyList()
        val blocks = html.split(Regex("<li[^>]*class=\"[^\"]*b_algo[^\"]*\""))
        if (blocks.size <= 1) return emptyList()
        val out = mutableListOf<Item>()
        for (i in 1 until blocks.size) {
            val b = blocks[i]
            val h2 = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val title = scrub(h2.groupValues[1])
            if (title.length < 4) continue
            val hrefs = Regex("href=\"(https?://[^\"]+)\"").findAll(b).map { it.groupValues[1] }
                .filter { !JUNK_HOST.any { h -> hostOf(it).contains(h) } }
                .toList()
            val link = hrefs.firstOrNull() ?: continue
            val body = b.substring(h2.range.last + 1)
            val snip = scrub(body.take(600))
            if (snip.length < 10) continue
            out.add(Item(title, link, snip.take(300)))
            if (out.size >= limit * 2) break
        }
        return rank(out).take(limit)
    }

    // ------------------------------------------------------------ 搜狗兜底
    private fun searchSogou(query: String, limit: Int): List<Item> {
        val url = "https://www.sogou.com/web?query=${enc(query)}"
        val html = http(url, "https://www.sogou.com/") ?: return emptyList()
        val blocks = html.split("class=\"vrwrap\"")
        if (blocks.size <= 1) return emptyList()
        val out = mutableListOf<Item>()
        for (i in 1 until blocks.size) {
            val b = blocks[i]
            val h3 = Regex("<h3[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            val title = scrub(h3.groupValues[1])
            if (title.length < 4) continue
            val link = Regex("href=\"(/link\\?url=[^\"]+|https?://[^\"]+)\"").find(b)
                ?.groupValues?.get(1) ?: continue
            val fullLink = if (link.startsWith("/link")) "https://www.sogou.com$link" else link
            val body = b.substring(h3.range.last + 1)
            val snip = scrub(body.take(600))
            if (snip.length < 10) continue
            out.add(Item(title, fullLink, snip.take(300)))
            if (out.size >= limit * 2) break
        }
        return rank(out).take(limit)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 拼成给模型看的 prompt 片段 */
    fun toPrompt(query: String, items: List<Item>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder("【实时搜索结果】关键词：").append(query).append('\n')
        items.forEachIndexed { i, it ->
            sb.append(i + 1).append(". ").append(it.title)
            sb.append(" —— ").append(it.snippet)
            sb.append("（").append(hostOf(it.url)).append("）\n")
        }
        sb.append("（以上为刚刚联网检索到的真实结果，请严格据此回答；结果里没有的不许编造。）")
        return sb.toString()
    }
}