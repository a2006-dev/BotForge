package com.ajx.botforge.data

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 主人 / 管理员授权体系（B 方案：主人 + 管理员两级）。
 *
 * 设计参考（成熟项目）：
 *  - 云崽 / Yunzai：`#设置主人` → 机器人回随机验证码 → 用户原样回复 → 完成绑定。
 *    验证码每个「Bot 账号 + 用户」组合独立、只对发起者有效、大小写不敏感。
 *  - NoneBot2：`SUPERUSERS` 配置全局超管，权限链
 *    SUPERUSER > GROUP_ADMIN > GROUP_OWNER > PRIVATE > GROUP。
 *  - 腾讯 XQ：主人 = 全局最高权限；管理员受群号限制。
 *
 * 本项目落地：
 *  1. 主人唯一。APP 内生成 8 位一次性验证码（5 分钟有效），
 *     群里/私聊发 `/绑定主人 验证码` 完成绑定。
 *  2. 管理员由主人授权，可批量增删，继承除「撤销/转移主人」外的全部权限。
 *  3. 判定 key 为「场景 + openid」：
 *     - 私聊：user_openid
 *     - 群聊：group_openid + user_openid（同一用户跨群 openid 不同，无法全局唯一，
 *       所以群内管理员必须按群授权，不能全局套用 —— 与 NoneBot 的 GROUP_ADMIN 语义一致）
 */
object AuthStore {

    private const val KEY_OWNER = "auth_owner_openid"
    private const val KEY_OWNER_NAME = "auth_owner_name"
    private const val KEY_OWNER_AT = "auth_owner_at"
    private const val KEY_ADMINS = "auth_admins"
    private const val KEY_CODE = "auth_pending_code"
    private const val KEY_CODE_AT = "auth_pending_code_at"
    private const val KEY_CODE_BOT = "auth_pending_code_bot"

    /** 验证码有效期：5 分钟。太长容易被泄露，太短用户来不及复制。 */
    const val CODE_TTL_MS = 5 * 60 * 1000L

    /**
     * 验证码字符集。
     * 去掉了 0/O、1/I/L 这些肉眼难分的字符 —— 用户要在手机 QQ 里手抄验证码，
     * 云崽那类项目也是同样的做法（验证码不区分大小写）。
     */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private lateinit var sp: SharedPreferences
    private val rnd = SecureRandom()

    fun init(prefs: SharedPreferences) {
        sp = prefs
    }

    // ---------------------------------------------------------------- 身份
    /** 当前主人 openid，未绑定返回空串 */
    val ownerOpenId: String
        get() = sp.getString(KEY_OWNER, "").orEmpty()

    val ownerName: String
        get() = sp.getString(KEY_OWNER_NAME, "").orEmpty()

    /** 绑定时间（毫秒），0 表示未绑定 */
    val ownerBoundAt: Long
        get() = sp.getLong(KEY_OWNER_AT, 0L)

    val hasOwner: Boolean get() = ownerOpenId.isNotEmpty()

