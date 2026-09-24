package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProtocolIdx = 0
    private var replyProtocolIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))

        // Capture all typed connection fields before wiring test buttons. Tests never use stale saved values.
        val judgeBaseEdit = edit(prefs.judgeBaseUrl, "HTTPS 地址或完整接口路径")
        val judgeModelEdit = edit(prefs.judgeModel, "填写服务商提供的模型 ID")
        judgeKeyEdit = edit(prefs.judgeKey, "判断接口密钥", password = true)
        val replyBaseEdit = edit(prefs.replyBaseUrl, "HTTPS 地址或完整接口路径")
        val replyModelEdit = edit(prefs.replyModel, "填写服务商提供的模型 ID")
        replyKeyEdit = edit(prefs.replyKey, "留空仅可复用同一服务地址的判断密钥", password = true)
        judgeProtocolIdx = when (prefs.judgeProtocol) {
            Prefs.PROTOCOL_OPENAI -> 1
            Prefs.PROTOCOL_ANTHROPIC -> 2
            else -> 0
        }
        var legacyProvider = prefs.judgeProvider
        replyProtocolIdx = if (prefs.replyProtocol == Prefs.PROTOCOL_ANTHROPIC) 1 else 0
        val judgeFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val judgeUseReplyRow = toggleRow("判断和排序复用下方回复接口", prefs.judgeUseReply) { enabled ->
            judgeFields.visibility = if (enabled) View.GONE else View.VISIBLE
        }
        judgeFields.visibility = if (prefs.judgeUseReply) View.GONE else View.VISIBLE

        fun typedConnections(name: String): Prefs = draftPrefs(name) {
            judgeProtocol = protocolOf(judgeProtocolIdx)
            judgeUseReply = (judgeUseReplyRow.tag as? Boolean) ?: false
            judgeProvider = legacyProvider
            judgeBaseUrl = judgeBaseEdit.text.toString().trim()
            judgeKey = judgeKeyEdit.text.toString().trim()
            judgeModel = judgeModelEdit.text.toString().trim()
            replyProtocol = if (replyProtocolIdx == 1) Prefs.PROTOCOL_ANTHROPIC else Prefs.PROTOCOL_OPENAI
            replyBaseUrl = replyBaseEdit.text.toString().trim()
            replyKey = replyKeyEdit.text.toString().trim()
            replyModel = replyModelEdit.text.toString().trim()
        }
        fun validateJudge(probe: Prefs) {
            require(probe.hasKey()) { "请填写实际使用的判断/回复接口密钥；不同服务地址不会互借密钥" }
            require(probe.effectiveJudgeModel().isNotBlank()) { "请填写实际使用的模型 ID" }
            require(probe.effectiveJudgeBase().isNotBlank()) { "请填写接口地址" }
            if (!probe.isJevJudge()) probe.judgeEndpoint()
        }

        val judgeCard = card()
        judgeCard.addView(cardTitle("判断与排序接口"))
        judgeCard.addView(text("可复用回复接口，只配一套；也可关闭复用，分别配置。Jev 是可选项。", 12f, sub))
        judgeCard.addView(judgeUseReplyRow)
        val legacyPresets = pills(listOf("Jev / OpenRouter", "Jev / TypeSafe", "Jev / 完整自定义地址"),
            when (legacyProvider) { Prefs.PROVIDER_TYPESAFE -> 1; Prefs.PROVIDER_CUSTOM -> 2; else -> 0 }) { idx ->
            legacyProvider = when (idx) { 1 -> Prefs.PROVIDER_TYPESAFE; 2 -> Prefs.PROVIDER_CUSTOM; else -> Prefs.PROVIDER_OPENROUTER }
            when (idx) {
                0 -> { judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENROUTER); judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER) }
                1 -> { judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_TYPESAFE); judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE) }
                2 -> {
                    val base = judgeBaseEdit.text.toString().trim().trimEnd('/')
                    judgeBaseEdit.setText(when (base) {
                        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> "${base}/alpha/decisions"
                        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> "${base}/v1/systemone"
                        else -> base
                    })
                }
            }
        }
        legacyPresets.visibility = if (judgeProtocolIdx == 0) View.VISIBLE else View.GONE
        judgeFields.addView(pills(listOf("Jev", "OpenAI 兼容", "Anthropic"), judgeProtocolIdx) { idx ->
            judgeProtocolIdx = idx
            legacyPresets.visibility = if (idx == 0) View.VISIBLE else View.GONE
            when (idx) {
                0 -> {
                    if (legacyProvider != Prefs.PROVIDER_CUSTOM) {
                        judgeBaseEdit.setText(if (legacyProvider == Prefs.PROVIDER_TYPESAFE) Prefs.DEFAULT_JUDGE_BASE_TYPESAFE else Prefs.DEFAULT_JUDGE_BASE_OPENROUTER)
                        judgeModelEdit.setText(if (legacyProvider == Prefs.PROVIDER_TYPESAFE) Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE else Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER)
                    }
                }
                1 -> { judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENAI); judgeModelEdit.setText("") }
                2 -> { judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_ANTHROPIC); judgeModelEdit.setText("") }
            }
        })
        judgeFields.addView(legacyPresets)
        judgeFields.addView(label("Base URL")); judgeFields.addView(judgeBaseEdit)
        judgeFields.addView(text("OpenAI：域名根地址、/v1 或完整 /chat/completions；Anthropic：根地址、/v1 或完整 /v1/messages。切换后请核对地址、密钥、模型。", 11f, sub))
        judgeFields.addView(label("密钥")); judgeFields.addView(judgeKeyEdit)
        judgeFields.addView(label("模型 ID")); judgeFields.addView(judgeModelEdit)
        judgeCard.addView(judgeFields)
        val judgeResult = resultText()
        judgeCard.addView(cardBtn("测试当前判断配置") {
            val probe = typedConnections(SCRATCH_JUDGE)
            try { validateJudge(probe) } catch (e: Exception) { judgeResult.text = e.message; return@cardBtn }
            val client = JudgeClient(probe)
            judgeResult.text = "测试中…（使用当前表单，无需先保存）"
            worker.execute {
                val start = System.currentTimeMillis()
                val a = client.judge(ChatSnapshot("连通测试", listOf(Msg("me", "你好"), Msg("other", "在吗？"))), prefs.relationship)
                main.post {
                    judgeResult.text = if (a.error != null) "失败：${a.error}"
                    else "成功 ${System.currentTimeMillis() - start}ms · ${a.trueIntent?.choice ?: "?"}" +
                        if (a.probabilistic) " · Jev 置信 ${pct(a.trueIntent?.confidence)}" else " · 模型自评 ${a.confidenceLevel}（非概率）"
                }
            }
        })
        judgeCard.addView(judgeResult)
        root.addView(judgeCard)

        val replyCard = card()
        replyCard.addView(cardTitle("回复接口"))
        replyCard.addView(text("支持 OpenAI Chat Completions / Anthropic Messages。复用开启时，本配置同时负责判断、生成和排序，不再需要 Jev。", 12f, sub))
        val replyIdx = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0; Prefs.DEEPSEEK_BASE -> 1; Prefs.DASHSCOPE_BASE -> 2; else -> 3
        }
        val replyPresets = pills(listOf("OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"), replyIdx) { idx ->
            when (idx) {
                0 -> { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE); replyModelEdit.setText(Prefs.DEFAULT_REPLY_MODEL) }
                1 -> { replyBaseEdit.setText(Prefs.DEEPSEEK_BASE); replyModelEdit.setText(Prefs.DEEPSEEK_MODEL) }
                2 -> { replyBaseEdit.setText(Prefs.DASHSCOPE_BASE); replyModelEdit.setText(Prefs.DASHSCOPE_MODEL) }
            }
        }
        replyPresets.visibility = if (replyProtocolIdx == 0) View.VISIBLE else View.GONE
        replyCard.addView(pills(listOf("OpenAI 兼容", "Anthropic"), replyProtocolIdx) { idx ->
            replyProtocolIdx = idx
            replyPresets.visibility = if (idx == 0) View.VISIBLE else View.GONE
            if (idx == 1) { replyBaseEdit.setText(Prefs.DEFAULT_REPLY_BASE_ANTHROPIC); replyModelEdit.setText("") }
            else { replyBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_OPENAI); replyModelEdit.setText("") }
        })
        replyCard.addView(replyPresets)
        replyCard.addView(label("Base URL")); replyCard.addView(replyBaseEdit)
        replyCard.addView(label("密钥")); replyCard.addView(replyKeyEdit)
        replyCard.addView(label("模型 ID（以服务商账号可用列表为准）")); replyCard.addView(replyModelEdit)
        val replyResult = resultText()
        replyCard.addView(cardBtn("测试当前回复配置") {
            val probe = typedConnections(SCRATCH_REPLY)
            try {
                require(probe.effectiveReplyKey().isNotBlank()) { "请填写回复密钥，或配置同一服务地址的判断密钥" }
                require(probe.replyModel.isNotBlank()) { "请填写回复模型 ID" }
                probe.replyEndpoint()
            } catch (e: Exception) { replyResult.text = e.message; return@cardBtn }
            val client = ReplyClient(probe)
            replyResult.text = "测试中…（使用当前表单，无需先保存）"
            worker.execute {
                val start = System.currentTimeMillis()
                val result = runCatching { client.ping() }
                main.post {
                    replyResult.text = result.fold(
                        { "成功 ${System.currentTimeMillis() - start}ms · ${it.replace("\n", " ").take(60)}" },
                        { "失败：${it.message}" })
                }
            }
        })
        replyCard.addView(replyResult)
        root.addView(replyCard)

        // --- 视觉接口 ---
        val visionCard = card()
        visionCard.addView(cardTitle("视觉接口（OCR 用，可先不填）"))
        visionCard.addView(text("读不到控件树的 App 走截图识别。B 阶段才用到，现在填不填都不影响。", 12f, sub))

        val visionBaseEdit = edit(prefs.visionBaseUrl, Prefs.DEFAULT_VISION_BASE)
        val visionModelEdit = edit(prefs.visionModel, Prefs.DEFAULT_VISION_MODEL)
        val visionIdx = when (prefs.visionBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_VISION_BASE -> 0
            Prefs.DASHSCOPE_BASE -> 1
            else -> 2
        }
        visionCard.addView(pills(
            listOf("OpenRouter", "通义兼容", "自定义"), visionIdx) { idx ->
            when (idx) {
                0 -> { visionBaseEdit.setText(Prefs.DEFAULT_VISION_BASE); visionModelEdit.setText(Prefs.DEFAULT_VISION_MODEL) }
                1 -> { visionBaseEdit.setText(Prefs.DASHSCOPE_BASE); visionModelEdit.setText(Prefs.DASHSCOPE_VISION_MODEL) }
            }
        })
        visionCard.addView(label("Base URL"))
        visionCard.addView(visionBaseEdit)
        visionCard.addView(label("密钥"))
        visionCard.addView(edit(prefs.visionKey, "留空则用回复接口密钥", password = true).also { visionKeyEdit = it })
        visionCard.addView(label("模型"))
        visionCard.addView(visionModelEdit)
        val visionResult = resultText()
        visionCard.addView(cardBtn("测试视觉") {
            val visionBase = visionBaseEdit.text.toString().trim()
            if (!VisionClient.supportsVision(visionBase.ifBlank { Prefs.DEFAULT_VISION_BASE })) {
                visionResult.text = GUARD_NO_VISION
                return@cardBtn
            }
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeKey = judgeKeyEdit.text.toString().trim()
                judgeBaseUrl = judgeBaseEdit.text.toString().trim()
                replyBaseUrl = replyBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
                replyKey = replyKeyEdit.text.toString().trim()
                visionBaseUrl = visionBase
                visionKey = visionKeyEdit.text.toString().trim()
                visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥（或填回复/判断接口密钥）"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        visionCard.addView(visionResult)
        root.addView(visionCard)

        // =================== 分析 ===================
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述（给 Jev 判断用）"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(label("会话白名单（每行一个关键词，空=所有会话）"))
        val wlEdit = edit(prefs.whitelist.joinToString("\n"), "留空则对所有会话生效").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2
        }
        card2.addView(wlEdit)
        val autoRow = toggleRow("对方发消息时自动分析", prefs.autoAnalyze)
        card2.addView(autoRow)

        // --- OCR 兜底（B 阶段）---
        val ocrFallbackRow = toggleRow("树读不到正文时用 OCR 兜底", prefs.ocrFallback)
        card2.addView(ocrFallbackRow)
        card2.addView(text("飞书正文是画上去的、微信伪装失效时也读不到，这时截一次屏本地识别（不上传）。", 11f, sub))
        val ocrAutoRow = toggleRow("OCR 模式自动分析", prefs.ocrAutoAnalyze)
        card2.addView(ocrAutoRow)
        card2.addView(text("关闭时 OCR 认完只亮悬浮球，点一下再分析。", 11f, sub))

        // --- 知识库 / 关联上下文（D 阶段） ---
        val ctxRow = toggleRow("记录聊天历史（只存本机，用于关联上下文）", prefs.contextEnabled)
        card2.addView(ctxRow)
        card2.addView(text("关闭时不写任何聊天内容到磁盘；笔记与联系人匹配仍然照常工作。", 11f, sub))
        card2.addView(label("注入最近历史条数（0–100）"))
        val ctxCountEdit = edit(prefs.contextHistoryCount.toString(), "30").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        card2.addView(ctxCountEdit)
        card2.addView(cardBtn("知识库与联系人") {
            startActivity(android.content.Intent(this, KnowledgeActivity::class.java))
        })
        val kbResult = resultText()
        card2.addView(cardBtn("清空知识库、历史与客服线索") {
            val c = KbStore.get(this).counts()
            val leads = com.jev.probe.core.CustomerLeadStore(this).all().size
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清空知识库、历史与客服线索")
                .setMessage("将删除 ${c.notes} 条笔记、${c.contacts} 个联系人、${c.logLines} 条聊天历史、" +
                    "${leads} 条客服线索（含他人手机号与微信号）。密钥、白名单等设置不受影响。不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    KbStore.get(this).clearAll()
                    com.jev.probe.core.CustomerLeadStore(this).clear()
                    kbResult.text = "已清空知识库、历史与客服线索"
                }
                .setNegativeButton("取消", null)
                .show()
        })
        // Deliberately low-key: a developer aid, not a user feature.
        card2.addView(text("自检", 12f, sub).apply {
            setPadding(dp(2), dp(12), dp(8), dp(2))
            setOnClickListener {
                kbResult.text = "自检中…"
                worker.execute {
                    val out = try { KbSelfCheck.run(this@SettingsActivity) }
                    catch (e: Exception) { "自检异常：${e.javaClass.simpleName} ${e.message ?: ""}" }
                    main.post { kbResult.text = out }
                }
            }
        })
        card2.addView(kbResult)
        root.addView(card2)

        // =================== 客服 Beta 0.1 ===================
        root.addView(section("客服 Beta 0.1"))
        val csCard = card()
        csCard.addView(cardTitle("抖音固定话术客服"))
        csCard.addView(text("只处理抖音当前会话；不调用模型生成固定话术。手动模式只填入，自动模式才允许点击发送。", 12f, sub))
        val csModeRow = toggleRow("启用客服 Beta 模式", prefs.customerMode)
        val csAutoRow = toggleRow("固定话术自动发送（高风险）", prefs.customerAutoSend)
        csCard.addView(csModeRow)
        csCard.addView(csAutoRow)
        csCard.addView(text("关闭自动发送时：自动识别、填入，但最后由你点击发送。开启后：只对预设固定话术尝试发送，识别不确定时不会发送。", 11f, sub))
        csCard.addView(label("固定回复第 1 句"))
        val csFirstEdit = edit(prefs.customerReplyFirst, "例如：您好，请问您需要咨询什么？").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; gravity = android.view.Gravity.TOP
        }
        csCard.addView(csFirstEdit)
        csCard.addView(label("固定回复第 2 句"))
        val csSecondEdit = edit(prefs.customerReplySecond, "例如：如果方便，请留下您的联系方式。").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; gravity = android.view.Gravity.TOP
        }
        csCard.addView(csSecondEdit)
        csCard.addView(text("两句都留空时不会发送任何内容，面板会提示人工处理；自动发送也不会启动。", 11f, sub))
        csCard.addView(label("线索分类（可自定义）"))
        val csCategoryEdit = edit(prefs.customerCategory, "例如：高意向 / 售后 / 代理商")
        csCard.addView(csCategoryEdit)
        csCard.addView(label("默认微信分发群"))
        val csGroupEdit = edit(prefs.customerWechatGroup, "例如：高意向客户群")
        csCard.addView(csGroupEdit)
        csCard.addView(text("Beta 0.1 会保存抖音线索和待分发群名；微信分发先以队列/填入为主，避免发错群。", 11f, sub))
        csCard.addView(cardBtn("客服线索 / 微信分发队列") {
            startActivity(android.content.Intent(this, CustomerQueueActivity::class.java))
        })
        root.addView(csCard)

        // =================== 外观 ===================
        root.addView(section("外观"))
        val card3 = card()
        val opacityLabel = label("悬浮窗不透明度：${prefs.overlayOpacity}%")
        card3.addView(opacityLabel)
        card3.addView(text("越低越透，越能看清下面的聊天", 12f, sub))
        val seek = SeekBar(this).apply {
            max = 40; progress = prefs.overlayOpacity - 60  // 60..100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    opacityLabel.text = "悬浮窗不透明度：${p + 60}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        card3.addView(seek)
        root.addView(card3)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            prefs.judgeProtocol = protocolOf(judgeProtocolIdx)
            prefs.judgeUseReply = (judgeUseReplyRow.tag as? Boolean) ?: false
            prefs.judgeProvider = legacyProvider
            prefs.judgeBaseUrl = judgeBaseEdit.text.toString().trim()
            prefs.judgeKey = judgeKeyEdit.text.toString().trim()
            prefs.judgeModel = judgeModelEdit.text.toString().trim()
            prefs.replyProtocol = if (replyProtocolIdx == 1) Prefs.PROTOCOL_ANTHROPIC else Prefs.PROTOCOL_OPENAI
            prefs.replyBaseUrl = replyBaseEdit.text.toString().trim()
            prefs.replyKey = replyKeyEdit.text.toString().trim()
            prefs.replyModel = replyModelEdit.text.toString().trim()

            prefs.customerMode = (csModeRow.tag as? Boolean) ?: false
            prefs.customerAutoSend = (csAutoRow.tag as? Boolean) ?: false
            prefs.customerReplyFirst = csFirstEdit.text.toString().trim()
            prefs.customerReplySecond = csSecondEdit.text.toString().trim()
            prefs.customerCategory = csCategoryEdit.text.toString().trim().ifBlank { "未分类" }
            prefs.customerWechatGroup = csGroupEdit.text.toString().trim()

            prefs.visionBaseUrl = visionBaseEdit.text.toString().trim()
            prefs.visionKey = visionKeyEdit.text.toString()
            prefs.visionModel = visionModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_VISION_MODEL }

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.whitelist = wlEdit.text.toString().split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = (autoRow.tag as? Boolean) ?: true
            prefs.ocrFallback = (ocrFallbackRow.tag as? Boolean) ?: true
            prefs.ocrAutoAnalyze = (ocrAutoRow.tag as? Boolean) ?: false
            prefs.contextEnabled = (ctxRow.tag as? Boolean) ?: false
            prefs.contextHistoryCount =
                ctxCountEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 100) ?: 30
            prefs.overlayOpacity = seek.progress + 60
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    // Held as fields because several test buttons read each other's key box.
    private lateinit var judgeKeyEdit: EditText
    private lateinit var replyKeyEdit: EditText
    private lateinit var visionKeyEdit: EditText

    private fun protocolOf(idx: Int) = when (idx) {
        1 -> Prefs.PROTOCOL_OPENAI
        2 -> Prefs.PROTOCOL_ANTHROPIC
        else -> Prefs.PROTOCOL_JEV
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean, onChange: ((Boolean) -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
            onChange?.invoke(now)
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** DeepSeek's official API has no vision model; say so instead of a 400. */
        private const val GUARD_NO_VISION =
            "该接口不支持视觉（DeepSeek 官方没有 image_url），请换 OpenRouter 或通义兼容"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

    }
}
