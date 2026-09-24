package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.capture.ocr.MlKitOcr
import com.jev.probe.capture.ocr.OcrLine
import com.jev.probe.capture.ocr.ScreenCapture
import com.jev.probe.core.BubbleRect
import com.jev.probe.core.CapturePolicy
import com.jev.probe.core.CaptureSource
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.ConversationGuard
import com.jev.probe.core.CustomerConfig
import com.jev.probe.core.CustomerLead
import com.jev.probe.core.CustomerLeadStore
import com.jev.probe.core.CustomerSendSchedule
import com.jev.probe.core.CustomerSessionController
import com.jev.probe.core.MoneyGuard
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.SnapshotChange
import com.jev.probe.core.SnapshotStability
import com.jev.probe.core.kb.ContextBuilder
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JevClient
import com.jev.probe.overlay.OverlayController
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Future

/**
 * The live capture service (registered under a disguised class name so WeChat
 * exposes its node tree — see the disguised subclass). It reads whichever
 * adapted chat app is in the foreground, detects a new incoming message from the
 * other person, runs Jev analysis off the main thread, and drives the floating
 * overlay.
 *
 * Per-app node rules live in [ChatAppAdapter] implementations; everything here
 * is app-agnostic.
 *
 * Sending. The personal copilot never sends: its only write action is
 * ACTION_SET_TEXT (or a clipboard PASTE fallback) to fill the chat input box
 * when the user taps "填入", and the user still presses send. The customer beta
 * is the single exception — [clickSendIfSafe] may press a send button, for
 * pre-configured fixed text only, never for model output, and only behind two
 * default-off switches (`customerMode`, `customerAutoSend`) plus a hard
 * `isDouyin` package gate. WeChat / QQ / X / Feishu cannot reach that path.
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** Adapted chat apps, keyed by package name. */
    private val adapters = listOf(
        WeChatAdapter(), QQAdapter(), XAdapter(), FeishuAdapter(),
        DouyinAdapter("com.ss.android.ugc.aweme"), DouyinAdapter("com.ss.android.ugc.aweme.lite")
    ).associateBy { it.pkg }

    /** Submit to the worker, ignoring rejection after the service is torn down
     *  (a stale overlay callback must never crash the process). */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var activePkg: String? = null
    private val conversationGuard = ConversationGuard()
    private var analysisJob: Future<*>? = null
    @Volatile private var connected = false
    private var visibleScreenKey: String? = null
    private var screenEpoch = 0L
    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    private var currentSnapshot: ChatSnapshot? = null
    private var stableSnapshot: ChatSnapshot? = null
    @Volatile private var assistantPaused = false
    private val capturePolicy = CapturePolicy()
    private var stopEnabledObserver: (() -> Unit)? = null
    private val customerLeads by lazy { CustomerLeadStore(this) }
    private val customerSession = CustomerSessionController()
    /**
     * Auto-send steps still waiting on their delay. Kept as named Runnables so
     * [invalidateConversation] can cancel them: an anonymous `postDelayed` is
     * unreachable, and leaving one alive means it fires after the operator has
     * switched to a different conversation — currently caught downstream by
     * [validatedInput] re-checking the ticket, which is a safety net, not a plan.
     */
    private val pendingAutoSends = ArrayList<Runnable>()
    /** Created on first use, released in [onDestroy]; a ToneGenerator per lead
     *  leaks the native audio resource it holds. */
    private var toneGen: ToneGenerator? = null
    private var lastViewport: String? = null
    private var lastIme: String? = null
    private var captureScheduled = false
    private val captureDebounce = Runnable { captureScheduled = false; refreshForeground() }

    /** Called on the main thread, including when leaving a chat or disabling the service. */
    private fun invalidateConversation() {
        screenEpoch++
        visibleScreenKey = null
        pendingSnapshot = null
        currentSnapshot = null
        stableSnapshot = null
        activePkg = null
        lastOcrSignature = ""
        lastViewport = null
        main.removeCallbacks(captureDebounce)
        captureScheduled = false
        main.removeCallbacks(debounce)
        cancelAutoSends()   // a pending fixed-text send must not survive its conversation
        conversationGuard.invalidate()
        customerSession.reset()   // the next conversation's leads are scanned from scratch
        analysisJob?.cancel(true)
        analysisJob = null
        overlay?.resetForNewConversation()
    }

    private fun observation(pkg: String, windowId: Int, snapshot: ChatSnapshot) =
        ConversationGuard.Observation(
            pkg, windowId, snapshot.title?.trim(), snapshot.signature(),
            snapshot.source == CaptureSource.ACCESSIBILITY && !snapshot.title.isNullOrBlank()
        )

    // Never carry the previous person's title into an unidentified conversation.
    private fun cleanTitle(snapshot: ChatSnapshot): ChatSnapshot =
        if (isTransientTitle(snapshot.title)) snapshot.copy(title = null)
        else snapshot.copy(title = snapshot.title?.trim())

    private fun conversationKey(root: AccessibilityNodeInfo, snapshot: ChatSnapshot): String {
        val title = snapshot.title.orEmpty()
        return "${root.packageName}/${root.windowId}/${title.length}:${title}"
    }

    /** Use the live focused application if Android temporarily exposes an IME root. */
    private fun foregroundRoot(): AccessibilityNodeInfo? {
        val current = rootInActiveWindow
        val list = runCatching { windows }.getOrDefault(emptyList())
        val activeWindow = list.firstOrNull { it.id == current?.windowId }
        if (current != null && activeWindow?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return current
        val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val focused = apps.filter { it.isFocused }
        return (focused.singleOrNull() ?: apps.filter { it.isActive }.singleOrNull())?.root
    }

    private fun screenKey(root: AccessibilityNodeInfo, snapshot: ChatSnapshot): String =
        conversationKey(root, snapshot) + "/" + if (snapshot.messages.isEmpty())
            ocrSignature(root.packageName.toString(), snapshot.title, snapshot.bubbleRects)
        else snapshot.signature()

    /** Only the exact current conversation may borrow its own pre-keyboard snapshot. */
    private fun canonicalSnapshot(root: AccessibilityNodeInfo, live: ChatSnapshot): ChatSnapshot =
        if (live.title.isNullOrBlank() || conversationKey(root, live) != visibleScreenKey) live
        else SnapshotStability.canonical(stableSnapshot, live)

    private fun liveObservation(root: AccessibilityNodeInfo? = foregroundRoot()): ConversationGuard.Observation? {
        root ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val live = adapters[pkg]?.extract(root, resources)?.let(::cleanTitle) ?: return null
        if (live.messages.isEmpty() || !prefs.isAllowed(live.title)) return null
        return observation(pkg, root.windowId, canonicalSnapshot(root, live))
    }

    private fun isCurrent(ticket: ConversationGuard.Ticket): Boolean =
        connected && !assistantPaused && prefs.enabled && conversationGuard.accepts(ticket)

    private fun canRender(ticket: ConversationGuard.Ticket): Boolean {
        if (!isCurrent(ticket)) return false
        val root = foregroundRoot() ?: return false
        val owner = ticket.observation
        if (root.windowId != owner.windowId || root.packageName?.toString() != owner.packageName) return false
        if (currentSnapshot?.source == CaptureSource.ACCESSIBILITY) return liveObservation(root) == owner
        val adapter = adapters[owner.packageName] ?: return true // manual OCR remains copy-only
        val raw = adapter.extract(root, resources)?.let(::cleanTitle) ?: return false
        return conversationKey(root, raw) == visibleScreenKey
    }

    // ---- OCR path (B stage). Everything here runs on the main thread: the
    // screenshot callback and the ML Kit callback are both posted back to it.
    private val screenCapture by lazy {
        ScreenCapture(this,
            hideOverlay = { overlay?.setHiddenForShot(true) },
            restoreOverlay = { overlay?.setHiddenForShot(false) },
            canCapture = { connected && prefs.enabled && !assistantPaused },
            activeRoot = { foregroundRoot() })
    }
    private val ocr = MlKitOcr()
    private var ocrBusy = false

    /** What the screen looked like the last time we fired an automatic shot.
     *  See [ocrSignature]: this is the brake on the OCR path. */
    private var lastOcrSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        connected = true
        overlay = OverlayController(this)
        assistantPaused = !prefs.enabled
        capturePolicy.setPaused(assistantPaused)
        overlay?.onToggleEnabled = {
            prefs.enabled = !prefs.enabled
            syncPauseState()
        }
        stopEnabledObserver = prefs.observeEnabled {
            main.post { if (connected) syncPauseState() }
        }
        overlay?.setPaused(assistantPaused)
        if (assistantPaused) overlay?.showPaused()
        overlay?.onManualAnalyze = { analyzeVisibleConversation() }
        // Bubble menu: file the open conversation as a knowledge-base contact.
        // Contacts are never created automatically — this is the one-tap way in.
        overlay?.onSaveContact = saveContact@{
            if (!prefs.enabled || assistantPaused) return@saveContact
            val snapshot = currentSnapshot
            val title = snapshot?.title
            val pkg = activePkg ?: ""
            when {
                snapshot?.source == CaptureSource.SCREEN_OCR -> overlay?.toast("整屏 OCR 无法确认联系人，请在知识库手动建档")
                title.isNullOrBlank() -> overlay?.toast("当前会话没有标题，存不了")
                isTransientTitle(title) -> overlay?.toast("当前会话标题还没加载出来，稍后再试")
                else -> submit {
                    val msg = try {
                        KbStore.get(this).saveOrMergeContact(title, pkg)
                    } catch (e: Exception) { "保存失败：${e.javaClass.simpleName}" }
                    main.post { overlay?.toast(msg) }
                }
            }
        }
        // Bubble menu: one manual screenshot + OCR, for any app at all.
        overlay?.onOcrCapture = { ocrCaptureManual() }
        // Keep the process at foreground importance so MIUI does not freeze us.
        runCatching { KeepAliveService.start(this) }
        // Load the bundled OCR model now, off the main thread: the first
        // recognize() otherwise pays for it inside the screenshot callback.
        submit { MlKitOcr.warmUp() }
        // HyperOS may kill and restart us. On (re)connect, proactively re-show the
        // bubble for whatever chat is already open, so it comes back on its own
        // instead of waiting for the user to scroll.
        main.postDelayed({ if (connected && prefs.enabled) runCatching { refreshForeground() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    private fun syncPauseState() {
        val wantPaused = !prefs.enabled
        if (wantPaused == assistantPaused) return
        assistantPaused = wantPaused
        capturePolicy.setPaused(wantPaused)
        invalidateConversation() // also revoke the previous session when resuming
        overlay?.setPaused(wantPaused)
        if (wantPaused) overlay?.showPaused()
        else {
            overlay?.showIdle(null)
            scheduleCapture()
        }
    }

    private fun imeStamp(): String = runCatching {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.joinToString(";") {
            val bounds = Rect(); it.getBoundsInScreen(bounds)
            "${it.id}:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        }
    }.getOrDefault("")

    private fun viewportStamp(root: AccessibilityNodeInfo): String {
        val bounds = Rect(); root.getBoundsInScreen(bounds)
        return "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}/${lastIme.orEmpty()}"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected || !::prefs.isInitialized) return
        syncPauseState()
        if (assistantPaused) return // no tree, screenshot, OCR, or model work while paused
        val now = SystemClock.elapsedRealtime()
        val ime = imeStamp()
        if (lastIme != null && ime != lastIme) capturePolicy.onLayout(now)
        lastIme = ime
        val eventIsIme = runCatching {
            windows.any { it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)
        if (eventIsIme) {
            capturePolicy.onLayout(now)
            scheduleCapture()
            return
        }
        // An app can edit a message TextView too. Only ignore edits of actual input fields.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            runCatching { event.source?.isEditable == true }.getOrDefault(false)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                capturePolicy.onScroll(now)
                scheduleCapture()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scheduleCapture()
            else -> Unit
        }
    }

    /** Read the settled tree once, rather than traversing every animation/caret event. */
    private fun scheduleCapture() {
        if (captureScheduled) return // merge event bursts without starving on a blinking caret
        captureScheduled = true
        main.postDelayed(captureDebounce, 250)
    }

    private fun refreshForeground() {
        if (!connected || assistantPaused || !prefs.enabled) return
        val root = foregroundRoot()
        val pkg = root?.packageName?.toString()
        if (pkg in adapters) maybeCapture(capturePolicy.allowAuto(SystemClock.elapsedRealtime()))
        else {
            // Don't repeatedly rewrite the floating window on its own redraw events.
            if (visibleScreenKey != null || currentSnapshot != null || analysisJob != null) invalidateConversation()
            val drop = pkg == null || pkg == packageName || pkg.contains("launcher", true) ||
                pkg == "com.miui.home" || pkg == "com.android.systemui"
            if (drop) overlay?.hide()
            else if (overlay?.isShowing() != true) overlay?.showIdle(null)
        }
    }

    private fun analyzeVisibleConversation() {
        if (!connected || assistantPaused || !prefs.enabled) return
        main.removeCallbacks(captureDebounce)
        captureScheduled = false
        val root = foregroundRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val live = adapters[pkg]?.extract(root, resources)?.let(::cleanTitle)
        if (live != null && live.messages.isNotEmpty()) {
            if (!prefs.isAllowed(live.title)) { invalidateConversation(); overlay?.hide(); return }
            val key = conversationKey(root, live)
            if (key != visibleScreenKey) invalidateConversation()
            visibleScreenKey = key
            // Explicit refresh uses exactly what is visible now, not the preserved pre-keyboard context.
            if (!handleCustomerSnapshot(live, pkg, root.windowId, manual = true, allowAutoAnalyze = true)) {
                acceptSnapshot(live, pkg, root.windowId, manual = true)
            }
        } else ocrCaptureManual()
    }

    private fun maybeCapture(allowAutoAnalyze: Boolean = true) {
        if (!connected || assistantPaused || !prefs.enabled) return
        val root = foregroundRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val snapshot = adapters[pkg]?.extract(root, resources)?.let(::cleanTitle)
        if (snapshot == null || !prefs.isAllowed(snapshot.title)) {
            if (visibleScreenKey != null || currentSnapshot != null) invalidateConversation()
            overlay?.hide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val key = conversationKey(root, snapshot)
        val sameConversation = key == visibleScreenKey
        val viewport = viewportStamp(root)
        if (sameConversation && lastViewport != null && viewport != lastViewport) capturePolicy.onLayout(now)
        if (!sameConversation) {
            invalidateConversation()
            visibleScreenKey = key
        }
        lastViewport = viewport
        activePkg = pkg
        if (snapshot.messages.isEmpty()) {
            val geometry = screenKey(root, snapshot)
            if (!capturePolicy.allowOcr(now, sameConversation)) {
                // Remember the new layout without paying for OCR solely because the keyboard moved it.
                if (lastOcrSignature != geometry) screenEpoch++
                lastOcrSignature = geometry
                return
            }
            if (prefs.ocrFallback && lastOcrSignature != geometry && !ocrBusy) {
                lastOcrSignature = geometry
                ocrCapture(snapshot.title, snapshot.bubbleRects, pkg, manual = false)
            }
            return
        }
        if (handleCustomerSnapshot(snapshot, pkg, root.windowId, manual = false, allowAutoAnalyze)) return
        val change = SnapshotStability.classify(stableSnapshot, snapshot)
        if (change == SnapshotChange.SAME || change == SnapshotChange.VIEWPORT_ONLY) {
            // Fresh nodes can still differ in title/source; classify is identity-aware.
            currentSnapshot = stableSnapshot ?: snapshot
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        val auto = allowAutoAnalyze || SnapshotStability.isAppend(stableSnapshot, snapshot)
        if (!ocrBusy) acceptSnapshot(snapshot, pkg, root.windowId, manual = false, allowAutoAnalyze = auto)
    }

    private fun isDouyin(pkg: String): Boolean =
        pkg == "com.ss.android.ugc.aweme" || pkg == "com.ss.android.ugc.aweme.lite"

    private fun handleCustomerSnapshot(
        snapshot: ChatSnapshot,
        pkg: String,
        windowId: Int,
        manual: Boolean,
        allowAutoAnalyze: Boolean
    ): Boolean {
        if (!prefs.customerMode || !isDouyin(pkg)) return false
        val changed = conversationGuard.observe(observation(pkg, windowId, snapshot))
        currentSnapshot = snapshot; stableSnapshot = snapshot; activePkg = pkg
        if (!changed && !manual) return true

        val action = customerSession.onSnapshot(snapshot, customerConfig())
        storeLeads(action.leads, action.notify)

        val ticket = conversationGuard.start()
        if (ticket == null) return true
        overlay?.resetForNewConversation()
        overlay?.setSnapshotInfo(snapshot)
        overlay?.showCustomerPlan(
            snapshot.title.orEmpty(), action.plan.lines, action.plan.reason, action.autoSend,
            onFill = { text -> fillInput(text, ticket) },
            // A human pressing the button is an explicit decision: never latched.
            onAutoSend = { if (action.autoSend) scheduleAutoSend(action.plan.lines, ticket) }
        )
        // The automatic path is latched — one dispatch per distinct plan per
        // conversation, see CustomerSessionController.
        if (allowAutoAnalyze && action.dispatchNow) scheduleAutoSend(action.plan.lines, ticket)
        return true
    }

    private fun customerConfig() = CustomerConfig(
        replyFirst = prefs.customerReplyFirst,
        replySecond = prefs.customerReplySecond,
        category = prefs.customerCategory,
        targetGroup = prefs.customerWechatGroup,
        autoSend = prefs.customerAutoSend
    )

    /** One schedule, one place — the "立即执行" button and the automatic path
     *  used to carry byte-identical copies of this loop. Replaces any batch
     *  still waiting, so a re-trigger cannot stack a second send on the first. */
    private fun scheduleAutoSend(lines: List<String>, ticket: ConversationGuard.Ticket) {
        cancelAutoSends()
        CustomerSendSchedule.delays(lines.size).forEachIndexed { index, delay ->
            val task = Runnable { fillInput(lines[index], ticket, autoSend = true) }
            pendingAutoSends.add(task)
            main.postDelayed(task, delay)
        }
    }

    private fun cancelAutoSends() {
        pendingAutoSends.forEach { main.removeCallbacks(it) }
        pendingAutoSends.clear()
    }

    /**
     * Leads hold other people's phone numbers and WeChat IDs, so the JSON
     * read/write behind [CustomerLeadStore.append] must never run here: this
     * method is reached from the main-thread capture debounce, and an
     * accessibility service that blocks the main thread gets disconnected by
     * the system. The store's own 24 h same-text dedupe is what makes a re-scan
     * of an unchanged screen harmless.
     */
    private fun storeLeads(leads: List<CustomerLead>, notify: String?) {
        if (leads.isEmpty()) return
        submit {
            val saved = leads.count { customerLeads.append(it) }
            if (saved > 0) main.post {
                beep()
                overlay?.toast(notify ?: "已保存 $saved 条线索")
            }
        }
    }

    private fun beep() {
        if (!connected) return   // a worker callback landing after teardown must not re-create it
        val tone = toneGen ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        }.getOrNull()?.also { toneGen = it } ?: return
        runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180) }
    }

    private fun acceptSnapshot(
        snapshot: ChatSnapshot, pkg: String, windowId: Int, manual: Boolean, allowAutoAnalyze: Boolean = true
    ) {
        if (!connected || assistantPaused || !prefs.enabled) return
        val changed = conversationGuard.observe(observation(pkg, windowId, snapshot))
        currentSnapshot = snapshot
        stableSnapshot = snapshot
        activePkg = pkg
        if (!changed && !manual) {
            if (overlay?.isShowing() != true) overlay?.showIdle(snapshot.title)
            return
        }
        screenEpoch++
        analysisJob?.cancel(true)
        analysisJob = null
        main.removeCallbacks(debounce)
        pendingSnapshot = null
        overlay?.resetForNewConversation()
        overlay?.setSnapshotInfo(snapshot)
        val auto = allowAutoAnalyze && prefs.autoAnalyze && snapshot.latestFrom == "other" &&
            (snapshot.source == CaptureSource.ACCESSIBILITY ||
                (snapshot.source == CaptureSource.BUBBLE_OCR && prefs.ocrAutoAnalyze))
        if (manual || auto) {
            pendingSnapshot = snapshot
            if (manual) runAnalysis() else main.postDelayed(debounce, 800)
        } else overlay?.showIdle(snapshot.title)
    }

    /** A placeholder title an app shows only for a moment (e.g. X's "连接中…"
     *  right after opening a DM thread) — never a real conversation title.
     *  Blank/null counts too, so a caller can always fall back the same way. */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        if (!connected || !prefs.enabled) return
        val root = foregroundRoot() ?: return
        val pkg = activePkg ?: return
        if (root.packageName?.toString() != pkg) { invalidateConversation(); return }
        if (snapshot.source == CaptureSource.ACCESSIBILITY) {
            val live = liveObservation()
            if (live != observation(pkg, root.windowId, snapshot)) {
                invalidateConversation(); overlay?.toast("会话已变化，请重新分析"); return
            }
        }
        if (!prefs.hasKey()) { overlay?.showError("未设置判断接口密钥，去设置里填"); return }
        val ticket = conversationGuard.start() ?: return
        analysisJob?.cancel(true)
        overlay?.showLoading()
        overlay?.setSnapshotInfo(snapshot)
        val client = JevClient(prefs)
        val rel = prefs.relationship
        analysisJob = worker.submit {
            if (!isCurrent(ticket)) return@submit
            // Unknown speaker/title captures must not pollute contact history.
            val ctx = if (snapshot.source == CaptureSource.SCREEN_OCR || snapshot.title.isNullOrBlank()) null
                else try { ContextBuilder.build(this, snapshot, pkg, prefs) }
                catch (e: Exception) {
                    Log.w(TAG, "context build failed: ${e.javaClass.simpleName}"); null
                }
            if (!isCurrent(ticket)) return@submit
            val judgment = client.judge(snapshot, rel, ctx)
            if (!isCurrent(ticket)) return@submit
            main.post judgmentReady@{
                if (!canRender(ticket)) return@judgmentReady
                overlay?.setContextInfo(ctx?.notes?.size ?: 0, ctx?.history?.size ?: 0)
                if (judgment.error != null) overlay?.showError(judgment.error)
                else overlay?.showJudgment(judgment)
            }
            if (judgment.error != null) return@submit
            var replyError: String? = null
            val ranked = try {
                client.draftAndRank(snapshot, rel, ctx, judgment) { isCurrent(ticket) }
            } catch (e: Exception) {
                replyError = e.message ?: e.javaClass.simpleName
                emptyList()
            }
            main.post repliesReady@{
                if (!canRender(ticket)) return@repliesReady
                overlay?.showReplies(ranked, replyError) { text -> fillInput(text, ticket) }
            }
        }
    }

    // ------------------------------------------------------------------ OCR

    /**
     * Bubble menu → "截屏识别一次". Works on ANY app, adapted or not: one whole
     * screen shot, every line OCR'd, lines grouped into pseudo-bubbles by line
     * spacing. Nobody can tell who said what this way, so everything is filed as
     * the other person and the panel says so.
     */
    private fun ocrCaptureManual() {
        if (!connected || assistantPaused || !prefs.enabled) return
        val root = foregroundRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        invalidateConversation()
        activePkg = pkg
        visibleScreenKey = adapters[pkg]?.extract(root, resources)?.let(::cleanTitle)?.let { conversationKey(root, it) }
        // A readable top-bar title is optional; never use a message as the contact name.
        val title = root.let {
            findTitleInActionBar(it, Int.MAX_VALUE, resources.displayMetrics.widthPixels, resources, 0.15, 0.85)
        }?.takeUnless(::isTransientTitle)
        ocrCapture(title, emptyList(), pkg, manual = true)
    }

    /**
     * What the screen would look like to a camera, as far as the tree can tell.
     *
     * Feishu: the conversation title plus every bubble rectangle and its side —
     * the bubbles move whenever the list scrolls or a message arrives, and stay
     * put when only chrome (caret, presence dot, timestamp) redraws. Apps that
     * give us no rectangles fall back to package + title, which at least stops a
     * burst of events on one screen from becoming a burst of screenshots.
     */
    private fun ocrSignature(pkg: String, title: String?, rects: List<BubbleRect>): String {
        if (rects.isEmpty()) return pkg + "|" + (title ?: "")
        return (title ?: "") + "|" + rects.joinToString(";") { br ->
            val r = br.rect
            "${r.left},${r.top},${r.right},${r.bottom},${br.side}"
        }
    }

    /**
     * Screenshot, then either OCR each known bubble rect (Feishu: the tree knows
     * where the bubbles are and who sent them, just not what they say) or OCR
     * the whole screen (everything else).
     */
    private fun ocrCapture(treeTitle: String?, rects: List<BubbleRect>, pkg: String, manual: Boolean) {
        if (!connected || assistantPaused || !prefs.enabled) return
        if (ocrBusy) { lastOcrSignature = ""; return }
        val initialRoot = foregroundRoot() ?: return
        val epoch = screenEpoch
        val windowId = initialRoot.windowId
        val initialTreeKey = adapters[pkg]?.extract(initialRoot, resources)?.let(::cleanTitle)
            ?.let { screenKey(initialRoot, it) }
        val valid: () -> Boolean = {
            val liveRoot = foregroundRoot()
            connected && prefs.enabled && screenEpoch == epoch && liveRoot != null &&
                liveRoot.windowId == windowId && liveRoot.packageName?.toString() == pkg &&
                (initialTreeKey == null || adapters[pkg]?.extract(liveRoot, resources)?.let(::cleanTitle)
                    ?.let { screenKey(liveRoot, it) } == initialTreeKey)
        }
        ocrBusy = true
        val deliver: (ChatSnapshot) -> Unit = { captured ->
            ocrBusy = false
            if (valid()) finishOcrSnapshot(captured, pkg, manual, windowId)
            else lastOcrSignature = ""
        }
        screenCapture.capture { res ->
            if (!valid()) {
                if (res is ScreenCapture.Result.Ok) res.bitmap.recycle()
                ocrBusy = false
                lastOcrSignature = ""
                return@capture
            }
            when (res) {
                is ScreenCapture.Result.Failed -> {
                    ocrBusy = false
                    Log.i(TAG, "ocr: screenshot failed code=${res.code}")
                    // Nothing was read, so the signature must not claim this screen
                    // is done — the next event may retry, still held back by
                    // ScreenCapture's own throttle and failure backoff.
                    if (!manual) lastOcrSignature = ""
                    // Throttle/interval codes are transient timing, not something
                    // the user can act on — nagging about them would be constant.
                    val transient = res.code == ScreenCapture.CODE_THROTTLED || res.code == 3
                    if (manual || !transient) overlay?.showError(res.humanMessage)
                }
                is ScreenCapture.Result.Ok -> {
                    ocr.scaleX = res.scaleX; ocr.scaleY = res.scaleY
                    ocr.originX = res.originX; ocr.originY = res.originY
                    if (rects.isNotEmpty() && !manual) {
                        // The original geometry was validated above; never mix in another screen's rects.
                        ocrByRects(res.bitmap, rects, treeTitle, deliver)
                    } else ocrWholeScreen(res.bitmap, treeTitle, deliver)
                }
            }
        }
    }

    /** One OCR pass per bubble rectangle; each rect becomes exactly one message. */
    private fun ocrByRects(bmp: Bitmap, rects: List<BubbleRect>, title: String?, deliver: (ChatSnapshot) -> Unit) {
        val sx = ocr.scaleX; val sy = ocr.scaleY
        // Screen -> bitmap: drop the window origin first. A window shot does not
        // start at (0,0) in split screen or when it excludes the status bar.
        val ox = ocr.originX; val oy = ocr.originY
        val out = arrayOfNulls<Msg>(rects.size)
        var remaining = rects.size
        rects.forEachIndexed { i, br ->
            val region = Rect(
                ((br.rect.left - ox) * sx).toInt(), ((br.rect.top - oy) * sy).toInt(),
                ((br.rect.right - ox) * sx).toInt(), ((br.rect.bottom - oy) * sy).toInt())
            ocr.recognize(bmp, region) { lines ->
                val text = cleanBubbleText(lines.joinToString(" ") { it.text })
                if (text.isNotEmpty()) out[i] = Msg(br.side, text)
                remaining--
                if (remaining == 0) {
                    runCatching { bmp.recycle() }
                    deliver(ChatSnapshot(title, out.filterNotNull(), source = CaptureSource.BUBBLE_OCR))
                }
            }
        }
    }

    /** Whole screen minus the top bar and the input area, grouped by line gaps. */
    private fun ocrWholeScreen(bmp: Bitmap, treeTitle: String?, deliver: (ChatSnapshot) -> Unit) {
        val region = Rect(0, (bmp.height * TOP_CROP).toInt(), bmp.width, (bmp.height * BOTTOM_CROP).toInt())
        ocr.recognize(bmp, region) { lines ->
            runCatching { bmp.recycle() }
            val msgs = groupOcrLines(lines)
            // A message body is not a contact name; never guess a title from OCR lines.
            deliver(ChatSnapshot(treeTitle, msgs, note = OCR_NOTE, source = CaptureSource.SCREEN_OCR))
        }
    }

    /**
     * OCR lines → "bubbles": a gap larger than 1.2x the previous line's height
     * starts a new one. Side is unknowable from a flat screen read, so every
     * group is filed as the other person (and [OCR_NOTE] says so on the panel).
     */
    private fun groupOcrLines(lines: List<OcrLine>): List<Msg> {
        val usable = lines
            .filter { it.text.isNotBlank() && !PURE_TIME.matches(it.text.trim()) }
            .sortedBy { it.bounds.top }
        val out = ArrayList<Msg>()
        val buf = StringBuilder()
        var prev: OcrLine? = null
        for (l in usable) {
            val p = prev
            if (p != null) {
                val gap = l.bounds.top - p.bounds.bottom
                val lineHeight = maxOf(p.bounds.height(), 1)
                if (gap > lineHeight * 1.2f) {
                    if (buf.isNotEmpty()) { out.add(Msg("other", buf.toString())); buf.setLength(0) }
                }
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(l.text.trim())
            prev = l
        }
        if (buf.isNotEmpty()) out.add(Msg("other", buf.toString()))
        return out
    }

    /** Strip the read receipt and the timestamp Feishu glues onto a bubble. */
    private fun cleanBubbleText(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            for (tail in arrayOf("已读", "未读")) {
                if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true }
            }
            TAIL_TIME.find(t)?.let { t = t.substring(0, it.range.first).trim(); changed = true }
        }
        return t
    }

    /** OCR without reliable speaker identity is copy-only and never recorded in contact history. */
    private fun finishOcrSnapshot(snapshot: ChatSnapshot, pkg: String, manual: Boolean, windowId: Int) {
        if (snapshot.messages.isEmpty()) {
            lastOcrSignature = ""
            if (manual) overlay?.showError("这一屏没认出文字")
            return
        }
        if (!prefs.isAllowed(snapshot.title)) { invalidateConversation(); overlay?.hide(); return }
        if (!manual) {
            val change = SnapshotStability.classify(stableSnapshot, snapshot)
            if (change == SnapshotChange.SAME || change == SnapshotChange.VIEWPORT_ONLY) return
        }
        acceptSnapshot(snapshot, pkg, windowId, manual, capturePolicy.allowAuto(SystemClock.elapsedRealtime()))
    }

    /**
     * Main-thread writes only. Each step revalidates owner, messages, and draft.
     *
     * Does not itself send: with [autoSend] it hands off to [clickSendIfSafe]
     * once the text is verified in the box, and that function applies its own
     * gates. Without [autoSend] the text sits in the composer and the user
     * presses send.
     */
    private fun fillInput(text: String, ticket: ConversationGuard.Ticket, autoSend: Boolean = false) {
        val edit = validatedInput(ticket)
        if (edit == null) { overlay?.toast("会话已变化或身份无法确认，请重新分析；可手动复制"); return }
        if (!edit.text.isNullOrEmpty()) {
            overlay?.toast("输入框已有草稿，未覆盖；请手动复制或先清空草稿"); return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        main.postDelayed(verifyText@{
            val current = validatedInput(ticket) ?: return@verifyText
            when {
                current.text?.toString() == text -> if (autoSend) clickSendIfSafe(ticket) else overlay?.toast("已填入，确认后自己发送")
                !current.text.isNullOrEmpty() -> overlay?.toast("输入框已有内容，已停止；未覆盖草稿")
                else -> {
                    // An unfocused IME may reject SET_TEXT. Focus, then revalidate before paste.
                    current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    main.postDelayed(pasteText@{
                        val fresh = validatedInput(ticket) ?: return@pasteText
                        if (!fresh.text.isNullOrEmpty()) return@pasteText
                        copyToClipboard(text)
                        fresh.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        main.postDelayed(verifyPaste@{
                            val after = validatedInput(ticket) ?: return@verifyPaste
                            if (after.text?.toString() == text) {
                                if (autoSend) clickSendIfSafe(ticket) else overlay?.toast("已填入，确认后自己发送")
                            } else overlay?.toast("已复制，请长按输入框手动粘贴")
                        }, 150)
                    }, 150)
                }
            }
        }, 150)
    }

    /**
     * The only click this app ever performs.
     *
     * Hard constraints 2 ("never press send") and 3 ("never touch money") both
     * come down to this function, so it fails closed at every step: customer
     * mode on, auto-send on, the ticket still current, Douyin in the foreground,
     * the tree fully scanned, exactly one visible clickable node labelled
     * "发送"/"Send", and no payment control anywhere near it. Anything else
     * leaves the text filled in the composer and hands it to a human.
     */
    private fun clickSendIfSafe(ticket: ConversationGuard.Ticket) {
        if (!prefs.customerMode || !prefs.customerAutoSend || !isCurrent(ticket)) return
        val root = foregroundRoot() ?: return
        if (root.packageName?.toString()?.let(::isDouyin) != true) return
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.addLast(root)
        var found: AccessibilityNodeInfo? = null; var guard = 0
        while (stack.isNotEmpty() && guard++ < 8000) {
            val node = stack.removeLast()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotBlank()) text else desc
            if ((label == "发送" || label.equals("Send", true)) && node.isVisibleToUser && node.isClickable) {
                if (found != null) { overlay?.toast("检测到多个发送按钮，已停止自动发送"); return }
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // The node cap can end the walk with entries still queued, and what was
        // scanned then says nothing about the rest of the tree: the "only one
        // send button" proof above is incomplete, so it must not be acted on.
        // findEditable already applies exactly this rule.
        if (stack.isNotEmpty()) { overlay?.toast("界面节点过多，无法确认发送按钮，已停止自动发送"); return }
        val target = found
        if (target == null) { overlay?.toast("未确认发送按钮，已填入但未自动发送"); return }
        if (nearMoneyControls(target)) { overlay?.toast("发送按钮位于支付/礼物类界面内，已停止自动发送"); return }
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            overlay?.toast("固定话术已自动发送")
        } else overlay?.toast("未确认发送按钮，已填入但未自动发送")
    }

    /**
     * Hard constraint 3 says never touch transfer / red packet / payment UI.
     * Nothing in the code knew money existed until now: the only thing between
     * an auto-send click and a Douyin pay / gift / DOU+ sheet was the exact
     * "发送" label match, which is coincidence rather than design.
     *
     * Scope is the candidate's own subtree plus its ancestor chain, NOT the
     * whole tree. Douyin parks a gift entry beside the composer, so a
     * whole-tree scan would refuse every legitimate send. An ancestor chain is
     * layout chrome in the normal case; when the button lives inside a payment
     * or gift sheet, that sheet is on the chain.
     */
    private fun nearMoneyControls(node: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.addLast(node)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 200) {
            val n = stack.removeLast()
            if (hasMoneyWord(n)) return true
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
        var parent = runCatching { node.parent }.getOrNull()
        var up = 0
        while (parent != null && up++ < 12) {
            if (hasMoneyWord(parent)) return true
            val current = parent
            parent = runCatching { current.parent }.getOrNull()
        }
        return false
    }

    private fun hasMoneyWord(n: AccessibilityNodeInfo): Boolean =
        MoneyGuard.mentions(n.text?.toString(), n.contentDescription?.toString())

    private fun validatedInput(ticket: ConversationGuard.Ticket): AccessibilityNodeInfo? {
        if (!connected || !prefs.enabled || !isCurrent(ticket)) return null
        val root = foregroundRoot() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val owner = liveObservation(root) ?: return null
        if (!conversationGuard.canFill(ticket, owner)) return null
        return findEditable(root)?.takeIf {
            it.refresh() && it.isEnabled && !it.isPassword && it.windowId == root.windowId &&
                it.packageName?.toString() == pkg &&
                (it.inputType and InputType.TYPE_MASK_CLASS) !in setOf(
                    InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var found: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable && node.isVisibleToUser) {
                if (found != null) return null // multiple fields: cannot prove which is the message input
                found = node
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return if (stack.isEmpty()) found else null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {
        invalidateConversation()
        overlay?.hide()
    }

    override fun onDestroy() {
        connected = false
        stopEnabledObserver?.invoke()
        stopEnabledObserver = null
        invalidateConversation()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Tear the overlay down and cut its callback so a stale button tap can
        // never call back into this dead instance.
        overlay?.onManualAnalyze = null
        overlay?.onSaveContact = null
        overlay?.onOcrCapture = null
        overlay?.onToggleEnabled = null
        overlay?.hide()
        overlay = null
        runCatching { toneGen?.release() }
        toneGen = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** Whole-screen OCR keeps the middle: no action bar, no input area. */
        private const val TOP_CROP = 0.12f
        private const val BOTTOM_CROP = 0.84f

        /** Said on the panel whenever a snapshot came from flat-screen OCR. */
        private const val OCR_NOTE = "整屏 OCR 未确认说话人；仅供参考、仅复制，不记入联系人历史"

        private val PURE_TIME = Regex("""\d{1,2}[:：]\d{2}""")
        private val TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}$""")

        /** Transient placeholder titles apps show while a chat page is still
         *  connecting/loading — see [isTransientTitle]. Matched as a substring,
         *  case-insensitive, after trimming a trailing ellipsis. */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
