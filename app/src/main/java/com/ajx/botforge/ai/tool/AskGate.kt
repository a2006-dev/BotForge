package com.ajx.botforge.ai.tool

import java.security.SecureRandom

/**
 * ASK 档工具的「两阶段确认」闸门。
 *
 * ## 为什么不做阻塞等待
 *
 * 执行链是纯阻塞的多线程：BotManager 用固定 4 线程池，MessageHandler 到 Tool.run
 * 全程同步调用。如果 Tool.run 里停下来等用户点确认，一个待确认的调用就占死 4 个线程里的 1 个；
 * 几个 ASK 连着来，线程池立刻被占满，连用户用来确认的那条消息都排不进来，
 * 变成自己把自己锁死的僵局。所以这里的做法是「不挂起 + 二阶段」：
 * 第一次调用立刻失败并给出确认码，用户确认后，下一次调用才真正执行。
 *
 * ## 状态模型
 *
 *  - 待确认表：[request] 生成一次性确认码，TTL 60 秒；同一（包, 工具）只保留最新一个码。
 *  - 授权表：[confirm] 消费确认码，把（包, 工具）记为已授权；[isGranted] 读这张表。
 *  - 拒绝表：[deny] 消费确认码并撤销已有授权，同时留痕，方便排障时回答「用户是不是拒绝过」。
 *
 * 授权粒度是「某个包里的某个工具」：不同包的同名工具互相独立，同一个包里的不同工具也互相独立。
 *
 * ## 为什么时间源可注入
 *
 * TTL 是本类唯一依赖时间的逻辑。如果直接读系统时钟，测过期分支就得真 sleep 61 秒，
 * 单测会慢到没人愿意跑，等于没测。所以时间统一走 [useClock] 注入，测试里把假时钟拨快即可。
 *
 * ## 线程安全
 *
 * 所有状态在同一把私有锁内读写。request 的「作废旧码 + 计数 + 选码写入」、
 * confirm 的「校验 + 消费 + 授权」都是复合操作，只靠 ConcurrentHashMap 的单操作原子性不够。
 * 临界区内只有几次哈希查找，没有 IO，也不回调外部代码，因此不会长时间持锁、也不会死锁。
 */
object AskGate {

    /** 确认码有效期（毫秒）。用户手打 6 位码，一分钟够用，又不至于让码长期可用。 */
    const val TTL_MS: Long = 60_000L

    /** 确认码长度。6 位取自 32 字符表，空间约 1.07e9，邻码猜测的成本足够高。 */
    const val CODE_LENGTH: Int = 6

    /**
     * 确认码字母表：26 个大写字母 + 数字 2 到 7，恰好 32 个。
     *
     * 去掉 0、1、8、9 是为了抗手机端手打误读（O 与 0、I 与 1、B 与 8 容易混）。
     * 选 32 而不是 36，是因为 32 是 2 的幂，SecureRandom.nextInt(32) 天然无偏，
     * 不需要额外的拒绝采样，也就不会因为取模引入可预测的偏斜。
     */
    private const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * 一次待确认的请求。
     *
     * [attempts] 是同一（包, 工具）的累积请求次数，从 1 开始。
     * 模型反复试探时这个数字会涨，排障时能一眼看出「它是不是在死循环重试」。
     */
    data class Pending(
        val code: String,
        val pkgId: String,
        val toolName: String,
        val argsDigest: String,
        val createdAt: Long,
        val expiresAt: Long,
        val attempts: Int,
    ) {
        /** 距过期的剩余毫秒数。已过期返回 0，不会返回负数。 */
        fun remainingMs(now: Long): Long = (expiresAt - now).coerceAtLeast(0L)
    }

    /** 授权 / 待确认的最小粒度：包 + 工具。故意不含参数，确认的是「这个工具能跑」。 */
    private data class Key(val pkgId: String, val toolName: String)

    private val lock = Any()

    private val pendingByCode = HashMap<String, Pending>()
    private val codeByKey = HashMap<Key, String>()
    private val attemptsByKey = HashMap<Key, Int>()
    private val granted = HashSet<Key>()
    private val denied = HashSet<Key>()

    /** 懒初始化：SecureRandom 构造在部分设备上要读熵，别拖慢对象首次初始化。 */
    private val rng: SecureRandom by lazy { SecureRandom() }

    /** 时间源。默认系统时钟，测试 / 热重载可替换。 */
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    // ------------------------------------------------------------ 时钟

    /** 注入时间源（毫秒）。测试里传假时钟，就能毫秒级地覆盖过期分支。 */
    fun useClock(now: () -> Long) {
        clock = now
    }

    /** 恢复系统时钟。 */
    fun useSystemClock() {
        clock = { System.currentTimeMillis() }
    }

    /** 当前时间源读数（毫秒）。 */
    fun now(): Long = clock()

    // ------------------------------------------------------------ 阶段一：请求