    /** 管理员列表，元素格式 `openid|来源场景`，来源场景为空表示全局管理员 */
    fun admins(): List<Admin> {
        val raw = sp.getString(KEY_ADMINS, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.isEmpty() || parts[0].isBlank()) null
            else Admin(
                openId = parts[0],
                scope = parts.getOrNull(1).orEmpty(),
                note = parts.getOrNull(2).orEmpty(),
                addedAt = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            )
        }
    }

    data class Admin(
        val openId: String,
        /** 空 = 全局；否则为限定群 openid */
        val scope: String,
        val note: String = "",
        val addedAt: Long = 0L
    ) {
        val isGlobal: Boolean get() = scope.isEmpty()

        fun describe(): String = when {
            isGlobal -> "全局管理员"
            else -> "群管理员（限群 ${scope.take(8)}…）"
        }
    }

    private fun writeAdmins(list: List<Admin>) {
        val text = list.joinToString("\n") { a ->
            listOf(a.openId, a.scope, a.note.replace("\n", " "), a.addedAt.toString())
                .joinToString("|")
        }
        sp.edit().putString(KEY_ADMINS, text).apply()
    }

    fun addAdmin(admin: Admin): Result<Unit> {
        if (admin.openId.isBlank()) return Result.failure(IllegalArgumentException("openid 不能为空"))
        if (admin.openId == ownerOpenId) return Result.failure(IllegalArgumentException("该用户已是主人"))
        val list = admins().toMutableList()
        if (list.any { it.openId == admin.openId && it.scope == admin.scope }) {
            return Result.failure(IllegalArgumentException("该管理员已存在"))
        }
        list.add(admin.copy(addedAt = System.currentTimeMillis()))
        writeAdmins(list)
        return Result.success(Unit)
    }

    fun removeAdmin(openId: String, scope: String): Boolean {
        val list = admins().toMutableList()
        val ok = list.removeAll { it.openId == openId && it.scope == scope }
        if (ok) writeAdmins(list)
        return ok
    }

    fun clearAdmins() {
        sp.edit().remove(KEY_ADMINS).apply()
    }

    // ---------------------------------------------------------------- 角色判定
    /** 角色等级。数值越大权限越高，便于比较。 */
    enum class Role(val label: String, val level: Int) {
        USER("普通用户", 0),
        ADMIN("管理员", 1),
        OWNER("主人", 2)
    }

    /**
     * 判定某人在某个场景下的角色。
     *
     * @param userOpenId 事件里的用户 openid
     * @param groupOpenId 事件里的群 openid，单聊传空
     */
    fun roleOf(userOpenId: String, groupOpenId: String): Role {
        if (userOpenId.isBlank()) return Role.USER
        if (userOpenId == ownerOpenId) return Role.OWNER
        val list = admins()
        // 全局管理员在任何场景都生效；限定群的管理员只在该群生效
        val hit = list.any { a ->
            a.openId == userOpenId && (a.isGlobal || (groupOpenId.isNotEmpty() && a.scope == groupOpenId))
        }
        return if (hit) Role.ADMIN else Role.USER
    }

    /** 是否具备主人级权限（主人 or 管理员） */
    fun isPrivileged(userOpenId: String, groupOpenId: String): Boolean =
        roleOf(userOpenId, groupOpenId).level >= Role.ADMIN.level

    /** 是否可以执行「只有主人能做的事」（撤销主人、转移主人、管理管理员） */
    fun isOwnerOnly(userOpenId: String): Boolean =
        userOpenId.isNotEmpty() && userOpenId == ownerOpenId

    // ---------------------------------------------------------------- 绑定验证码
    data class PendingCode(
        val code: String,
        /** 生成时选定的机器人 id，空表示对所有机器人有效 */
        val botId: String,
        val createdAt: Long
    ) {
        fun remainMs(now: Long = System.currentTimeMillis()): Long =
            (createdAt + CODE_TTL_MS - now).coerceAtLeast(0L)

        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = remainMs(now) == 0L
    }

    /** 当前待用的验证码；没有或已过期返回 null */
    fun pendingCode(): PendingCode? {
        val code = sp.getString(KEY_CODE, "").orEmpty()
        if (code.isEmpty()) return null
        val at = sp.getLong(KEY_CODE_AT, 0L)
        val bot = sp.getString(KEY_CODE_BOT, "").orEmpty()
        val p = PendingCode(code, bot, at)
        if (p.isExpired()) {
            clearCode()
            return null
        }
        return p
    }

    /**
     * 生成一个新的绑定验证码。
     *
     * 为什么「永远不会相同」：每次生成都是 SecureRandom 取 8 位，
     * 字符集 31 个符号 → 31^8 ≈ 8.5e11 种组合，且旧码在生成新码时被覆盖，
     * 同时只在 5 分钟窗口内有效，碰撞概率可以忽略。
     */
    fun generateCode(botId: String = ""): PendingCode {
        val sb = StringBuilder(8)
        repeat(8) {
            sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        }
        val p = PendingCode(sb.toString(), botId, System.currentTimeMillis())
        sp.edit()
            .putString(KEY_CODE, p.code)
            .putLong(KEY_CODE_AT, p.createdAt)
            .putString(KEY_CODE_BOT, p.botId)
            .apply()
        return p
    }

    fun clearCode() {
        sp.edit().remove(KEY_CODE).remove(KEY_CODE_AT).remove(KEY_CODE_BOT).apply()
    }

    /**
     * 校验并消费验证码。
     *
     * 大小写不敏感、忽略空格与全角字符 —— 用户在手机 QQ 里手打，容错必须宽。
     * 消费后会立即清空，一次性使用。
     */
    fun consumeCode(input: String): Result<PendingCode> {
        val p = pendingCode() ?: return Result.failure(IllegalStateException("验证码已过期，请在 APP 里重新生成"))
        val a = normalize(input)
        if (a.isEmpty()) return Result.failure(IllegalArgumentException("请输入验证码"))
        if (a != normalize(p.code)) return Result.failure(IllegalArgumentException("验证码不正确"))
        clearCode()
        return Result.success(p)
    }

    private fun normalize(s: String): String =
        s.trim()
            .replace("　", "")
            .replace(" ", "")
            .uppercase()

    // ---------------------------------------------------------------- 绑定 / 解绑
    fun bindOwner(openId: String, name: String = ""): Result<Unit> {
        if (openId.isBlank()) return Result.failure(IllegalArgumentException("openid 不能为空"))
        if (hasOwner && ownerOpenId != openId) {
            return Result.failure(IllegalStateException("已存在主人，请先在 APP 里解除绑定"))
        }
        sp.edit()
            .putString(KEY_OWNER, openId)
            .putString(KEY_OWNER_NAME, name)
            .putLong(KEY_OWNER_AT, System.currentTimeMillis())
            .apply()
        return Result.success(Unit)
    }

    fun unbindOwner() {
        sp.edit()
            .remove(KEY_OWNER)
            .remove(KEY_OWNER_NAME)
            .remove(KEY_OWNER_AT)
            .apply()
    }

    /** 脱敏显示 openid：只保留首尾各 4 位 */
    fun mask(openId: String): String {
        if (openId.length <= 10) return openId
        return openId.take(4) + "…" + openId.takeLast(4)
    }

    /** openid 的短哈希，用于在没有昵称时给出稳定可辨认的标识 */
    fun shortHash(openId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(openId.toByteArray())
        return d.take(3).joinToString("") { "%02x".format(it) }
    }
}
