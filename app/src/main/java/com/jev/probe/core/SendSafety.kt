package com.jev.probe.core

/**
 * Hard constraint 3: never touch transfer / red packet / payment UI.
 *
 * Kept as pure text matching so the rule is unit-testable. An untestable guard
 * on a hard constraint is how the auto-send path shipped with no money check at
 * all — the only thing between clicking send and a Douyin pay / gift / DOU+
 * sheet was an exact "发送" label match.
 *
 * What to scan is the caller's decision: the candidate button's own subtree
 * plus its ancestor chain, never the whole tree. Douyin parks a gift entry
 * beside the composer, so a whole-tree scan would refuse every legitimate send;
 * a payment sheet, by contrast, is on the chain because the button would be
 * inside it.
 */
object MoneyGuard {
    private val WORDS = listOf(
        "支付", "付款", "转账", "红包", "收款", "打赏", "礼物", "充值",
        "钱包", "结算", "订单", "pay", "wallet", "gift", "transfer", "checkout"
    )

    fun mentions(text: String?, description: String?): Boolean {
        val t = text.orEmpty()
        val d = description.orEmpty()
        if (t.isEmpty() && d.isEmpty()) return false
        return WORDS.any { t.contains(it, true) || d.contains(it, true) }
    }
}
