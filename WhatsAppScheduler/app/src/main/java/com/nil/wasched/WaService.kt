package com.nil.wasched

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import android.widget.Toast

/**
 * Drives WhatsApp's UI. All view IDs / labels live in the helpers below so they are easy to adjust
 * when a WhatsApp update changes them (inspect with Android Studio's Layout Inspector or uiautomatorviewer).
 */
class WaService : AccessibilityService() {
    companion object { @Volatile var instance: WaService? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private var pkg = "com.whatsapp"

    override fun onServiceConnected() {
        instance = this
        scope.launch { for (id in queue) process(id) }
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    fun enqueue(id: Long) { queue.trySend(id) }

    private suspend fun process(id: Long) {
        val db = Db.get(this)
        val m = db.msgs().get(id) ?: return
        if (m.status != "PENDING") return
        val g = db.groups().byName(m.groupName)
        if (g == null || !g.enabled) { db.msgs().setStatus(id, "SKIPPED"); return }
        pkg = if (g.business) "com.whatsapp.w4b" else "com.whatsapp"
        val ok = try { run(m, g) } catch (e: Exception) { false }
        db.msgs().setStatus(id, if (ok) "SENT" else "FAILED")
    }

    private suspend fun run(m: Msg, g: WaGroup): Boolean {
        wake()
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launch)
        if (!openChat(g.name)) return false
        return if (m.type == "POLL") {
            sendPoll(m) && (m.url.isBlank() || sendText(m.url)) // URL goes as a separate message after the poll
        } else {
            sendText(listOf(g.prefix, m.message, m.url, g.suffix).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }

    // ---------- flows ----------

    private suspend fun openChat(name: String): Boolean {
        val search = waitFor(15000) {
            byId("menuitem_search") ?: byDesc("Search")
                ?: findNodes { n ->
                    val t = (n.text ?: n.contentDescription ?: n.hintText)?.toString() ?: ""
                    t.contains("Search", true)
                }.firstOrNull()
        }
        if (search == null) { debug("search not found"); return false }
        click(search)
        delay(800)
        val input = waitFor { byId("search_input") ?: editables().firstOrNull() }
        if (input == null) { debug("search box not found"); return false }
        setText(input, name)
        delay(1500)
        val hit = waitFor { findNodes { it.text?.toString() == name && !it.isEditable }.firstOrNull() }
        if (hit == null) { debug("group not found in results"); return false }
        click(hit)
        val opened = waitFor { byId("entry") ?: editables().firstOrNull() }
        if (opened == null) { debug("chat did not open"); return false }
        return true
    }

    private suspend fun sendText(t: String): Boolean {
        val entry = waitFor { byId("entry") ?: editables().firstOrNull() }
        if (entry == null) { debug("message box not found"); return false }
        setText(entry, t)
        delay(500)
        val send = waitFor { byId("send") ?: byDesc("Send") }
        if (send == null) { debug("send button not found"); return false }
        click(send)
        delay(1500)
        return true
    }

    private fun debug(why: String) {
        val seen = findNodes { it.viewIdResourceName != null || it.text != null }
            .take(25)
            .joinToString(" | ") { (it.viewIdResourceName?.substringAfter("id/") ?: "") + ":" + (it.text ?: it.contentDescription ?: "") }
        Toast.makeText(this, "$why\n$seen", Toast.LENGTH_LONG).show()
    }

    private suspend fun sendPoll(m: Msg): Boolean {
        val attach = waitFor { byId("input_attach_button") ?: byDesc("Attach") } ?: return false
        click(attach)
        val poll = waitFor { byText("Poll") } ?: return false
        click(poll)
        val q = waitFor { editables().firstOrNull() } ?: return false
        setText(q, m.pollQuestion)
        m.pollOptions.split("\n").filter { it.isNotBlank() }.forEachIndexed { i, o ->
            val f = waitFor(4000) { editables().getOrNull(i + 1) ?: run { scrollDown(); null } } ?: return false
            setText(f, o)
            delay(250)
        }
        val sw = waitFor(3000) { findNodes { it.className?.toString()?.contains("Switch") == true }.firstOrNull() ?: run { scrollDown(); null } }
        if (sw != null && sw.isChecked != m.allowMultiple) click(sw)
        val send = waitFor { byId("send") ?: byDesc("Send") } ?: return false
        click(send)
        delay(1500)
        return true
    }

    // ---------- helpers ----------

    private fun wake() {
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "wasched:send")
            .acquire(120_000)
    }

    private suspend fun <T : Any> waitFor(timeout: Long = 8000, f: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            f()?.let { return it }
            delay(300)
        }
        return null
    }

    private fun findNodes(p: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (p(n)) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    private fun byId(id: String) = rootInActiveWindow?.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()
    private fun byText(t: String) = findNodes { it.text?.toString() == t }.firstOrNull()
    private fun byDesc(d: String) = findNodes { it.contentDescription?.toString().equals(d, true) }.firstOrNull()
    private fun editables() = findNodes { it.isEditable }
    private fun scrollDown() { findNodes { it.isScrollable }.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }

    private fun click(n: AccessibilityNodeInfo) {
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        x?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun setText(n: AccessibilityNodeInfo, t: String) {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }
}
