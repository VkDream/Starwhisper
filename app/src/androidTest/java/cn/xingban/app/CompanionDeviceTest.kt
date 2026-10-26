package cn.xingban.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.xingban.app.data.*
import cn.xingban.core.*
import cn.xingban.app.integrations.SpeechOutput
import cn.xingban.app.integrations.SpeechRuntime
import cn.xingban.app.ui.ReadingTouchArea
import cn.xingban.app.ui.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import android.speech.tts.TextToSpeech
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.min

/** Runs only in the separately installed fixture application/UID. */
@RunWith(AndroidJUnit4::class)
class CompanionDeviceTest {
    @Test fun r17QqControlsFollowAvailabilityAndFixedProviderSelectionPersists() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        var durable = AppData(settings = AppSettings(musicSource = "qq", musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = durable; override suspend fun save(data: AppData) { durable = data } }
        var modelCalls = 0; var phoneCalls = 0
        val noNetwork = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult { modelCalls++; error("isolated UI has no network") }
        }
        val phone = object : cn.xingban.app.integrations.PhoneRuntime(app) {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(cn.xingban.app.integrations.QqMusicState())
            override fun setForeground(value: Boolean) = Unit
            override fun execute(command: DeviceCommand): cn.xingban.app.integrations.PhoneCommandResult { phoneCalls++; return cn.xingban.app.integrations.PhoneCommandResult(true, "隔离QQ音乐已收到暂停请求。") }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val evidence = File(app.cacheDir, "r17-qq-ui").apply { mkdirs() }
        val lookup = org.json.JSONObject().put("action", "initializing")
        fun button(text: String): androidx.test.uiautomator.UiObject2 {
            lookup.put("action", "button").put("target", text)
            val node = device.wait(Until.findObject(By.text(text)), 5000)
            val clickable = node?.let { generateSequence(it) { parent -> parent.parent }.firstOrNull { parent -> parent.isClickable } }
            lookup.put("nodeExists", node != null).put("clickableFound", clickable != null)
            if (node == null || clickable == null) {
                val reason = if (node == null) "text did not appear within 5000ms" else "text appeared but has no clickable ancestor"
                lookup.put("reason", reason)
                error("QQ controls target '$text': $reason")
            }
            return clickable
        }
        suspend fun reveal(text: String) {
            lookup.put("action", "reveal").put("target", text).put("iterations", 0).put("clickableFound", org.json.JSONObject.NULL)
            var phase = "current"
            var steps = 0
            while (true) {
                val viewport = checkNotNull(device.wait(Until.findObject(By.pkg(app.packageName).clazz("android.widget.ScrollView").scrollable(true)), 5000)).visibleBounds
                val node = device.findObject(By.text(text))
                val found = node?.visibleBounds
                lookup.put("phase", phase).put("iterations", steps).put("viewport", viewport.toShortString()).put("found", found?.toShortString() ?: org.json.JSONObject.NULL).put("nodeExists", node != null)
                if (phase != "reset" && found != null && !found.isEmpty && viewport.contains(found)) return
                if (phase == "current") phase = "reset"
                if (phase == "reset") {
                    val title = device.findObject(By.text("把陪伴调成你的样子"))?.visibleBounds
                    lookup.put("topMarker", title?.toShortString() ?: org.json.JSONObject.NULL)
                    if (title != null && !title.isEmpty && viewport.contains(title)) { phase = "scan"; continue }
                }
                if (steps == 16) break
                val upper = viewport.top + viewport.height() / 4
                val lower = viewport.top + viewport.height() * 3 / 4
                device.swipe(viewport.centerX(), if (phase == "reset") upper else lower, viewport.centerX(), if (phase == "reset") lower else upper, 35)
                steps++
                device.waitForIdle(1000); delay(80)
            }
            error("Required settings control '$text' did not enter viewport; iterations=${lookup.optInt("iterations")}, viewport=${lookup.opt("viewport")}, found=${lookup.opt("found")}, nodeExists=${lookup.opt("nodeExists")}")
        }
        try {
            withContext(kotlinx.coroutines.Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork, phone); holder.put("r17-qq-ui", model) }
            withTimeout(5000) { model.state.first { it.loaded } }
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            checkNotNull(device.wait(Until.findObject(By.desc("更多")), 5000)).click(); button("设置").click()
            reveal("未安装QQ音乐。"); assertTrue(device.hasObject(By.text("打开通知访问设置"))); assertFalse(button("打开QQ音乐").isEnabled)
            withContext(kotlinx.coroutines.Dispatchers.Main) { phone.state.value = cn.xingban.app.integrations.QqMusicState(installed = true, accessGranted = true, listenerConnected = true) }
            assertTrue(device.wait(Until.hasObject(By.text("QQ音乐没有活跃播放会话，请主动打开QQ音乐并播放歌曲。")), 5000))
            reveal("下一首"); assertFalse(button("下一首").isEnabled)
            withContext(kotlinx.coroutines.Dispatchers.Main) { phone.state.value = phone.state.value.copy(sessionAvailable = true, title = "隔离曲目", artist = "隔离作者", playback = cn.xingban.app.integrations.QqPlayback.PLAYING, canPause = true) }
            assertTrue(device.wait(Until.hasObject(By.textContains("隔离曲目")), 5000)); reveal("暂停"); assertTrue(button("暂停").isEnabled); assertFalse(button("下一首").isEnabled)
            button("暂停").click()
            withTimeout(5000) { model.state.first { it.data.messages.any { message -> message.text == "隔离QQ音乐已收到暂停请求。" } } }
            assertEquals(1, phoneCalls); assertEquals(0, modelCalls)
            withContext(kotlinx.coroutines.Dispatchers.Main) { phone.state.value = phone.state.value.copy(canSetVolume = true, volumePercent = 30, volumeScope = DeviceVolumeScope.LOCAL_SHARED) }
            reveal("手机播放音量（系统共享） · 30%")
            assertTrue(device.hasObject(By.desc("手机播放音量（系统共享）")))
            withContext(kotlinx.coroutines.Dispatchers.Main) { phone.state.value = phone.state.value.copy(volumePercent = 45, volumeScope = DeviceVolumeScope.REMOTE_OUTPUT) }
            assertTrue(device.wait(Until.hasObject(By.text("播放设备音量 · 45%")), 5000))
            withContext(kotlinx.coroutines.Dispatchers.Main) { phone.state.value = phone.state.value.copy(canSetVolume = false) }
            assertTrue(device.wait(Until.hasObject(By.text("当前QQ音乐输出不支持设置绝对音量。")), 5000))
            val volumeOwner = generateSequence(checkNotNull(device.findObject(By.desc("播放设备音量")))) { it.parent }.firstOrNull { it.className == "android.widget.SeekBar" }
            assertNotNull(volumeOwner); assertFalse(checkNotNull(volumeOwner).isEnabled)
            reveal("QQ音乐未提供当前队列。"); reveal("OpenCode Go"); button("OpenCode Go").click(); button("保存设置").click()
            withTimeout(5000) { model.state.first { it.data.settings.provider == "OpenCode Go" && it.notice == "设置已保存。" } }
            assertEquals("deepseek-v4.1-flash", durable.settings.model); assertEquals("qq", durable.settings.musicSource)
            check(device.takeScreenshot(File(evidence, "controls.png"))); device.dumpWindowHierarchy(File(evidence, "controls.xml"))
            File(evidence, "receipt.json").writeText(org.json.JSONObject().put("scope", "ISOLATED_FAKE_PHONE_REAL_VM_UI").put("installedPermissionNoSessionAndActionGates", true).put("volumeScopeLabelsAndUnsupportedDisabled", true).put("queueNullAccurate", true).put("providerPersisted", true).put("phoneCalls", phoneCalls).put("modelCalls", modelCalls).put("realQqPlayback", "UNKNOWN").toString(2))
        } catch (error: Throwable) {
            val failure = org.json.JSONObject().put("failureType", error.javaClass.simpleName).put("failureMessage", error.message ?: org.json.JSONObject.NULL).put("lookup", lookup)
            runCatching {
                val current = model.state.value
                val qq = current.qqMusic
                failure.put("musicSource", current.data.settings.musicSource).put("qq", org.json.JSONObject().put("installed", qq.installed).put("accessGranted", qq.accessGranted).put("listenerConnected", qq.listenerConnected).put("sessionAvailable", qq.sessionAvailable).put("playback", qq.playback.name).put("canPlay", qq.canPlay).put("canPause", qq.canPause).put("canPrevious", qq.canPrevious).put("canNext", qq.canNext).put("canSetVolume", qq.canSetVolume).put("volumeScope", qq.volumeScope?.name ?: org.json.JSONObject.NULL))
            }.onFailure { failure.put("stateCaptureErrorType", it.javaClass.simpleName) }
            runCatching { failure.put("pngSaved", device.takeScreenshot(File(evidence, "lookup-failure.png"))) }.onFailure { failure.put("pngErrorType", it.javaClass.simpleName) }
            runCatching { device.dumpWindowHierarchy(File(evidence, "lookup-failure.xml")); failure.put("xmlSaved", true) }.onFailure { failure.put("xmlErrorType", it.javaClass.simpleName) }
            runCatching { File(evidence, "lookup-failure.json").writeText(failure.toString(2)) }.onFailure { capture ->
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("r17EvidenceWriteErrorType", capture.javaClass.simpleName) })
            }
            throw error
        } finally { scenario.close(); withContext(kotlinx.coroutines.Dispatchers.Main) { holder.clear() } }
    }
    @Before fun requireIsolatedFixturePackages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("cn.xingban.app.fixture.xbr2diag27", instrumentation.targetContext.packageName)
        assertEquals("cn.xingban.app.fixture.xbr2diag27.test", instrumentation.context.packageName)
    }

    @Test fun chatHistoryFadesOnlyWhileFollowingAndNeverStealsReadingPosition() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "device-evidence").apply { mkdirs() }
        val messages = (1..20).map { index -> SavedMessage("r11-$index", if (index % 2 == 0) "assistant" else "user", "消息 " + index.toString().padStart(2, '0') + " · 在这里陪你。", 1000L + index) }
        var saved = AppData(messages = messages, settings = AppSettings(musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = saved; override suspend fun save(data: AppData) { saved = data } }
        var requests = 0
        val noNetwork = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult { requests++; error("No network in the r11 UI fixture") }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork); holder.put("r11-home-ui", model) }
        withTimeout(10000) { model.state.first { it.loaded } }
        assertFalse(model.state.value.hasDeepSeekKey)
        @Suppress("UNCHECKED_CAST")
        val ui = ChatViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as kotlinx.coroutines.flow.MutableStateFlow<CompanionUiState>
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val receipt = JSONObject().put("scope", "ISOLATED_SYNTHETIC_UI_IN_MEMORY").put("realApiCalls", 0)
        fun chatBounds() = checkNotNull(device.findObject(By.desc("聊天消息"))).visibleBounds
        fun visible(text: String): Boolean = device.findObject(By.text(text))?.visibleBounds?.let { !it.isEmpty && android.graphics.Rect.intersects(it, chatBounds()) } == true
        suspend fun awaitVisible(text: String) { withTimeout(5000) { while (!visible(text)) delay(50) } }
        data class Anchor(val text: String, val bounds: android.graphics.Rect, val relativeTop: Int)
        fun probeAnchor(): Anchor {
            val viewport = chatBounds()
            return device.findObjects(By.textStartsWith("消息 ")).mapNotNull { node ->
                val bounds = node.visibleBounds; val text = node.text
                if (!bounds.isEmpty && android.graphics.Rect.intersects(bounds, viewport)) Anchor(text, android.graphics.Rect(bounds), bounds.top - viewport.top) else null
            }.minBy { it.bounds.top }
        }
        suspend fun anchor(): Anchor = withTimeout(5000) {
            var previous: Anchor? = null; var stable: Anchor? = null
            while (stable == null) {
                try { val current = probeAnchor(); if (current == previous) stable = current; previous = current }
                catch (_: androidx.test.uiautomator.StaleObjectException) { previous = null }
                if (stable == null) delay(50)
            }
            checkNotNull(stable)
        }
        fun shot(name: String) {
            check(device.takeScreenshot(File(evidence, "$name.png")))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun dragHistory() {
            val bounds = chatBounds()
            check(device.swipe(bounds.centerX(), bounds.top + 25, bounds.centerX(), bounds.bottom - 25, 35))
        }
        fun chatInput() = checkNotNull(generateSequence(checkNotNull(device.findObject(By.desc("聊天输入")))) { it.parent }.firstOrNull { it.className == "android.widget.EditText" })
        try {
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            assertTrue(device.wait(Until.hasObject(By.text("收起")), 5000))
            checkNotNull(device.findObject(By.text("收起"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("展开阅读")), 5000))
            assertTrue(device.wait(Until.hasObject(By.text(messages.last().text)), 10000)); delay(500)
            assertTrue(visible(messages.last().text))
            shot("r11-latest-fade")
            dragHistory(); delay(500)
            assertFalse(visible(messages.last().text))
            val reading = anchor(); val readingText = reading.text; val readingTop = reading.bounds.top
            receipt.put("readingAnchor", readingText).put("readingTopBefore", readingTop)
            shot("r11-history-clear")
            var incoming = SavedMessage("r11-incoming", "assistant", "新到消息 · 不抢走回看。", 9000)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = ui.value.data.messages + incoming), liveReply = "当前流式 · 慢慢说。", localQuestion = "本地新台词") }
            delay(500)
            val afterAnchor = anchor(); assertEquals(readingText, afterAnchor.text)
            val after = afterAnchor.bounds.top
            assertTrue("New content pulled the history anchor", kotlin.math.abs(after - readingTop) <= 3)
            assertFalse(visible(incoming.text))
            assertEquals(1, device.findObjects(By.text("有新回复 · 回到最新")).size)
            receipt.put("readingTopAfterIncoming", after).put("incomingSavedLiveAndLocalDidNotPull", true)
            shot("r11-history-incoming-clear")
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(liveReply = "", localQuestion = "") }
            repeat(5) {
                val bounds = chatBounds()
                check(device.swipe(bounds.centerX(), bounds.bottom - 25, bounds.centerX(), bounds.top + 25, 20))
            }
            delay(500); assertTrue(visible(incoming.text))
            shot("r11-return-latest")
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(busy = true, liveReply = "当前流式 · 始终清楚。", notice = "界面回归提示：保持清晰。") }
            assertTrue(device.wait(Until.hasObject(By.text("停止")), 5000)); delay(500)
            shot("r11-live-notice")
            val untouched = model.state.value.data.character.lastTouchAtMillis
            val overlay = chatBounds()
            device.click(2, overlay.centerY()); delay(150)
            assertEquals(untouched, model.state.value.data.character.lastTouchAtMillis)
            checkNotNull(device.findObject(By.text("停止"))).click()
            val stopped = withTimeout(5000) { model.state.first { value -> !value.busy && value.liveReply.isEmpty() } }
            val stopGone = device.wait(Until.gone(By.text("停止")), 5000)
            val injectedTextGone = device.wait(Until.gone(By.text("当前流式 · 始终清楚。")), 5000)
            receipt.put("stopScope", "VISUAL_FIXTURE_NO_REAL_TURN").put("stopBusy", stopped.busy).put("stopLiveEmpty", stopped.liveReply.isEmpty()).put("stopButtonGone", stopGone).put("injectedTextGone", injectedTextGone)
                .put("messagesAfterVisualStop", org.json.JSONArray(stopped.data.messages.map { JSONObject().put("id", it.id).put("role", it.role).put("status", it.status) }))
            assertTrue(stopGone && injectedTextGone)
            dragHistory(); delay(400)
            val nearBefore = anchor()
            checkNotNull(device.findObject(By.text("展开阅读"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("收起")), 5000))
            val nearExpanded = anchor()
            assertTrue(visible(incoming.text)); assertNotNull(device.findObject(By.textContains("回到最新")))
            shot("r12-near-bottom-expanded")
            checkNotNull(device.findObject(By.text("收起"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("展开阅读")), 5000))
            val nearCollapsed = anchor()
            assertEquals(nearExpanded.text, nearCollapsed.text)
            assertTrue(kotlin.math.abs(nearExpanded.relativeTop - nearCollapsed.relativeTop) <= 3)
            incoming = SavedMessage("r12-near-incoming", "assistant", "近底部新回复 · 保持回看。", 9500)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = ui.value.data.messages + incoming)) }
            val nearAfterNew = anchor()
            assertEquals(nearCollapsed, nearAfterNew); assertFalse(visible(incoming.text))
            assertTrue(device.wait(Until.hasObject(By.text("有新回复 · 回到最新")), 5000)); shot("r12-near-bottom-new-reply")
            receipt.put("nearBottomBefore", nearBefore.text).put("nearBottomExpanded", nearExpanded.text).put("nearBottomCollapsed", nearCollapsed.text).put("nearBottomNewReplyDidNotPull", true)
            repeat(3) { dragHistory() }; delay(400)
            val expandedAnchor = anchor()
            checkNotNull(device.findObject(By.text("展开阅读"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("收起")), 5000))
            assertEquals(expandedAnchor.text, anchor().text)
            shot("r12-expanded-history")
            checkNotNull(device.findObject(By.text("收起"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("展开阅读")), 5000))
            val collapsedAnchor = anchor()
            assertEquals(expandedAnchor.text, collapsedAnchor.text)
            assertTrue(kotlin.math.abs(expandedAnchor.relativeTop - collapsedAnchor.relativeTop) <= 3)
            receipt.put("expandCollapseKeptHistoryAnchor", expandedAnchor.text)
            checkNotNull(device.findObject(By.textContains("回到最新"))).click(); awaitVisible(incoming.text); delay(500)
            assertTrue(device.wait(Until.gone(By.textContains("回到最新")), 5000))
            shot("r12-return-latest")
            dragHistory(); delay(400)
            assertFalse(visible(incoming.text))
            val oldNotice = model.state.value.notice
            val input = checkNotNull(device.findObject(By.desc("聊天输入")))
            val editText = chatInput()
            editText.text = "发送回归"
            withTimeout(5000) { while (chatInput().text != "发送回归") delay(50) }
            receipt.put("sendSelectedInput", JSONObject().put("class", input.className).put("text", input.text)
                .put("editTextClass", editText?.className ?: JSONObject.NULL).put("editTextText", editText?.text ?: JSONObject.NULL))
            val send = checkNotNull(device.wait(Until.findObject(By.text("发送")), 5000))
            val sendParent = withTimeout(5000) {
                var parent = generateSequence(send) { it.parent }.firstOrNull { it.isClickable }
                while (parent?.isEnabled != true) { delay(50); parent = generateSequence(checkNotNull(device.findObject(By.text("发送")))) { it.parent }.firstOrNull { it.isClickable } }
                checkNotNull(parent)
            }
            receipt.put("sendBeforeClick", JSONObject().put("text", send.text).put("class", send.className)
                .put("clickableParentClass", sendParent?.className ?: JSONObject.NULL).put("parentEnabled", sendParent?.isEnabled ?: JSONObject.NULL)
                .put("parentClickable", sendParent?.isClickable ?: JSONObject.NULL).put("parentBounds", sendParent?.visibleBounds?.toShortString() ?: JSONObject.NULL))
            sendParent.click()
            scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            val sent = withTimeout(5000) { model.state.first { it.notice.isNotBlank() && it.notice != oldNotice } }
            awaitVisible(incoming.text); delay(500)
            shot("r11-send-follow")
            receipt.put("sendResumedFollow", "NO_KEY_LOCAL_NOTICE_ONLY").put("sendNoticeBefore", oldNotice).put("sendNoticeAfter", sent.notice).put("stopWorked", true).put("overlayTapDidNotTouchAvatar", true)
            chatInput().text = ""
            withTimeout(5000) { while (chatInput().text != "") delay(50) }
            scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            val longText = "单条长回复保持清楚。这一段可以慢慢往上回看。\n".repeat(30)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = listOf(SavedMessage("r11-long", "assistant", longText, 10000))), busy = false, liveReply = "", notice = "", localQuestion = "") }
            assertTrue(device.wait(Until.hasObject(By.text(longText)), 5000)); delay(500)
            shot("r11-single-long")
            checkNotNull(device.findObject(By.text("展开阅读"))).click(); delay(300)
            chatInput().click()
            val imeShown = withTimeout(5000) {
                var shown = false
                while (!shown) { scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }; if (!shown) delay(50) }
                shown
            }
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(busy = true) }; delay(200)
            val stopBounds = checkNotNull(device.findObject(By.text("停止"))).visibleBounds
            assertFalse(chatBounds().isEmpty); assertFalse(chatInput().visibleBounds.isEmpty); assertFalse(stopBounds.isEmpty)
            shot("r12-long-keyboard")
            receipt.put("expandedLongImeVisible", imeShown).put("expandedInputAndStopReachable", true)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(busy = false) }
            scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            checkNotNull(device.findObject(By.text("收起"))).click(); delay(200)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = emptyList())) }
            delay(150); assertNotNull(device.findObject(By.desc("聊天输入")))
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = listOf(SavedMessage("r11-short", "assistant", "单条短回复同样清楚。", 11000)))) }
            assertTrue(device.wait(Until.hasObject(By.text("单条短回复同样清楚。")), 5000)); delay(250)
            shot("r11-single-short")
            assertEquals(0, requests)
            receipt.put("returnedToLatest", true).put("singleLongAndShortRendered", true).put("emptyListKeptInput", true).put("fakeDialogueCalls", requests).put("pixelAssessment", "SCREENSHOTS_REQUIRE_VISUAL_REVIEW")
        } finally {
            val ending = model.state.value
            receipt.put("ending", JSONObject().put("loaded", ending.loaded).put("hasDeepSeekKey", ending.hasDeepSeekKey).put("notice", ending.notice).put("busy", ending.busy).put("requests", requests))
            try { shot("r11-send-end") } finally {
                File(evidence, "r11-ui-observation.json").writeText(receipt.toString(2))
                scenario.close(); withContext(Dispatchers.Main) { holder.clear() }
            }
        }
    }

    @Test fun settingsDraftsShowSafeStatusLimitsAndSaveResults() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "device-evidence").apply { mkdirs() }
        val source = MemorySource("r12-memory-source", MemorySourceKind.USER_MESSAGE, 1000, "我喜欢喝茶")
        val memory = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single()).state
        var saved = AppData(memory = memory, settings = AppSettings(musicEnabled = false))
        val ordinarySaveEntered = CompletableDeferred<Unit>(); val releaseOrdinarySave = CompletableDeferred<Unit>()
        var ordinaryCommittedMusic: BackgroundMusicTrack? = null
        var rejectOrdinarySave = false
        val store = object : AppStore(app) {
            override suspend fun load() = saved
            override suspend fun save(data: AppData) {
                if (rejectOrdinarySave && data.settings.userAddress == "未保存的新称呼") throw java.io.IOException("isolated settings save rejection")
                if (data.settings.userAddress == "小星" && !ordinarySaveEntered.isCompleted) {
                    ordinarySaveEntered.complete(Unit); releaseOrdinarySave.await(); ordinaryCommittedMusic = data.settings.musicTrack
                }
                saved = data
            }
        }
        var requests = 0
        val noNetwork = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult { requests++; error("No network in the r12 UI fixture") }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork); holder.put("r12-settings-ui", model) }
        withTimeout(10000) { model.state.first { it.loaded } }; assertFalse(model.state.value.hasDeepSeekKey)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val receipt = JSONObject().put("scope", "REMAINING_SETTINGS_BRANCHES_ONLY_IN_MEMORY").put("realApiCalls", 0).put("currentKeyFixture", "NO_KEY_DEFAULT").put("reusedKeyStates", "r12-settings-04").put("reusedNickname41And40", "r12-settings-02").put("navigation", org.json.JSONArray()).put("editTextStaleCause", "UNKNOWN").put("editTextStaleRetries", 0)
        fun step(name: String) { receipt.put("activeStep", name) }
        fun shot(name: String) { check(device.takeScreenshot(File(evidence, "$name.png"))); device.dumpWindowHierarchy(File(evidence, "$name.xml")) }
        fun button(text: String) = checkNotNull(generateSequence(checkNotNull(device.wait(Until.findObject(By.text(text)), 5000))) { it.parent }.firstOrNull { it.isClickable })
        fun freshField(description: String) = device.findObject(By.desc(description))?.let { selected -> generateSequence(selected) { it.parent }.firstOrNull { it.className == "android.widget.EditText" } }
        suspend fun <T : Any> field(description: String, action: (androidx.test.uiautomator.UiObject2) -> T?): T = withTimeout(5000) {
            var result: T? = null
            while (result == null) {
                try { freshField(description)?.let { result = action(it) } }
                catch (_: androidx.test.uiautomator.StaleObjectException) { receipt.put("editTextStaleRetries", receipt.getInt("editTextStaleRetries") + 1) }
                if (result == null) delay(50)
            }
            checkNotNull(result)
        }
        suspend fun fieldText(description: String) = field(description) { it.text }
        suspend fun fieldEnabled(description: String) = field(description) { it.isEnabled }
        suspend fun setField(description: String, value: String, passwordStatus: String? = null) = field(description) { current ->
            current.text = value
            if (passwordStatus != null) {
                if (device.findObject(By.text(passwordStatus))?.visibleBounds?.isEmpty == false) Unit else null
            } else if (freshField(description)?.text == value) Unit else null
        }
        suspend fun hideIme() {
            scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            withTimeout(5000) {
                var hidden = false
                while (!hidden) {
                    var visible: Boolean? = null
                    scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) }
                    hidden = !checkNotNull(visible) { "IME visibility unavailable" }
                    if (!hidden) delay(50)
                }
            }
        }
        suspend fun readyViewport(stage: String): android.graphics.Rect {
            hideIme()
            val window = device.currentPackageName
            check(window == app.packageName) { "Wrong navigation window: $window" }
            checkNotNull(device.findObject(By.pkg(app.packageName).text("保存设置"))) { "Fixture Settings is not visible" }
            val scroll = checkNotNull(device.wait(Until.findObject(By.pkg(app.packageName).clazz("android.widget.ScrollView")), 5000)).visibleBounds
            check(!scroll.isEmpty) { "Empty Settings scroll viewport" }
            val content = android.graphics.Rect()
            scenario.onActivity { activity ->
                check(activity.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(content))
                val bars = checkNotNull(activity.window.decorView.rootWindowInsets).getInsets(android.view.WindowInsets.Type.systemBars())
                content.top = maxOf(content.top, bars.top); content.bottom = minOf(content.bottom, device.displayHeight - bars.bottom)
            }
            val viewport = android.graphics.Rect(scroll)
            check(viewport.intersect(content) && !viewport.isEmpty) { "Settings viewport is outside app content" }
            receipt.getJSONArray("navigation").put(JSONObject().put("stage", stage).put("window", window).put("scroll", scroll.toShortString()).put("content", content.toShortString()).put("viewport", viewport.toShortString()))
            return viewport
        }
        fun swipeViewport(stage: String, viewport: android.graphics.Rect, down: Boolean) {
            val upper = viewport.top + (viewport.height() * 0.30f).toInt(); val lower = viewport.top + (viewport.height() * 0.70f).toInt()
            val start = if (down) upper else lower; val end = if (down) lower else upper
            receipt.getJSONArray("navigation").put(JSONObject().put("stage", stage).put("viewport", viewport.toShortString()).put("startX", viewport.centerX()).put("startY", start).put("endX", viewport.centerX()).put("endY", end))
            check(device.swipe(viewport.centerX(), start, viewport.centerX(), end, 20))
        }
        suspend fun top() {
            repeat(9) {
                val bounds = readyViewport("top-before")
                val title = device.findObject(By.text("把陪伴调成你的样子"))?.visibleBounds
                if (title != null && !title.isEmpty && title.top > bounds.top && title.bottom < bounds.bottom) return
                swipeViewport("top-swipe-down", bounds, true)
                readyViewport("top-after")
            }
            error("Settings title did not enter the scroll viewport")
        }
        suspend fun reveal(description: String, supportingText: String? = null) {
            top()
            repeat(9) {
                val bounds = readyViewport("reveal-before:$description")
                val target = if (supportingText == null) device.findObject(By.desc(description)) else device.findObject(By.text(supportingText))
                val targetBounds = target?.visibleBounds
                if (targetBounds != null && !targetBounds.isEmpty && (supportingText == null || (targetBounds.top > bounds.top && targetBounds.bottom < bounds.bottom))) return
                swipeViewport("reveal-swipe-up:$description", bounds, false)
                readyViewport("reveal-after:$description")
            }
            error("Field not visible: $description")
        }
        suspend fun enabled(text: String, expected: Boolean) = withTimeout(5000) { while (button(text).isEnabled != expected) delay(50) }
        suspend fun nextMusicClick() = withTimeout(5000) {
            var clicked = false
            while (!clicked) {
                val viewport = readyViewport("nextMusic-before")
                val text = device.findObject(By.text("下一首")); val bounds = text?.visibleBounds
                val parent = if (text != null && bounds != null && !bounds.isEmpty && viewport.contains(bounds)) generateSequence(text) { it.parent }.firstOrNull { it.isClickable } else null
                if (parent?.isEnabled == true) {
                    receipt.getJSONArray("navigation").put(JSONObject().put("stage", "nextMusic-click").put("window", device.currentPackageName).put("viewport", viewport.toShortString()).put("startX", bounds!!.centerX()).put("startY", bounds.centerY()).put("endX", bounds.centerX()).put("endY", bounds.centerY()))
                    parent.click(); clicked = true
                } else delay(50)
            }
        }
        try {
            step("OPEN_SETTINGS")
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            checkNotNull(device.wait(Until.findObject(By.desc("更多")), 5000)).click(); button("设置").click()
            assertTrue(device.wait(Until.hasObject(By.text("更改自动保存，与下方普通设置草稿分开。")), 5000))
            enabled("保存设置", false)
            step("ORDINARY_NICKNAME_DRAFT")
            reveal("昵称输入")
            setField("昵称输入", "小星"); hideIme(); top()
            val previousTrack = model.state.value.data.settings.musicTrack
            step("MUSIC_SAVE_WITH_ORDINARY_DRAFT")
            nextMusicClick()
            val changedMusic = withTimeout(5000) { model.state.first { it.data.settings.musicTrack != previousTrack } }.data.settings.musicTrack
            reveal("昵称输入"); assertEquals("小星", fieldText("昵称输入"))
            assertTrue(device.wait(Until.hasObject(By.text("普通设置有未保存更改")), 5000)); shot("r12-music-and-draft")
            step("ORDINARY_SAVE_WAITING_GATE"); button("保存设置").click()
            withTimeout(5000) { ordinarySaveEntered.await() }
            val queuedTrack = changedMusic.next()
            try {
                enabled("保存设置", false)
                step("PENDING_SAVE_NICKNAME_DISABLED")
                assertFalse(fieldEnabled("昵称输入"))
                field("昵称输入") { current ->
                    assertFalse(current.isEnabled)
                    try { current.text = "不应写入" } catch (expected: IllegalStateException) { receipt.put("disabledNicknameActionRejection", expected.javaClass.simpleName) }
                    freshField("昵称输入")?.let { after -> assertFalse(after.isEnabled); assertEquals("小星", after.text); Unit }
                }
                val ownerChecks = org.json.JSONArray(); receipt.put("pendingPreferenceOwners", ownerChecks)
                listOf("温柔偏好", "活泼偏好", "傲娇偏好").forEach { description ->
                    step("PENDING_SAVE_PREFERENCE_DISABLED:$description"); reveal(description)
                    val owner = generateSequence(checkNotNull(device.findObject(By.desc(description)))) { it.parent }.firstOrNull { it.className == "android.widget.SeekBar" }
                    val ownerClass = owner?.className; val ownerEnabled = owner?.isEnabled
                    ownerChecks.put(JSONObject().put("description", description).put("ownerClass", ownerClass ?: JSONObject.NULL).put("ownerEnabled", ownerEnabled ?: JSONObject.NULL))
                    assertNotNull(owner); assertFalse(checkNotNull(ownerEnabled))
                }
                step("PENDING_SAVE_MODEL_DISABLED"); reveal("对话模型"); assertFalse(fieldEnabled("对话模型"))
                step("PENDING_SAVE_KEY_DISABLED"); reveal("DeepSeek API Key"); assertFalse(fieldEnabled("DeepSeek API Key"))
                step("PENDING_SAVE_DELETE_DISABLED"); reveal("删除 DeepSeek Key"); assertFalse(checkNotNull(device.findObject(By.desc("删除 DeepSeek Key"))).isEnabled)
                step("PENDING_SAVE_VOICE_DISABLED"); reveal("语音方式"); listOf("关闭", "系统 TTS", "MiniMax").forEach { assertFalse(button(it).isEnabled) }
                shot("r12-settings-saving")
                step("PENDING_SAVE_MUSIC_QUEUE"); top(); nextMusicClick()
                receipt.put("ordinaryControlsDisabledDuringPendingSave", true).put("disabledNicknameRejectedEdit", true).put("musicControlStayedEnabledDuringPendingSave", true)
            } catch (failure: Throwable) {
                receipt.put("pendingGateFailure", true)
                try { shot("r12-settings-pending-failure") } catch (capture: Exception) { receipt.put("pendingCaptureError", capture.javaClass.simpleName) }
                throw failure
            } finally { releaseOrdinarySave.complete(Unit) }
            step("WAIT_ORDINARY_AND_QUEUED_MUSIC_COMMIT")
            withTimeout(5000) { model.state.first { it.data.settings.userAddress == "小星" && it.data.settings.musicTrack == queuedTrack && it.notice == "设置已保存。" } }
            assertEquals(changedMusic, ordinaryCommittedMusic); assertEquals(queuedTrack, saved.settings.musicTrack)
            step("SAVED_FIELDS_REENABLED"); reveal("昵称输入"); assertTrue(fieldEnabled("昵称输入")); assertEquals("小星", fieldText("昵称输入"))
            assertTrue(device.wait(Until.hasObject(By.text("普通设置无未保存更改")), 5000)); shot("r12-settings-saved")
            step("FAILED_SAVE_RETAINS_DRAFT"); rejectOrdinarySave = true; reveal("昵称输入"); setField("昵称输入", "未保存的新称呼"); hideIme()
            enabled("保存设置", true); button("保存设置").click()
            withTimeout(5000) { model.state.first { it.notice == "设置保存失败，请核对输入。" } }
            assertTrue(device.wait(Until.hasObject(By.text("普通设置有未保存更改")), 5000)); assertEquals("未保存的新称呼", fieldText("昵称输入"))
            assertTrue(fieldEnabled("昵称输入")); assertEquals("小星", saved.settings.userAddress)
            shot("r12-settings-save-failed")
            receipt.put("ordinaryDraftSurvivedMusicSave", true).put("ordinarySaveKeptLatestMusic", true).put("failedSettingsSaveKeptDraftAndDidNotClaimSaved", true)
            step("OPEN_MEMORY_CORRECTION")
            hideIme(); button("记忆").click()
            assertTrue(device.wait(Until.hasObject(By.text(memory.items.single().text)), 5000)); button("更正").click()
            step("MEMORY_501_RETAINED_DISABLED"); setField("记忆更正输入", "星".repeat(501))
            assertTrue(device.wait(Until.hasObject(By.text("501/500 · 超出上限，请缩短后保存")), 5000)); enabled("保存更正", false)
            assertEquals(501, fieldText("记忆更正输入").length); shot("r12-memory-invalid")
            step("MEMORY_500_ENABLED"); setField("记忆更正输入", "星".repeat(500)); enabled("保存更正", true)
            step("MEMORY_CANCEL_PRESERVES_ORIGINAL"); button("取消").click(); assertEquals(memory, model.state.value.data.memory)
            assertTrue(device.wait(Until.hasObject(By.text(memory.items.single().text)), 5000)); shot("r12-memory-cancelled")
            assertEquals(0, requests); assertFalse(cn.xingban.app.data.SecretStore(app).has("deepseek"))
            receipt.put("memory501RetainedAndDisabled500Enabled", true).put("memoryCancelKeptOriginal", true).put("fakeDialogueCalls", requests).put("pixelAssessment", "SCREENSHOTS_REQUIRE_VISUAL_REVIEW")
            step("REMAINING_BRANCHES_PASSED")
        } finally {
            releaseOrdinarySave.complete(Unit)
            receipt.put("endingNotice", model.state.value.notice).put("endingRequests", requests)
            try { shot("r12-settings-end") } finally {
                File(evidence, "r12-settings-observation.json").writeText(receipt.toString(2))
                scenario.close(); withContext(Dispatchers.Main) { holder.clear() }
            }
        }
    }

    @Test fun chatToolbarHasReadableBackgroundInCompactAndHistory() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "device-evidence").apply { mkdirs() }
        val messages = (1..20).map { SavedMessage("r12-toolbar-$it", "assistant", "工具栏展示 $it · 本地合成消息。", 1000L + it) }
        var saved = AppData(messages = messages, activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.READ)), settings = AppSettings(musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = saved; override suspend fun save(data: AppData) { saved = data } }
        var requests = 0
        val noNetwork = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult { requests++; error("No network in the toolbar visual fixture") }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork); holder.put("r12-toolbar-ui", model) }
        withTimeout(10000) { model.state.first { it.loaded } }
        @Suppress("UNCHECKED_CAST")
        val ui = ChatViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as kotlinx.coroutines.flow.MutableStateFlow<CompanionUiState>
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun shot(name: String) { check(device.takeScreenshot(File(evidence, "$name.png"))); device.dumpWindowHierarchy(File(evidence, "$name.xml")) }
        try {
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            assertTrue(device.wait(Until.hasObject(By.text("收起")), 5000))
            checkNotNull(device.findObject(By.text("收起"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("展开阅读")), 5000))
            assertTrue(device.wait(Until.hasObject(By.text(messages.last().text)), 10000)); delay(500)
            shot("r12-toolbar-compact")
            repeat(3) {
                val bounds = checkNotNull(device.findObject(By.desc("聊天消息"))).visibleBounds
                check(device.swipe(bounds.centerX(), bounds.top + 25, bounds.centerX(), bounds.bottom - 25, 30))
            }
            val incoming = SavedMessage("r12-toolbar-new", "assistant", "刚到的新回复 · 本地合成。", 9000)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(data = ui.value.data.copy(messages = ui.value.data.messages + incoming)) }
            assertTrue(device.wait(Until.hasObject(By.text("有新回复 · 回到最新")), 5000))
            checkNotNull(device.findObject(By.text("展开阅读"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("收起")), 5000)); delay(500)
            assertNotNull(device.findObject(By.text("有新回复 · 回到最新")))
            shot("r12-toolbar-history-new")
            assertEquals(0, requests)
            File(evidence, "r12-toolbar-observation.json").writeText(JSONObject().put("scope", "SYNTHETIC_VISUAL_ONLY").put("behaviorReuse", "device-r12-chat-02.log").put("scene", "READ").put("screenshots", 2).put("realApiCalls", 0).put("fakeDialogueCalls", requests).put("pixelAssessment", "SCREENSHOTS_REQUIRE_VISUAL_REVIEW").toString(2))
        } finally { scenario.close(); withContext(Dispatchers.Main) { holder.clear() } }
    }

    @Test fun localMusicCyclesYieldsAndReleasesWithoutSpeaking() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val music = kotlinx.coroutines.flow.MutableStateFlow(false to BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
        val speechStarted = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        var speechStarts = 0
        lateinit var runtime: SpeechRuntime
        var runtimeCreated = false
        var competingFocus: AudioFocusRequest? = null
        val playerField = SpeechRuntime::class.java.getDeclaredField("musicPlayer").apply { isAccessible = true }
        val focusField = SpeechRuntime::class.java.getDeclaredField("musicFocusRequest").apply { isAccessible = true }
        fun player(): MediaPlayer? = playerField.get(runtime) as? MediaPlayer
        suspend fun awaitTrack(track: BackgroundMusicTrack) = withTimeout(10_000) { music.first { it.first && it.second == track } }
        try {
            lateinit var activityModel: ChatViewModel
            scenario.onActivity { activity -> activityModel = ViewModelProvider(activity)[ChatViewModel::class.java] }
            withTimeout(10_000) { activityModel.state.first { it.loaded } }
            withContext(Dispatchers.Main) {
                // Keep the real Activity on top for Android's focus gate; isolate this probe's audio owner.
                val activitySpeech = ChatViewModel::class.java.getDeclaredField("speech").apply { isAccessible = true }.get(activityModel) as SpeechRuntime
                activitySpeech.setForeground(false)
                runtime = SpeechRuntime(context, object : SpeechRuntime.Listener {
                    override fun onPartial(text: String) = Unit
                    override fun onFinal(text: String) = Unit
                    override fun onListeningChanged(listening: Boolean) { assertFalse(listening) }
                    override fun onError(message: String) { errors += message }
                    override fun onMusicChanged(playing: Boolean, track: BackgroundMusicTrack) { music.value = playing to track }
                    override fun onPlayingChanged(playing: Boolean) {
                        if (playing) {
                            assertFalse(runtime.isMusicPlaying)
                            speechStarts++
                            // Cancel at the existing playing callback, before either speech engine runs.
                            runtime.invalidateGeneration()
                            speechStarted.complete(Unit)
                        }
                    }
                })
                runtimeCreated = true
                runtime.configureMusic(true, 0.15, BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
                assertNull(player()) // No player before the real foreground gate.
                runtime.setForeground(true)
            }
            awaitTrack(BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
            for (expected in listOf(BackgroundMusicTrack.AVEC_SOIN, BackgroundMusicTrack.MOONSTONE, BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)) {
                withContext(Dispatchers.Main) {
                    val actual = requireNotNull(player())
                    assertTrue(actual.isPlaying)
                    assertTrue(actual.duration > 1000)
                    actual.seekTo(actual.duration - 150) // Reach the real local completion callback without waiting full tracks.
                }
                awaitTrack(expected)
            }
            withContext(Dispatchers.Main) {
                runtime.beginTurn()
                runtime.enqueueSentence("这句在进入语音引擎之前取消。", SpeechOutput.SystemTts())
            }
            withTimeout(5000) { speechStarted.await() }
            awaitTrack(BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
            withContext(Dispatchers.Main) {
                assertEquals(1, speechStarts)
                assertTrue(requireNotNull(player()).isPlaying)
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
                competingFocus = focus
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(focus))
            }
            withTimeout(5000) { music.first { !it.first } }
            withContext(Dispatchers.Main) {
                assertNull(player()); assertNull(focusField.get(runtime))
                audioManager.abandonAudioFocusRequest(requireNotNull(competingFocus)); competingFocus = null
            }
            delay(250)
            withContext(Dispatchers.Main) {
                assertFalse(runtime.isMusicPlaying)
                runtime.configureMusic(true, 0.2, BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
                assertNull(player()) // A settings/volume refresh must not reclaim lost focus.
                runtime.setForeground(false)
                runtime.setForeground(true)
            }
            awaitTrack(BackgroundMusicTrack.MEDITATION_IMPROMPTU_01)
            withContext(Dispatchers.Main) {
                runtime.configureMusic(true, 0.0, BackgroundMusicTrack.AVEC_SOIN, selectTrack = true)
            }
            awaitTrack(BackgroundMusicTrack.AVEC_SOIN)
            withContext(Dispatchers.Main) {
                assertTrue(requireNotNull(player()).isPlaying)
                assertEquals(originalSystemVolume, audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                runtime.configureMusic(false, 0.0, BackgroundMusicTrack.AVEC_SOIN)
                assertFalse(runtime.isMusicPlaying); assertNull(player()); assertNull(focusField.get(runtime))
                runtime.configureMusic(true, 0.15, BackgroundMusicTrack.AVEC_SOIN)
            }
            awaitTrack(BackgroundMusicTrack.AVEC_SOIN)
            withContext(Dispatchers.Main) {
                runtime.setForeground(false)
                assertFalse(runtime.isMusicPlaying); assertNull(player()); assertNull(focusField.get(runtime))
                runtime.setForeground(true)
                runtime.release() // A pending prepare callback must not restart a released runtime.
            }
            delay(150)
            withContext(Dispatchers.Main) {
                assertFalse(runtime.isMusicPlaying); assertNull(player()); assertNull(focusField.get(runtime)); assertTrue(errors.toString(), errors.isEmpty())
            }
            val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
            File(evidence, "r10-music-observation.json").writeText(JSONObject().put("localMediaPlayer", true).put("completedTracksViaSeek", 3).put("speechYield", "CANCELLED_BEFORE_ENGINE").put("competingFocus", "SAME_APP_DISTINCT_REQUEST").put("automaticFocusReclaim", false).put("foregroundDisableRelease", true).put("systemVolumeUnchanged", true).put("heardAudio", "NOT_CHECKED").toString(2))
        } finally {
            withContext(Dispatchers.Main) {
                competingFocus?.let { audioManager.abandonAudioFocusRequest(it) }
                if (runtimeCreated) runtime.release()
            }
            scenario.close()
        }
    }

    @Test fun musicSettingsPersistAndFollowActualActivityForeground() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = AppStore(context)
        val original = store.load()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: ChatViewModel
        try {
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[ChatViewModel::class.java] }
            withTimeout(10_000) { model.state.first { it.loaded } }
            val initialVolume = if (model.state.value.data.settings.musicVolume == 0.15) 0.16 else 0.15
            withContext(Dispatchers.Main) { model.saveMusicSettings(false, initialVolume, BackgroundMusicTrack.MEDITATION_IMPROMPTU_01, selectTrack = true) }
            withTimeout(10_000) { model.state.first { !it.data.settings.musicEnabled && it.data.settings.musicVolume == initialVolume && it.data.settings.musicTrack == BackgroundMusicTrack.MEDITATION_IMPROMPTU_01 && !it.musicPlaying } }
            val baseline = model.state.value.data
            (device.wait(Until.findObject(By.desc("更多")), 5000) ?: throw AssertionError("更多入口未显示")).click()
            (device.wait(Until.findObject(By.text("设置")), 5000) ?: throw AssertionError("设置入口未显示")).click()
            (device.wait(Until.findObject(By.desc("背景音乐开关")), 5000) ?: throw AssertionError("音乐开关未显示")).click()
            withTimeout(10_000) { model.state.first { it.data.settings.musicEnabled && it.musicPlaying } }
            (device.wait(Until.findObject(By.text("选择曲目 · Meditation Impromptu 01")), 5000) ?: throw AssertionError("选曲入口未显示")).click()
            (device.wait(Until.findObject(By.text("Moonstone")), 5000) ?: throw AssertionError("Moonstone选项未显示")).click()
            withTimeout(10_000) { model.state.first { it.data.settings.musicTrack == BackgroundMusicTrack.MOONSTONE && it.musicTrack == BackgroundMusicTrack.MOONSTONE && it.musicPlaying } }
            (device.wait(Until.findObject(By.text("下一首")), 5000) ?: throw AssertionError("下一首未显示")).click()
            withTimeout(10_000) { model.state.first { it.data.settings.musicTrack == BackgroundMusicTrack.MEDITATION_IMPROMPTU_01 && it.musicTrack == BackgroundMusicTrack.MEDITATION_IMPROMPTU_01 && it.musicPlaying } }
            val slider = device.wait(Until.findObject(By.desc("背景音乐音量")), 5000) ?: throw AssertionError("音乐音量未显示")
            val bounds = slider.visibleBounds
            device.click(bounds.left + bounds.width() / 3, bounds.centerY())
            withTimeout(10_000) { model.state.first { it.data.settings.musicVolume != initialVolume } }
            val savedSettings = model.state.value.data.settings
            assertTrue(savedSettings.musicVolume in 0.0..1.0)
            assertEquals(savedSettings, store.load().settings)
            val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(evidence, "r10-music-settings.png")))
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(5000) { model.state.first { !it.musicPlaying } }
            withContext(Dispatchers.Main) {
                val runtime = ChatViewModel::class.java.getDeclaredField("speech").apply { isAccessible = true }.get(model)
                assertNull(SpeechRuntime::class.java.getDeclaredField("musicPlayer").apply { isAccessible = true }.get(runtime))
                assertNull(SpeechRuntime::class.java.getDeclaredField("musicFocusRequest").apply { isAccessible = true }.get(runtime))
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            withTimeout(10_000) { model.state.first { it.musicPlaying && it.musicTrack == savedSettings.musicTrack } }
            assertEquals(baseline.messages, store.load().messages)
            assertEquals(baseline.memory, store.load().memory)
            val restored = baseline.copy(settings = baseline.settings.copy(musicEnabled = false, musicVolume = 0.35, musicTrack = BackgroundMusicTrack.AVEC_SOIN))
            withContext(Dispatchers.Main) { model.restoreBackup(StateCodec.encode(restored)) }
            withTimeout(5000) { model.state.first { !it.musicPlaying && it.data.settings == restored.settings } }
            scenario.recreate()
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[ChatViewModel::class.java] }
            withTimeout(10_000) { model.state.first { it.loaded && it.data.settings == restored.settings } }
            assertFalse(model.state.value.musicPlaying)
            File(evidence, "r10-music-host-observation.json").writeText(JSONObject().put("uiToggleSelectionNextVolume", true).put("nextSelectionPersisted", true).put("actualActivityStopReleased", true).put("actualActivityStartResumed", true).put("restoreAndRecreateSettings", true).put("chatAndMemoryPreserved", true).put("heardAudio", "NOT_CHECKED").toString(2))
        } finally { scenario.close(); store.save(original) }
    }

    @Test fun readingHitCoordinatesFollowFitAndBreathing() {
        assertEquals(6, CompanionScenes.map { it.drawable }.distinct().size)
        assertEquals(listOf("03_reading", "01_desk_rest", "02_sofa_cushion", "04_window_profile", "06_warm_mug", "18_chair_rest"), CompanionScenes.map { it.id })
        for (scene in CompanionScenes) {
        for ((width, height) in listOf(412f to 842f, 412f to 506f, 842f to 412f)) {
            for (breath in listOf(1f, 1.006f, 1.012f)) {
                fun hit(nx: Float, ny: Float): ReadingTouchArea? {
                    val fit = min(width / 1024f, height / 1536f)
                    val x = (width - 1024f * fit) / 2f + nx * 1024f * fit
                    val y = (height - 1536f * fit) / 2f + ny * 1536f * fit
                    return companionTouchAt(scene, (x - width * 0.5f) * breath + width * 0.5f, (y - height * 0.82f) * breath + height * 0.82f, width, height, breath)
                }
                assertEquals(ReadingTouchArea.HAIR, hit(scene.hair.x, scene.hair.y))
                assertEquals(ReadingTouchArea.CHEEK, hit(scene.cheek.x, scene.cheek.y))
                for (area in ReadingTouchArea.entries.filter { it != ReadingTouchArea.BOOK || scene.id == "03_reading" }) {
                    val anchor = companionTouchAnchor(scene, area, width, height, breath)
                    assertEquals(area, companionTouchAt(scene, anchor.x, anchor.y, width, height, breath))
                }
                assertEquals(if (scene.id == "03_reading") ReadingTouchArea.BOOK else null, hit(0.90f, 0.65f))
                assertNull(hit(0.95f, 0.15f)) // dark image background
                assertNull(hit(0.35f, 0.60f)) // clothing
                assertNull(companionTouchAt(scene, 0f, 0f, width, height, breath)) // Fit letterbox
            }
        }
        }
    }

    @Test fun idleEligibilityRejectsDraftAndEveryBusyState() {
        val ready = CompanionUiState(loaded = true)
        assertEquals(180_000L, COMPANION_IDLE_MILLIS)
        assertTrue(canRotateCompanionScene(ready, ""))
        for (scene in CompanionScenes) assertFalse(canRotateCompanionScene(ready, "", scene.id))
        assertFalse(canRotateCompanionScene(ready.copy(loaded = false), ""))
        assertFalse(canRotateCompanionScene(ready.copy(busy = true), ""))
        assertFalse(canRotateCompanionScene(ready.copy(listening = true), ""))
        assertFalse(canRotateCompanionScene(ready.copy(speaking = true), ""))
        assertFalse(canRotateCompanionScene(ready, "draft"))
        assertFalse(canRotateCompanionScene(ready, " "))
        assertEquals(1_200_000L, COMPANION_QUESTION_MILLIS)
        assertTrue(canAskCompanionQuestion(ready, ""))
        assertFalse(canAskCompanionQuestion(ready.copy(notice = "error"), ""))
        assertFalse(canAskCompanionQuestion(ready, "draft"))
        assertFalse(canAskCompanionQuestion(ready.copy(busy = true), ""))
        assertFalse(canAskCompanionQuestion(ready.copy(listening = true), ""))
        assertFalse(canAskCompanionQuestion(ready.copy(speaking = true), ""))
        for (type in ActivityType.entries) {
            val activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, type))
            val explicit = ready.copy(data = AppData(activity = activity))
            assertFalse(canRotateCompanionScene(explicit, ""))
            assertEquals(type == ActivityType.CHAT, canAskCompanionQuestion(explicit, ""))
            assertTrue(CompanionScenes.contains(companionScene(activity)))
        }
        assertEquals("03_reading", companionScene(CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.READ))).id)
        assertEquals("02_sofa_cushion", companionScene(CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.WATCH))).id)
        assertEquals("01_desk_rest", companionScene(CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.WORK))).id)
    }

    @Test fun manualScenesPreserveActivityDraftAndStreamingAcrossHomeAndRecreation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "r17-ui").apply { mkdirs() }
        val secrets = SecretStore(app)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val initialActivity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.WORK))
        val readingSource = MemorySource("r17-reading-source", MemorySourceKind.USER_MESSAGE, 1000, "我喜欢喝茶")
        val readingMemory = MemoryRules.commit(MemoryState(), MemoryRules.extract(readingSource, 0).single()).state
        var saved = AppData(messages = listOf(SavedMessage("r17-reading-existing", "assistant", "保留已有消息。", 1000)), memory = readingMemory, activity = initialActivity, settings = AppSettings(musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = saved; override suspend fun save(data: AppData) { saved = data } }
        val fake = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult {
                check(purpose == RequestPurpose.CHAT)
                requests.incrementAndGet()
                onDelta("我还在这里陪你。")
                entered.complete(Unit)
                release.await()
                return cn.xingban.app.integrations.DialogueResult(true, activity = ActivityCommand(ActivityAction.SWITCH, ActivityType.WATCH))
            }
        }
        lateinit var model: ChatViewModel
        var modelCreated = false
        var injected = false
        // Retain the fake VM under the production key before MainActivity creates its real Compose tree.
        // Recreate then exercises the same rememberSaveable call site, not a replacement test composition.
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.PRE_ON_CREATE && !injected) {
                activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:cn.xingban.app.ChatViewModel", model)
                injected = true
            }
        }
        var scenario: ActivityScenario<MainActivity>? = null
        fun avatar(scene: CompanionScene) = checkNotNull(device.wait(Until.findObject(By.descStartsWith("浅，${scene.label}")), 5000))
        fun button(text: String) = checkNotNull(generateSequence(checkNotNull(device.wait(Until.findObject(By.text(text)), 5000))) { it.parent }.firstOrNull { it.isClickable })
        fun select(scene: CompanionScene?) {
            checkNotNull(device.wait(Until.findObject(By.descStartsWith("画面：")), 5000)).click()
            button(scene?.label ?: "自动").click()
            avatar(scene ?: companionScene(model.state.value.data.activity))
        }
        fun openPage(label: String) {
            checkNotNull(device.findObject(By.desc("更多"))).click()
            button(label).click()
        }
        fun assertReadingExpanded(expanded: Boolean) {
            assertTrue(device.wait(Until.hasObject(By.text(if (expanded) "收起" else "展开阅读")), 5000))
            assertFalse(device.hasObject(By.text(if (expanded) "展开阅读" else "收起")))
        }
        fun visitPageAndReturn(label: String, title: String, expanded: Boolean) {
            openPage(label)
            assertTrue(device.wait(Until.hasObject(By.text(title)), 5000))
            button("陪伴").click()
            assertReadingExpanded(expanded)
        }
        try {
            secrets.write("deepseek", "fixture-not-real-key")
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); modelCreated = true }
            withTimeout(10_000) { model.state.first { it.loaded } }
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            avatar(CompanionScenes[1]); assertTrue(injected)
            val readingBaseline = model.state.value.data
            assertReadingExpanded(true)
            visitPageAndReturn("设置", "把陪伴调成你的样子", true)
            visitPageAndReturn("记忆", "一起记得的事", true)
            button("收起").click(); assertReadingExpanded(false)
            visitPageAndReturn("设置", "把陪伴调成你的样子", false)
            visitPageAndReturn("记忆", "一起记得的事", false)
            checkNotNull(scenario).recreate()
            assertReadingExpanded(false)
            button("展开阅读").click(); assertReadingExpanded(true)
            checkNotNull(scenario).recreate()
            assertReadingExpanded(true)
            checkNotNull(scenario).onActivity { activity -> assertSame(model, ViewModelProvider(activity)[ChatViewModel::class.java]) }
            assertEquals(readingBaseline.messages, model.state.value.data.messages)
            assertEquals(readingBaseline.memory, model.state.value.data.memory)
            assertEquals(0, requests.get())
            button("收起").click(); assertReadingExpanded(false)
            checkNotNull(device.findObject(By.desc("画面：自动"))).click()
            assertTrue(device.takeScreenshot(File(evidence, "manual-entry.png")))
            device.pressBack()
            for (scene in CompanionScenes) {
                select(scene)
                assertEquals(initialActivity, model.state.value.data.activity)
                assertEquals(readingBaseline.messages, model.state.value.data.messages)
            }
            checkNotNull(device.findObject(By.desc("聊天输入"))).click()
            val draft = "draft stays while choosing"
            checkNotNull(device.findObject(By.clazz("android.widget.EditText"))).text = draft
            device.pressBack()
            select(CompanionScenes[4])
            assertTrue(device.hasObject(By.text(draft)))
            withContext(Dispatchers.Main) { model.send("隔离流式切图测试") }
            withTimeout(10_000) { entered.await() }
            select(CompanionScenes[3])
            assertTrue(model.state.value.busy)
            assertEquals("我还在这里陪你。", model.state.value.liveReply)
            assertEquals(initialActivity, model.state.value.data.activity)
            assertTrue(device.hasObject(By.text(draft)))
            assertEquals(1, requests.get())
            assertTrue(device.takeScreenshot(File(evidence, "manual-during-fake-stream.png")))
            release.complete(Unit)
            withTimeout(10_000) { model.state.first { !it.busy && it.data.activity.type == ActivityType.WATCH } }
            avatar(CompanionScenes[3]) // The completed activity result cannot replace manual display.
            assertTrue(model.state.value.data.messages.all { it.status == "completed" })
            assertEquals(1, requests.get())
            select(null); avatar(CompanionScenes[2])
            val afterReply = model.state.value.data.activity
            select(CompanionScenes[5])
            assertEquals(afterReply, model.state.value.data.activity)
            val avatarBounds = avatar(CompanionScenes[5]).visibleBounds
            val anchor = companionTouchAnchor(CompanionScenes[5], ReadingTouchArea.HAIR, avatarBounds.width().toFloat(), avatarBounds.height().toFloat(), 1f)
            device.click((avatarBounds.left + anchor.x).toInt(), (avatarBounds.top + anchor.y).toInt())
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("本地互动：")), 1000))
            openPage("设置")
            assertTrue(device.wait(Until.hasObject(By.text("把陪伴调成你的样子")), 5000))
            button("陪伴").click(); avatar(CompanionScenes[5])
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            openPage("记忆")
            assertTrue(device.wait(Until.hasObject(By.text("一起记得的事")), 5000))
            button("陪伴").click(); avatar(CompanionScenes[5])
            checkNotNull(scenario).recreate()
            avatar(CompanionScenes[5])
            assertTrue(device.hasObject(By.desc("画面：椅背休息")))
            assertEquals(afterReply, model.state.value.data.activity)
            assertEquals(1, requests.get())
            File(evidence, "manual-receipt.json").writeText(JSONObject().put("scope", "IN_MEMORY_APPSTORE_REAL_VM_FAKE_CLIENT").put("readingDefaultExpanded", true).put("readingChoiceSurvivesSettingsAndMemory", true).put("readingChoiceSurvivesRecreate", true).put("readingNavigationPreservesMessagesAndMemory", true).put("sixScenes", true).put("draftPreservedDuringSelection", true).put("streamNotCancelled", true).put("activityCannotOverrideManual", true).put("autoUsesCurrentActivity", true).put("navigationAndRecreatePreserved", true).put("fakeRequests", requests.get()).put("realApiCalls", 0).put("pixelAssessment", "SCREENSHOTS_REQUIRE_VISUAL_REVIEW").toString(2))
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback) }
            scenario?.close()
            if (!injected && modelCreated) withContext(Dispatchers.Main) { ViewModelStore().apply { put("unadopted-r16", model); clear() } }
            secrets.write("deepseek", "")
        }
    }

    @Test fun shortenedQuestionTimerShowsHomeBubbleOnceAndRestartsOnlyOnInteraction() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "device-evidence").apply { mkdirs() }
        var saved = AppData()
        val store = object : AppStore(app) { override suspend fun load() = saved; override suspend fun save(data: AppData) { saved = data } }
        var requests = 0
        val noNetwork = object : cn.xingban.app.integrations.DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<cn.xingban.app.integrations.DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): cn.xingban.app.integrations.DialogueResult { requests++; error("No requests allowed") }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork); holder.put("question-ui", model) }
        withTimeout(10000) { model.state.first { it.loaded } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var revision by mutableLongStateOf(0)
        var eligible by mutableStateOf(false)
        val asks = java.util.concurrent.atomic.AtomicInteger()
        try {
            scenario.onActivity { activity ->
                model.foreground()
                activity.setContent {
                    CompanionApp(model, {}, {}, {})
                    CompanionIdleQuestion(eligible, revision, { asks.incrementAndGet(); model.showIdleQuestion() }, idleMillis = 600)
                }
            }
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("浅，低头读书")), 10000))
            withContext(Dispatchers.Main) { eligible = true }
            // This independent scene update must not restart the question effect.
            withContext(Dispatchers.Main) { model.rotateCompanionScene() }
            assertTrue(device.wait(Until.hasObject(By.text("还在忙呀？要不要和我说两句。")), 5000))
            assertEquals(1, asks.get()); assertTrue(saved.messages.isEmpty()); assertTrue(saved.memory.items.isEmpty())
            assertTrue(device.takeScreenshot(File(evidence, "r7-question-shortened-home.png")))
            delay(900); assertEquals(1, asks.get())
            withContext(Dispatchers.Main) { eligible = false; model.userInteraction() }
            assertTrue(device.wait(Until.gone(By.text("还在忙呀？要不要和我说两句。")), 2000))
            delay(700); assertEquals(1, asks.get())
            withContext(Dispatchers.Main) { revision++; eligible = true }
            assertTrue(device.wait(Until.hasObject(By.text("还在忙呀？要不要和我说两句。")), 5000))
            assertEquals(2, asks.get()); assertEquals(0, requests)
            File(evidence, "r7-question-shortened.json").writeText(JSONObject().put("productionThresholdMillis", COMPANION_QUESTION_MILLIS).put("fixtureThresholdMillis", 600).put("clock", "SHORTENED_FIXTURE_NOT_REAL_20_MINUTES").put("asks", asks.get()).put("realRequests", requests).toString(2))
        } finally { scenario.close(); withContext(Dispatchers.Main) { holder.clear() } }
    }

    @Test fun sixSceneImagesUseTheirOwnHairAndCheekHitAreas() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val touches = java.util.concurrent.atomic.AtomicInteger()
        try {
            for (scene in CompanionScenes) {
                scenario.onActivity { activity -> activity.setContent { Companion2DAvatar(Expression.NEUTRAL, false, { touches.incrementAndGet() }, Modifier.fillMaxSize(), scene) } }
                val avatar = device.wait(Until.findObject(By.descStartsWith("浅，${scene.label}")), 5000) ?: throw AssertionError("${scene.id}未显示")
                val bounds = avatar.visibleBounds
                val fit = min(bounds.width() / 1024f, bounds.height() / 1536f)
                for ((region, area) in listOf(scene.hair to ReadingTouchArea.HAIR, scene.cheek to ReadingTouchArea.CHEEK)) {
                    val before = touches.get()
                    val x = bounds.left + (bounds.width() - 1024f * fit) / 2f + region.x * 1024f * fit
                    val y = bounds.top + (bounds.height() - 1536f * fit) / 2f + region.y * 1536f * fit
                    device.click(x.toInt(), y.toInt())
                    assertTrue("${scene.id} ${area.name}未命中", device.wait(Until.hasObject(By.text(area.reply)), 1500))
                    assertEquals(before + 1, touches.get())
                    val bubble = checkNotNull(device.findObject(By.desc("本地互动：${area.reply}"))).visibleBounds
                    assertTrue("短句应在人物层可见范围内", bounds.contains(bubble))
                    assertTrue("短句应靠近触点", kotlin.math.abs(bubble.centerY() - y) < 150 * context.resources.displayMetrics.density)
                    assertTrue(device.takeScreenshot(File(evidence, "r6-${scene.id}-${area.name.lowercase()}.png")))
                    assertTrue(device.wait(Until.gone(By.text(area.reply)), 3000))
                }
            }
        } finally { scenario.close(); AppStore(context).save(AppData()) }
    }

    @Test fun touchRippleRepeatsDuringCooldownAndClearsOnSceneSizeAndBackground() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.cacheDir, "r16-interaction").apply { mkdirs() }
        val previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        val touches = java.util.concurrent.atomic.AtomicInteger()
        var scene by mutableStateOf(CompanionScenes.first())
        var compact by mutableStateOf(false)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun avatar() = checkNotNull(device.wait(Until.findObject(By.descStartsWith("浅，${scene.label}")), 5000))
        fun point(area: ReadingTouchArea): Pair<Int, Int> {
            val bounds = avatar().visibleBounds
            val anchor = companionTouchAnchor(scene, area, bounds.width().toFloat(), bounds.height().toFloat(), 1f)
            return (bounds.left + anchor.x).toInt() to (bounds.top + anchor.y).toInt()
        }
        fun tap(point: Pair<Int, Int>) { check(device.click(point.first, point.second)) }
        fun rippleNear(point: Pair<Int, Int>) {
            val ring = checkNotNull(device.wait(Until.findObject(By.desc("触点光圈")), 300)).visibleBounds
            val density = context.resources.displayMetrics.density
            val diameter = (72f * density + .5f).toInt()
            val bounds = avatar().visibleBounds
            val bubble = device.findObject(By.descStartsWith("本地互动："))?.visibleBounds
            // Both probes are away from edges, with the bubble above and the full Canvas width visible.
            // Accessibility can clip its transparent upper area; the unobstructed bottom preserves its center.
            val awayFromEdges = point.first - diameter / 2f >= bounds.left && point.first + diameter / 2f <= bounds.right && point.second - diameter / 2f >= bounds.top && point.second + diameter / 2f <= bounds.bottom
            val bubbleAbove = bubble != null && bubble.bottom <= point.second - 18f * density + 3f
            val fullWidth = ring.width() == diameter
            val containsTap = ring.contains(point.first, point.second)
            val dx = ring.centerX() - point.first; val dy = ring.bottom - diameter / 2f - point.second
            val validGeometry = awayFromEdges && bubbleAbove && fullWidth && containsTap
            val outside = !validGeometry || kotlin.math.abs(dx) > 3 || kotlin.math.abs(dy) > 3f
            val snapshot = File(evidence, "touch-ripple-offset-${android.os.SystemClock.elapsedRealtime()}.png")
            val captured = if (outside) device.takeScreenshot(snapshot) else null
            val details = "expectedTap=$point actualRing=$ring dx=$dx dy=$dy diameter=$diameter avatar=$bounds bubble=$bubble awayFromEdges=$awayFromEdges bubbleAbove=$bubbleAbove fullWidth=$fullWidth containsTap=$containsTap" + if (outside) " failureScreenshot=$snapshot captured=$captured" else ""
            assertTrue(details, validGeometry)
            assertTrue(details, kotlin.math.abs(dx) <= 3)
            assertTrue(details, kotlin.math.abs(dy) <= 3f)
        }
        try {
            scenario.onActivity { activity -> activity.setContent {
                Box(Modifier.fillMaxSize()) { Companion2DAvatar(Expression.NEUTRAL, false, { touches.incrementAndGet() }, if (compact) Modifier.fillMaxWidth().height(420.dp) else Modifier.fillMaxSize(), scene) }
            } }
            val hair = point(ReadingTouchArea.HAIR); val cheek = point(ReadingTouchArea.CHEEK)
            tap(hair)
            tap(cheek)
            tap(cheek) // Three real pointer events, including the same point, inside the business cooldown.
            rippleNear(cheek)
            assertEquals(1, touches.get())
            assertTrue(device.hasObject(By.text(ReadingTouchArea.HAIR.reply)))
            assertFalse(device.hasObject(By.text(ReadingTouchArea.CHEEK.reply)))
            assertTrue(device.takeScreenshot(File(evidence, "touch-nearby-cooldown.png")))
            withContext(Dispatchers.Main) { scene = CompanionScenes[4] }
            avatar()
            assertFalse(device.hasObject(By.desc("触点光圈")))
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            delay(700)
            val warmHair = point(ReadingTouchArea.HAIR)
            tap(warmHair); rippleNear(warmHair)
            withContext(Dispatchers.Main) { compact = true }
            withTimeout(3000) { while (avatar().visibleBounds.height() > 420 * context.resources.displayMetrics.density + 4) delay(30) }
            assertFalse(device.hasObject(By.desc("触点光圈")))
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            delay(700)
            // Exercise a real accessibility action and verify its scene anchor produces local feedback.
            fun findAvatar(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.contentDescription?.startsWith("浅，${scene.label}") == true) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findAvatar(child)?.let { return it } }
                return null
            }
            val node = checkNotNull(findAvatar(checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)))
            val action = checkNotNull(node.actionList.firstOrNull { it.label?.toString() == ReadingTouchArea.CHEEK.action })
            assertTrue(node.performAction(action.id))
            assertTrue(device.wait(Until.hasObject(By.text(ReadingTouchArea.CHEEK.reply)), 1000))
            assertTrue(device.takeScreenshot(File(evidence, "accessibility-anchor.png")))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            avatar()
            assertFalse(device.hasObject(By.desc("触点光圈")))
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            File(evidence, "touch-receipt.json").writeText(JSONObject().put("scope", "ISOLATED_AVATAR_REAL_POINTER_AND_ACCESSIBILITY").put("businessCooldownMillis", 650).put("immediateVisualDuringCooldown", true).put("samePointAcknowledged", true).put("sceneSizeBackgroundClear", true).put("accessibilityUsesSceneAnchor", true).put("realApiCalls", 0).put("pixelAssessment", "SCREENSHOTS_REQUIRE_VISUAL_REVIEW").toString(2))
        } finally { scenario.close(); Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout }
    }

    @Test fun real180SecondIdleRotationRestartsAfterInteractionAndForegroundReturn() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = AppStore(context)
        store.save(AppData(reunionDay = java.time.LocalDate.now().toString(), reunionShownAt = System.currentTimeMillis()))
        val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
        val receipt = JSONObject().put("thresholdMillis", COMPANION_IDLE_MILLIS).put("clock", "REAL_ELAPSED_REALTIME").put("realRequests", 0)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("浅，低头读书")), 10000))
            device.findObject(By.desc("聊天输入")).click()
            device.findObject(By.clazz("android.widget.EditText")).text = "draft blocks idle"
            assertTrue(device.wait(Until.hasObject(By.text("draft blocks idle")), 3000))
            device.findObject(By.clazz("android.widget.EditText")).text = ""
            device.pressBack()
            device.findObject(By.desc("更多")).click()
            (device.wait(Until.findObject(By.text("设置")), 5000) ?: throw AssertionError("设置菜单未出现")).click()
            assertTrue(device.wait(Until.hasObject(By.text("把陪伴调成你的样子")), 5000))
            device.findObject(By.text("陪伴")).click()
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("浅，低头读书")), 5000))
            device.pressHome()
            delay(500)
            context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val avatar = device.wait(Until.findObject(By.descStartsWith("浅，低头读书")), 10000) ?: throw AssertionError("前台返回后姿态丢失")
            val returnedAt = android.os.SystemClock.elapsedRealtime()
            receipt.put("returnedAtElapsed", returnedAt)
            delay(15_000)
            val bounds = avatar.visibleBounds
            val resetStart = android.os.SystemClock.elapsedRealtime()
            device.click((bounds.left + bounds.width() * .95f).toInt(), (bounds.top + bounds.height() * .2f).toInt())
            receipt.put("interactionAtElapsed", resetStart)
            assertTrue(device.takeScreenshot(File(evidence, "r6-idle-before.png")))
            delay(170_000)
            assertTrue("交互应推迟原先的闲置截止时间", device.hasObject(By.descStartsWith("浅，低头读书")))
            receipt.put("stillReadingAfterOriginalDeadlineMillis", android.os.SystemClock.elapsedRealtime() - returnedAt)
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("浅，桌边托腮")), 20_000))
            val rotatedAt = android.os.SystemClock.elapsedRealtime()
            receipt.put("rotatedAtElapsed", rotatedAt).put("elapsedSinceInteractionMillis", rotatedAt - resetStart)
            assertTrue("不能提前到180秒之前换图", rotatedAt - resetStart >= COMPANION_IDLE_MILLIS)
            assertTrue(device.hasObject(By.desc("聊天输入")))
            assertTrue(device.takeScreenshot(File(evidence, "r6-idle-after.png")))
            receipt.put("result", "PASS")
        } finally {
            File(evidence, "r6-real-idle.json").writeText(receipt.toString(2))
            scenario.close(); store.save(AppData())
        }
    }

    @Test fun readingTouchRegionsAreTemporaryAndOverlayDoesNotPassThrough() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.setOrientationNatural()
        val previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        val store = AppStore(context)
        store.save(AppData(reunionDay = java.time.LocalDate.now().toString(), reunionShownAt = System.currentTimeMillis()))
        val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
        val receipt = JSONObject().put("realRequests", 0)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val avatar = device.wait(Until.findObject(By.descStartsWith("浅，低头读书")), 10000) ?: throw AssertionError("读书图未显示")
            val bounds = avatar.visibleBounds
            val baseline = store.load()
            fun tap(nx: Float, ny: Float) {
                val fit = min(bounds.width() / 1024f, bounds.height() / 1536f)
                val x = bounds.left + (bounds.width() - 1024f * fit) / 2f + nx * 1024f * fit
                val y = bounds.top + (bounds.height() - 1536f * fit) / 2f + ny * 1536f * fit
                device.click(x.toInt(), y.toInt())
            }
            tap(0.51f, 0.145f)
            tap(0.57f, 0.343f) // no intervening idle/query: must stay in the 650ms cooldown
            assertTrue(device.wait(Until.hasObject(By.text(ReadingTouchArea.HAIR.reply)), 1000))
            assertFalse(device.hasObject(By.text(ReadingTouchArea.CHEEK.reply)))
            assertTrue(device.takeScreenshot(File(evidence, "r5-hair-cooldown.png")))
            assertTrue(device.wait(Until.gone(By.text(ReadingTouchArea.HAIR.reply)), 3000))
            tap(0.57f, 0.343f)
            assertTrue(device.wait(Until.hasObject(By.text(ReadingTouchArea.CHEEK.reply)), 1000))
            assertTrue(device.takeScreenshot(File(evidence, "r5-cheek.png")))
            assertTrue(device.wait(Until.gone(By.text(ReadingTouchArea.CHEEK.reply)), 3000))
            tap(0.90f, 0.65f)
            assertTrue("竖屏可见书页应可点击", device.wait(Until.hasObject(By.text(ReadingTouchArea.BOOK.reply)), 1000))
            assertTrue(device.takeScreenshot(File(evidence, "r5-book.png")))
            assertTrue(device.wait(Until.gone(By.text(ReadingTouchArea.BOOK.reply)), 3000))
            val afterRegions = store.load()
            tap(0.95f, 0.15f)
            tap(0.35f, 0.60f)
            val inputBounds = device.findObject(By.desc("聊天输入")).visibleBounds
            device.click(bounds.centerX(), inputBounds.top - 10) // empty padding inside the chat overlay
            delay(750)
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            assertEquals("背景/未标部位/聊天覆盖层不能触摸人物", afterRegions.character, store.load().character)
            assertEquals(baseline.messages, store.load().messages)
            assertEquals(baseline.memory, store.load().memory)
            device.findObject(By.desc("聊天输入")).click()
            device.findObject(By.clazz("android.widget.EditText")).text = "local input only"
            assertTrue(device.wait(Until.hasObject(By.text("local input only")), 3000))
            assertTrue(device.takeScreenshot(File(evidence, "r5-keyboard.png")))
            device.pressBack()
            device.pressHome()
            val launcherIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            receipt.put("returnLauncher", JSONObject().put("targetPackage", context.packageName).put("component", launcherIntent.component?.flattenToString() ?: JSONObject.NULL).put("flags", launcherIntent.flags).put("intentPackage", launcherIntent.`package` ?: JSONObject.NULL))
            val launchCommand = "am start -W -n ${checkNotNull(launcherIntent.component).flattenToString()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10000000"
            val launchOutput = try { device.executeShellCommand(launchCommand) } catch (failure: Exception) { receipt.put("returnLaunchException", failure.toString()); "" }
            val launchSucceeded = launchOutput.lineSequence().any { it.trim() == "Status: ok" }
            receipt.put("returnLaunchCommand", launchCommand).put("returnLaunchOutput", launchOutput).put("returnLaunchSucceeded", launchSucceeded)
            val returnedReading = device.wait(Until.hasObject(By.descStartsWith("浅，低头读书")), 10000)
            receipt.put("returnReadingWithin10Seconds", returnedReading)
            if (!launchSucceeded || !returnedReading) {
                val failurePng = File(evidence, "r5-return-failure.png")
                val failureXml = File(evidence, "r5-return-failure.xml")
                receipt.put("returnFailurePng", failurePng.name).put("returnFailurePngSaved", device.takeScreenshot(failurePng))
                device.dumpWindowHierarchy(failureXml)
                receipt.put("returnFailureXml", failureXml.name).put("returnFailureCurrentPackageName", device.currentPackageName ?: JSONObject.NULL)
                    .put("returnFailureAvatarDescriptions", org.json.JSONArray(device.findObjects(By.descStartsWith("浅，")).map { it.contentDescription }))
                File(evidence, "r5-reading-touch.json").writeText(receipt.toString(2))
            }
            assertTrue("Shell launcher did not report Status: ok: $launchOutput", launchSucceeded)
            assertTrue(returnedReading)
            assertTrue(device.hasObject(By.desc("聊天输入")))
            assertFalse(device.hasObject(By.descStartsWith("本地互动：")))
            assertTrue(device.takeScreenshot(File(evidence, "r5-returned-home.png")))
            receipt.put("result", "PASS").put("fitAndBreathGui", "portrait").put("regions", "HAIR,CHEEK,BOOK").put("cooldownMillis", 650).put("feedbackExpires", true).put("messagesAndMemoryUnchanged", true).put("backgroundClothingAndOverlayIgnored", true).put("focusedInputAndForegroundReturn", true)
        } finally {
            File(evidence, "r5-reading-touch.json").writeText(receipt.toString(2))
            scenario.close()
            Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout
            device.unfreezeRotation()
            store.save(AppData()) // only this fixture UID
        }
    }

    @Test fun systemChineseTtsCompletesAndStopsWithoutDialogueRequest() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receipt = JSONObject().put("fixturePackage", context.packageName).put("paidRequests", 0)
        val evidence = File(context.cacheDir, "device-evidence").apply { mkdirs() }
        var probe: TextToSpeech? = null
        var runtime: SpeechRuntime? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            // Audio focus hardening denies focus while the app process is backgrounded (procState != TOP); bring the activity forward first.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val initialized = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) { probe = TextToSpeech(context) { initialized.complete(it == TextToSpeech.SUCCESS) } }
            assertTrue(withTimeout(15000) { initialized.await() })
            withContext(Dispatchers.Main) {
                val engine = requireNotNull(probe)
                val availability = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                receipt.put("chineseAvailability", availability).put("voiceLanguage", engine.voice?.locale?.language ?: "NONE").put("voiceRequiresNetwork", engine.voice?.isNetworkConnectionRequired)
                assertTrue("Chinese language data unavailable; receipt preserved", availability >= 0)
                assertEquals("zh", engine.voice?.locale?.language)
                engine.shutdown(); probe = null
            }
            var started = CompletableDeferred<Unit>()
            var finished = CompletableDeferred<Unit>()
            val errors = mutableListOf<String>()
            val listener = object : SpeechRuntime.Listener {
                override fun onPartial(text: String) = Unit
                override fun onFinal(text: String) = Unit
                override fun onListeningChanged(listening: Boolean) = Unit
                override fun onPlayingChanged(playing: Boolean) { if (playing) started.complete(Unit) else finished.complete(Unit) }
                override fun onError(message: String) { errors += message; finished.complete(Unit) }
            }
            withContext(Dispatchers.Main) { runtime = SpeechRuntime(context, listener) }
            assertTrue(withTimeout(15000) { requireNotNull(runtime).awaitSystemTtsReady() })
            withContext(Dispatchers.Main) { requireNotNull(runtime).beginTurn(); requireNotNull(runtime).enqueueSentence("你好，我是浅。这是隔离的中文语音验证。", SpeechOutput.SystemTts()) }
            withTimeout(20000) { started.await(); finished.await() }
            withContext(Dispatchers.Main) {
                receipt.put("completedErrorCount", errors.size).put("completedPlaying", requireNotNull(runtime).isPlaying)
                assertTrue("System TTS completion error; no automatic retry", errors.isEmpty())
                assertFalse(requireNotNull(runtime).isPlaying)
                started = CompletableDeferred(); finished = CompletableDeferred()
                requireNotNull(runtime).beginTurn()
                requireNotNull(runtime).enqueueSentence("这是停止播放验证，我们会在读完之前主动停止。".repeat(8), SpeechOutput.SystemTts())
            }
            withTimeout(10000) { started.await() }
            delay(600)
            withContext(Dispatchers.Main) {
                receipt.put("playingBeforeStop", requireNotNull(runtime).isPlaying)
                assertTrue(requireNotNull(runtime).isPlaying)
                requireNotNull(runtime).invalidateGeneration()
                receipt.put("playingAfterStop", requireNotNull(runtime).isPlaying).put("stopErrorCount", errors.size)
                assertFalse(requireNotNull(runtime).isPlaying); assertTrue(errors.isEmpty())
            }
            delay(400)
            withContext(Dispatchers.Main) { receipt.put("playingAfterStopWait", requireNotNull(runtime).isPlaying); assertFalse(requireNotNull(runtime).isPlaying) }
            receipt.put("result", "PASS_RUNTIME_CALLBACKS_AND_STOP")
        } finally {
            scenario?.close()
            withContext(Dispatchers.Main) { runtime?.release(); probe?.shutdown() }
            File(evidence, "tts-r3-observation.json").writeText(receipt.toString(2))
        }
    }

    @Test fun persistenceNavigationAndBadImportKeepData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = AppStore(context)
        val source = MemorySource("fixture-source", MemorySourceKind.USER_MESSAGE, 1000, "我喜欢喝茶")
        val memory = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single()).state
        val fixture = AppData(messages = listOf(SavedMessage("fixture-source", "user", "隔离测试资料：我喜欢喝茶", 1000), SavedMessage("fixture-answer", "assistant", "这是本地验证资料，不是真实 API 回复。", 1001)), memory = memory)
        store.save(fixture)
        assertEquals(fixture, store.load())
        val backup = StateCodec.encode(fixture)
        try { StateCodec.decode("{bad-import"); fail("bad backup accepted") } catch (_: org.json.JSONException) { }
        assertEquals(fixture, store.load())
        assertFalse(backup.contains("apiKey", true))
        val screenshots = File(context.cacheDir, "device-evidence").apply { mkdirs() }
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.desc("聊天输入")), 15000))
            assertTrue(device.takeScreenshot(File(screenshots, "01-home.png")))
            val displayedStates = org.json.JSONArray()
            val avatar = device.wait(Until.findObject(By.descStartsWith("浅，低头读书")), 5000) ?: throw AssertionError("2D读书立绘等待超时")
            displayedStates.put(avatar.contentDescription)
            delay(300) // Let the initial composition settle before visible evidence.
            assertTrue(device.takeScreenshot(File(screenshots, "01-avatar-approved.png")))
            avatar.click()
            val afterTouch = device.wait(Until.findObject(By.descStartsWith("浅，低头读书")), 5000) ?: throw AssertionError("触摸后2D人物消失")
            displayedStates.put(afterTouch.contentDescription)
            assertTrue(device.takeScreenshot(File(screenshots, "01-avatar-touch.png")))
            assertTrue("触摸后聊天输入丢失", device.hasObject(By.desc("聊天输入")))
            File(screenshots, "2d-touch-states.json").writeText(displayedStates.toString(2))
            device.findObject(By.desc("更多")).click()
            (device.wait(Until.findObject(By.text("设置")), 5000) ?: throw AssertionError("更多菜单的设置入口等待超时")).click()
            assertTrue(device.wait(Until.hasObject(By.text("把陪伴调成你的样子")), 5000))
            assertTrue(device.takeScreenshot(File(screenshots, "02-settings.png")))
            device.findObject(By.text("记忆")).click()
            assertTrue(device.wait(Until.hasObject(By.text("一起记得的事")), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("我喜欢喝茶")), 5000))
            assertTrue(device.takeScreenshot(File(screenshots, "03-memory.png")))
            device.findObject(By.text("更正")).click()
            assertTrue(device.wait(Until.hasObject(By.text("更正记忆")), 5000))
            device.findObject(By.clazz("android.widget.EditText")).text = "我喜欢绿茶"
            device.findObject(By.text("保存更正")).click()
            assertTrue(device.wait(Until.hasObject(By.text("我喜欢绿茶")), 5000))
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            assertTrue(device.wait(Until.hasObject(By.desc("聊天输入")), 10000))
            device.findObject(By.desc("更多")).click()
            (device.wait(Until.findObject(By.text("记忆")), 5000) ?: throw AssertionError("重启后更多菜单的记忆入口等待超时")).click()
            assertTrue(device.wait(Until.hasObject(By.text("我喜欢绿茶")), 5000))
            assertTrue(device.takeScreenshot(File(screenshots, "04-restarted-memory.png")))
            device.findObject(By.text("忘记")).click()
            (device.wait(Until.findObject(By.text("确认忘记")), 5000) ?: throw AssertionError("确认忘记对话框等待超时")).click()
            assertTrue(device.wait(Until.hasObject(By.text("记忆修订 3 · 0 条生效")), 5000))
            assertTrue(store.load().memory.suppressedSourceIds.contains("fixture-source"))
        } finally {
            scenario.close()
            store.save(AppData()) // only the isolated fixture created above; never touches another app.
        }
    }
}
