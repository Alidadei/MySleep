package org.fossify.clock.helpers

/**
 * Local anti-spam gate for community submissions: ad/sales content is
 * rejected outright. Pure-Java rules (keywords + contact-pattern regexes)
 * run before anything is stored; when the cloud backend lands this stays as
 * the first line of defense and the server runs the same rules server-side.
 */
object AdGuard {

    /** Strong signals - any single hit blocks the submission. */
    private val strongKeywords = listOf(
        "加微信", "加vx", "加V", "加企鹅", "私聊", "私信我", "联系我", "扫码", "二维码",
        "优惠券", "领券", "折扣", "低价出", "出售", "急出", "二手转", "代购", "代下单",
        "下单立减", "货到付款", "返现", "佣金", "招商", "招代理", "兼职", "刷单",
        "引流", "涨粉", "粉丝群", "资源群", "内部群", "免费领", "点击购买", "立即购买",
        "店铺", "旗舰店", "微店", "拼多多", "淘宝下单", "京东自营店", "带货", "卖货",
        "招聘", "贷款", "借款", "办理", "低价充值", "会员充值", "账号出售", "外挂"
    )

    /** Weak signals - two or more hits block the submission. */
    private val weakKeywords = listOf(
        "广告", "推广", "下单", "购买", "秒杀", "限时", "福利", "补贴", "开户",
        "咨询", "官网址", "平台", "一手货源", "厂家直销", "包赚", "稳赚", "收益"
    )

    private val contactPatterns = listOf(
        // 微信/vx/v + 5+ 位账号
        Regex("""(?i)(微信|weixin|wx|vx|v信|v:x|威信|薇信)[号:：\s]*[a-zA-Z0-9_-]{5,}"""),
        // QQ 群/号 + 数字
        Regex("""(?i)(qq|扣扣|企鹅)[群号:：\s]*\d{4,}"""),
        // 手机号
        Regex("""1[3-9]\d{9}"""),
        // 常见短链
        Regex("""(?i)(t\.cn|bit\.ly|dwz\.cn|url\.cn|suo\.im|tinyurl)/\S+""")
    )

    /** Verdict reason codes - the caller renders them with localized strings. */
    const val REASON_CONTACT = "contact"
    const val REASON_STRONG = "strong"
    const val REASON_WEAK = "weak"

    sealed class Verdict {
        object Ok : Verdict()
        data class Blocked(val code: String, val detail: String) : Verdict()
    }

    fun check(vararg texts: String?): Verdict {
        val all = texts.joinToString(" ") { it ?: "" }
        val lower = all.lowercase()

        contactPatterns.forEach { pattern ->
            pattern.find(lower)?.let {
                return Verdict.Blocked(REASON_CONTACT, it.value.take(24))
            }
        }

        strongKeywords.firstOrNull { lower.contains(it.lowercase()) }?.let {
            return Verdict.Blocked(REASON_STRONG, it)
        }

        val weakHits = weakKeywords.count { lower.contains(it.lowercase()) }
        if (weakHits >= 2) {
            return Verdict.Blocked(REASON_WEAK, weakHits.toString())
        }

        return Verdict.Ok
    }
}