    /**
     * 登记一次待确认请求，返回确认码。
     *
     * 同一（包, 工具）再次请求时，旧码会立即作废并只保留新码：
     * 否则用户手里可能同时握着好几个都有效的码，谁也说不清哪个才算数。
     * [argsDigest] 只用于审计展示，不参与授权判断（确认的是工具，不是某次参数）。
     */
    fun request(pkgId: String, toolName: String, argsDigest: String): String {
        synchronized(lock) {
            val t = clock()
            pruneExpired(t)
            val key = Key(pkgId, toolName)
            codeByKey[key]?.let { dropCode(it) }
            val attempts = (attemptsByKey[key] ?: 0) + 1
            attemptsByKey[key] = attempts
            val code = newCode(t)
            pendingByCode[code] = Pending(
                code = code,
                pkgId = pkgId,
                toolName = toolName,
                argsDigest = argsDigest,
                createdAt = t,
                expiresAt = t + TTL_MS,
                attempts = attempts,
            )
            codeByKey[key] = code
            return code
        }
    }

    /** 生成一个当前未占用、且不可预测的确认码。 */
    private fun newCode(t: Long): String {
        while (true) {
            val sb = StringBuilder(CODE_LENGTH)
            repeat(CODE_LENGTH) {
                sb.append(ALPHABET[rng.nextInt(ALPHABET.length)])
            }
            val code = sb.toString()
            val clash = pendingByCode[code]
            if (clash == null) return code
            if (clash.expiresAt <= t) {
                // 过期残留，清掉后可以安全复用这个码；仍有效的码绝不覆盖。
                dropCode(code)
                return code
            }
        }
    }

    // ------------------------------------------------------------ 阶段二：确认 / 拒绝

    /**
     * 消费确认码并授予（包, 工具）执行权。
     *
     * 命中且未过期返回 true；码不存在、已过期、已被消费都返回 false。
     * 一次性：先摘码再授予，任何并发路径都不可能让同一个码生效两次。
     * 输入会做归一化（去空格与全角空格、统一大写、忽略连字符），因为用户是手打。
     */
    fun confirm(code: String): Boolean {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return false
        synchronized(lock) {
            pruneExpired(clock())
            val p = pendingByCode[normalized] ?: return false
            dropCode(normalized)
            val key = Key(p.pkgId, p.toolName)
            granted.add(key)
            denied.remove(key)
            attemptsByKey.remove(key)
            return true
        }
    }

    /**
     * 消费确认码并拒绝。命中且未过期返回 true。
     *
     * 拒绝会顺带撤销已有的授权：用户明确说「不要」时，不能让旧授权继续生效。
     */
    fun deny(code: String): Boolean {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return false
        synchronized(lock) {
            pruneExpired(clock())
            val p = pendingByCode[normalized] ?: return false
            dropCode(normalized)
            val key = Key(p.pkgId, p.toolName)
            granted.remove(key)
            denied.add(key)
            attemptsByKey.remove(key)
            return true
        }
    }

    // ------------------------------------------------------------ 查询

    /** 该（包, 工具）是否已被确认授权。 */
    fun isGranted(pkgId: String, toolName: String): Boolean =
        synchronized(lock) { granted.contains(Key(pkgId, toolName)) }

    /** 该（包, 工具）是否被用户明确拒绝过。仅用于排障展示与提示，不参与放行判断。 */
    fun wasDenied(pkgId: String, toolName: String): Boolean =
        synchronized(lock) { denied.contains(Key(pkgId, toolName)) }

    /** 按码取待确认详情；码不存在或已过期返回 null。 */
    fun pending(code: String): Pending? {
        val normalized = normalize(code)
        if (normalized.isEmpty()) return null
        synchronized(lock) {
            pruneExpired(clock())
            return pendingByCode[normalized]
        }
    }

    /** 当前有效（未过期）的待确认条数。过期项会被先清理，所以不会虚高。 */
    fun pendingCount(): Int {
        synchronized(lock) {
            pruneExpired(clock())
            return pendingByCode.size
        }
    }

    // ------------------------------------------------------------ 维护

    /** 撤销单个（包, 工具）的授权，下次调用重新走确认。 */
    fun revoke(pkgId: String, toolName: String) {
        synchronized(lock) { granted.remove(Key(pkgId, toolName)) }
    }

    /** 清空全部状态并恢复系统时钟。仅供测试与热重载，正常流程不会调用。 */
    fun reset() {
        synchronized(lock) {
            pendingByCode.clear()
            codeByKey.clear()
            attemptsByKey.clear()
            granted.clear()
            denied.clear()
            clock = { System.currentTimeMillis() }
        }
    }

    private fun dropCode(code: String) {
        val p = pendingByCode.remove(code) ?: return
        val key = Key(p.pkgId, p.toolName)
        if (codeByKey[key] == code) codeByKey.remove(key)
    }

    private fun pruneExpired(t: Long) {
        if (pendingByCode.isEmpty()) return
        val stale = pendingByCode.values
            .filter { it.expiresAt <= t }
            .map { it.code }
        stale.forEach { dropCode(it) }
    }

    /** 宽进：去首尾空白、去半角与全角空格、去连字符，统一大写。 */
    private fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        input.forEach { c ->
            when (c) {
                ' ', '\u3000', '\t', '\n', '\r', '-' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().uppercase()
    }
}
