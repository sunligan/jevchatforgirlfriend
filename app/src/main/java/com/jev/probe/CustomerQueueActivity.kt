package com.jev.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.CustomerLead
import com.jev.probe.core.CustomerLeadStore
import kotlin.math.roundToInt
import android.util.TypedValue

/** Local-only lead and WeChat dispatch queue for Customer Beta 0.1. */
class CustomerQueueActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var store: CustomerLeadStore
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val accent = Color.parseColor("#3A7AFE")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CustomerLeadStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(Color.parseColor("#F2F3F5"))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        render()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text("客服线索 / 微信分发队列", 24f, ink, true))
        root.addView(text("全部保存在 App 私有目录。Beta 0.1 默认复制/填入，最后由你确认发送。", 12f, sub))
        val leads = store.all().sortedByDescending { it.createdAt }
        if (leads.isEmpty()) {
            root.addView(card().apply { addView(text("还没有线索。抖音客服模式识别到手机号、微信号或相关暗示后会出现在这里。", 13f, sub)) })
            return
        }
        leads.forEach { root.addView(leadCard(it)) }
    }

    private fun leadCard(lead: CustomerLead): View = card().apply {
        addView(text("${lead.category} · ${lead.targetGroup.ifBlank { "未配置目标群" }}", 15f, ink, true))
        addView(text("来源：${lead.sourceApp} / ${lead.sourceTitle.ifBlank { "当前会话" }}", 12f, sub))
        lead.phone?.let { addView(text("电话：$it", 13f, ink)) }
        lead.wechat?.let { addView(text("微信：$it", 13f, ink)) }
        addView(text("原文：${lead.rawText}", 13f, ink))
        addView(text(if (lead.dispatched) "状态：已处理" else "状态：待分发", 12f,
            if (lead.dispatched) Color.parseColor("#16A34A") else Color.parseColor("#D97706"), true))
        val row = LinearLayout(this@CustomerQueueActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("复制分发文本") { copy(dispatchText(lead)) })
        if (!lead.dispatched) row.addView(button("标记已处理") { store.markDispatched(lead.id); render() })
        addView(row)
    }

    private fun dispatchText(lead: CustomerLead): String = buildString {
        append("【抖音客户线索】\n")
        append("分类：").append(lead.category).append('\n')
        if (lead.phone != null) append("电话：").append(lead.phone).append('\n')
        if (lead.wechat != null) append("微信：").append(lead.wechat).append('\n')
        append("抖音会话：").append(lead.sourceTitle).append('\n')
        append("原文：").append(lead.rawText)
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("customer_lead", text))
        android.widget.Toast.makeText(this, "已复制，可到目标微信群粘贴", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }
}
