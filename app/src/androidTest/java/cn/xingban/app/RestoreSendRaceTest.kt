package cn.xingban.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import cn.xingban.app.ui.CompanionApp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.xingban.app.data.*
import cn.xingban.app.integrations.*
import cn.xingban.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.flow.collect

@RunWith(AndroidJUnit4::class)
class RestoreSendRaceTest {
    @Test fun r17VolumeTargetsAreCapturedBeforeSaveAndUiNeverRetargets() = runBlocking<Unit> {
        val localType = android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        val remoteType = android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val absolute = android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE
        assertEquals(DeviceVolumeScope.LOCAL_SHARED, controllableVolumeScope(localType, absolute, 15))
        assertEquals(DeviceVolumeScope.REMOTE_OUTPUT, controllableVolumeScope(remoteType, absolute, 100))
        for (type in listOf(localType, remoteType)) for (control in listOf(android.media.VolumeProvider.VOLUME_CONTROL_FIXED, android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE)) assertNull(controllableVolumeScope(type, control, 100))
        assertNull(controllableVolumeScope(localType, absolute, 0)); assertNull(controllableVolumeScope(remoteType, absolute, -1)); assertNull(controllableVolumeScope(999, absolute, 100))
        assertTrue(matchesVolumeScope(DeviceVolumeScope.LOCAL_SHARED, DeviceVolumeScope.LOCAL_SHARED)); assertTrue(matchesVolumeScope(DeviceVolumeScope.REMOTE_OUTPUT, DeviceVolumeScope.REMOTE_OUTPUT))
        assertFalse(matchesVolumeScope(DeviceVolumeScope.LOCAL_SHARED, DeviceVolumeScope.REMOTE_OUTPUT)); assertFalse(matchesVolumeScope(DeviceVolumeScope.REMOTE_OUTPUT, DeviceVolumeScope.LOCAL_SHARED)); assertFalse(matchesVolumeScope(null, DeviceVolumeScope.LOCAL_SHARED)); assertFalse(matchesVolumeScope(DeviceVolumeScope.LOCAL_SHARED, null)); assertFalse(matchesVolumeScope(null, null))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var durable = AppData(settings = AppSettings(musicEnabled = false))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = object : AppStore(app) {
            override suspend fun load() = durable
            override suspend fun save(data: AppData) {
                if (!entered.isCompleted && data.messages.lastOrNull()?.text == "QQ音乐音量30%") { entered.complete(Unit); release.await() }
                durable = data
            }
        }
        val commands = mutableListOf<DeviceCommand.SetQqMusicVolume>()
        val phone = object : PhoneRuntime(app) {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(QqMusicState(volumeScope = DeviceVolumeScope.LOCAL_SHARED, canSetVolume = true))
            override fun setForeground(value: Boolean) = Unit
            override fun execute(command: DeviceCommand): PhoneCommandResult { commands += command as DeviceCommand.SetQqMusicVolume; return PhoneCommandResult(false, "隔离记录目标；未执行平台音量操作。") }
        }
        val noNetwork = object : DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult = error("Volume commands never request a model")
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork, phone); holder.put("r17-volume", model); model.foreground() }
            withTimeout(5000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.send("QQ音乐音量30%") }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { phone.state.value = phone.state.value.copy(volumeScope = DeviceVolumeScope.REMOTE_OUTPUT) }
            release.complete(Unit)
            withTimeout(5000) { model.state.first { !it.busy && it.data.messages.any { message -> message.role == "assistant" } } }
            assertEquals(DeviceVolumeScope.LOCAL_SHARED, commands.single().expectedScope)
            withContext(Dispatchers.Main) { model.sendMusicVolume(40, DeviceVolumeScope.LOCAL_SHARED) }
            withTimeout(5000) { model.state.first { !it.busy && it.data.messages.count { message -> message.role == "assistant" } == 2 } }
            assertEquals(DeviceVolumeScope.LOCAL_SHARED, commands.last().expectedScope)
            withContext(Dispatchers.Main) { model.send("QQ音乐音量50%") }
            withTimeout(5000) { model.state.first { !it.busy && it.data.messages.count { message -> message.role == "assistant" } == 3 } }
            assertEquals(DeviceVolumeScope.REMOTE_OUTPUT, commands.last().expectedScope)
            withContext(Dispatchers.Main) { phone.state.value = phone.state.value.copy(volumeScope = null); model.send("QQ音乐音量60%") }
            withTimeout(5000) { model.state.first { it.data.messages.any { message -> message.text.contains("没有可控制的音量目标") } } }
            assertEquals(3, commands.size); assertTrue(model.state.value.data.memory.items.isEmpty()); assertEquals(0, model.state.value.data.usage.chat.requests)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { holder.clear() } }
    }
    @Test fun r17LocalCommandsSaveBeforeHandoffAndCancelledOwnerNeverExecutes() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        SecretStore(app).write("deepseek", ""); SecretStore(app).write("opencode_go", "")
        var durable = AppData(settings = AppSettings(musicEnabled = false))
        var rejectOpen = false; var gateOpen = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = object : AppStore(app) {
            override suspend fun load() = durable
            override suspend fun save(data: AppData) {
                if (data.messages.any { it.text == "准备打开QQ音乐。" }) {
                    if (rejectOpen) throw java.io.IOException("isolated handoff save failure")
                    if (gateOpen) { entered.complete(Unit); release.await() }
                }
                durable = data
            }
        }
        var modelCalls = 0
        val noNetwork = object : DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult { modelCalls++; error("No model call for a deterministic local command") }
        }
        val executions = mutableListOf<DeviceCommand>()
        val fakePhone = object : PhoneRuntime(app) {
            var fakeForeground = false
            override val state = kotlinx.coroutines.flow.MutableStateFlow(QqMusicState(installed = true, accessGranted = true, listenerConnected = true, sessionAvailable = true, title = "隔离曲目", canPause = true))
            override fun setForeground(value: Boolean) { fakeForeground = value }
            override fun execute(command: DeviceCommand): PhoneCommandResult {
                check(fakeForeground)
                if (command == DeviceCommand.OpenQqMusic || command is DeviceCommand.SetAlarm) check(durable.messages.any { it.role == "assistant" && it.status == "local" && it.text.startsWith("准备") })
                executions += command
                return PhoneCommandResult(true, if (command is DeviceCommand.SetAlarm) "已向系统提交该时间的闹钟设置请求，请在时钟中确认。" else "隔离QQ音乐操作已返回。", command is DeviceCommand.SetAlarm || command == DeviceCommand.OpenQqMusic)
            }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, noNetwork, fakePhone); holder.put("r17-local", model); model.foreground() }
            withTimeout(5000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.send("暂停QQ音乐") }
            withTimeout(5000) { model.state.first { it.data.messages.any { message -> message.text == "隔离QQ音乐操作已返回。" } } }
            assertEquals(listOf(DeviceCommand.PauseQqMusic), executions); assertEquals(0, modelCalls)
            withContext(Dispatchers.Main) { model.send("设置8点30分闹钟") }
            withTimeout(5000) { model.state.first { it.data.messages.any { message -> message.text == "已向系统提交该时间的闹钟设置请求，请在时钟中确认。" } } }
            assertEquals(DeviceCommand.SetAlarm(8, 30), executions.last()); assertEquals(2, executions.size)
            assertTrue(model.state.value.data.memory.items.isEmpty()); assertTrue(model.state.value.data.messages.filter { it.role == "assistant" }.all { it.status == "local" })
            rejectOpen = true
            withContext(Dispatchers.Main) { model.send("打开QQ音乐") }
            withTimeout(5000) { model.state.first { it.notice.contains("未执行手机操作") } }
            assertEquals(2, executions.size)
            rejectOpen = false; gateOpen = true
            withContext(Dispatchers.Main) { model.dismissNotice(); model.send("打开QQ音乐") }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { model.background() }
            release.complete(Unit)
            withTimeout(5000) { model.state.first { it.data.departedAtMillis != null } }
            assertEquals(2, executions.size); assertEquals(0, modelCalls)
            assertEquals(0, model.state.value.data.usage.chat.requests)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { holder.clear() } }
    }
    private fun summarySeed(): AppData {
        val messages = (1..11).flatMap { n -> listOf(
            SavedMessage("r13-u$n", "user", if (n == 1) "记住：我喜欢绿茶" else "虚构故事第${n}幕，蓝门旁的信还没打开。", 1000L + n * 10),
            SavedMessage("r13-a$n", "assistant", "故事继续，第${n}幕。", 1001L + n * 10, turnId = "r13-u$n"),
        ) }
        val first = messages.first()
        val source = MemorySource(first.id, MemorySourceKind.USER_MESSAGE, first.time, first.text)
        val memory = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single()).state
        return AppData(messages = messages, memory = memory, settings = AppSettings(musicEnabled = false))
    }
    private class SummaryFixture(private val gated: Boolean = true, private val failSummary: Boolean = false) : DialogueClient {
        val summaryEntered = CompletableDeferred<Unit>()
        val releaseSummary = CompletableDeferred<Unit>()
        val nextChatEntered = CompletableDeferred<Unit>()
        val releaseNextChat = CompletableDeferred<Unit>()
        var holdNextChat = false
        var chats = 0
        var summaries = 0
        override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
            onUsage(RequestUsage(requestId, roundId, purpose, model, System.currentTimeMillis(), 100, 20, 40, 60))
            if (purpose == RequestPurpose.SUMMARY) {
                summaries++
                summaryEntered.complete(Unit)
                if (gated) withContext(NonCancellable) { releaseSummary.await() }
                if (failSummary) throw java.io.IOException("isolated summary failure")
                onDelta("虚构故事：蓝门旁的信还没打开；用户与角色打算一起读信。")
                return DialogueResult(true, emptyList(), memoryValid = true)
            }
            chats++
            onDelta(if (chats == 1) "第一段已保存。\n\n第二段也已保存。" else "下一轮回复。")
            if (holdNextChat && chats > 1) { nextChatEntered.complete(Unit); releaseNextChat.await() }
            return DialogueResult(true, emptyList(), memoryValid = true)
        }
    }

    @Test fun routineHistoryTrimmingIsSilentButClippedInputKeepsTheOriginalAndExplains() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val secrets = SecretStore(app)
        secrets.write("deepseek", "fixture-not-real-key")
        val seed = (1..6).flatMap { n -> listOf(
            SavedMessage("r14-u$n", "user", "普通历史问题$n", 1000L + n * 10),
            SavedMessage("r14-a$n", "assistant", "普通历史回复$n", 1001L + n * 10, turnId = "r14-u$n"),
        ) }
        var durable = AppData(messages = seed, settings = AppSettings(musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = durable; override suspend fun save(data: AppData) { durable = data } }
        val contexts = mutableListOf<List<DialogueMessage>>()
        val fake = object : DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
                check(purpose == RequestPurpose.CHAT)
                contexts += messages
                onDelta("隔离回复" + contexts.size)
                return DialogueResult(true, emptyList(), memoryValid = true)
            }
        }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "r14-notice").apply { mkdirs() }
        val receipt = JSONObject().put("scope", "ISOLATED_FIXTURE_REAL_VM_ASSEMBLER_IN_MEMORY").put("realApiCalls", 0)
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("r14-notice", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            val short = "我们继续聊。"
            withContext(Dispatchers.Main) { model.send(short) }
            val normal = withTimeout(10000) { model.state.first { !it.busy && it.data.messages.any { message -> message.role == "user" && message.text == short && message.status == "completed" } } }
            assertEquals(4, contexts.single().count { it.role == "assistant" })
            assertEquals(short, contexts.single().last().content)
            assertTrue(normal.notice.isEmpty())
            assertTrue(device.wait(Until.hasObject(By.text("隔离回复1")), 5000))
            check(device.takeScreenshot(File(evidence, "normal.png")))
            val original = "长".repeat(2500)
            check(original.length < 12000)
            withContext(Dispatchers.Main) { model.send(original) }
            val clipped = withTimeout(10000) { model.state.first { !it.busy && it.data.messages.any { message -> message.role == "user" && message.text == original && message.status == "completed" } } }
            val expected = "消息太长，本次只发送了前一部分。原文已保留，建议分几条发送。"
            assertEquals(expected, clipped.notice)
            assertNotEquals(original, contexts.last().last().content)
            assertEquals(original, durable.messages.last { it.role == "user" }.text)
            assertTrue(device.wait(Until.hasObject(By.text(expected)), 5000))
            check(device.takeScreenshot(File(evidence, "clipped.png")))
            assertEquals(2, contexts.size)
            receipt.put("seedCompletedTurns", 6).put("selectedRecentTurns", 4).put("shortInputNoticeEmpty", true)
                .put("originalLongInputChars", original.length).put("sentLongInputChars", contexts.last().last().content.length)
                .put("originalLongInputPreserved", true).put("clippedInputNotice", clipped.notice).put("fakeChatRequests", contexts.size)
        } finally {
            scenario.close(); withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "")
            File(evidence, "receipt.json").writeText(receipt.toString(2))
        }
    }

    @Test fun summaryMaintenanceGatesCancelLateWritesAndKeepUsageAcrossRestore() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val secrets = SecretStore(app)
        secrets.write("deepseek", "fixture-not-real-key")
        val evidence = File(app.cacheDir, "r13-maintenance").apply { mkdirs() }
        val outcomes = JSONArray()
        try {
            for (action in listOf("send", "stop", "clear", "correct", "forget", "restore", "failure")) {
                var durable = summarySeed()
                val seed = durable
                val store = object : AppStore(app) { override suspend fun load() = durable; override suspend fun save(data: AppData) { durable = data } }
                val fake = SummaryFixture(failSummary = action == "failure")
                val holder = ViewModelStore(); lateinit var model: ChatViewModel
                try {
                    withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("r13-$action", model) }
                    withTimeout(10000) { model.state.first { it.loaded } }
                    withContext(Dispatchers.Main) { model.foreground(); model.send("继续虚构故事，先把信留着。") }
                    withTimeout(10000) { fake.summaryEntered.await() }
                    val maintained = model.state.value
                    assertFalse(maintained.busy)
                    assertEquals(2, maintained.data.messages.filter { it.role == "assistant" && it.turnId == maintained.data.usage.roundId }.size)
                    assertEquals(12, maintained.data.summaryAttemptTurns)
                    assertEquals(1, maintained.data.usage.chat.requests)
                    assertNull(maintained.data.summary)
                    val memoryId = maintained.data.memory.items.single().id
                    when (action) {
                        "send" -> withContext(Dispatchers.Main) { fake.holdNextChat = true; model.send("新输入要立即开始。") }
                        "stop" -> withContext(Dispatchers.Main) { model.stop() }
                        "clear" -> withContext(Dispatchers.Main) { model.clearSummary() }
                        "correct" -> withContext(Dispatchers.Main) { model.editMemory(memoryId, "我喜欢白茶") }
                        "forget" -> withContext(Dispatchers.Main) { model.editMemory(memoryId, null) }
                        "restore" -> withContext(Dispatchers.Main) { model.restoreBackup(StateCodec.encode(seed)) }
                    }
                    if (action == "send") withTimeout(5000) { fake.nextChatEntered.await() }
                    if (action in setOf("correct", "forget")) withTimeout(5000) { model.state.first { it.data.memory.revision > seed.memory.revision } }
                    if (action == "clear") withTimeout(5000) { model.state.first { it.notice.contains("对话摘要已清除") } }
                    fake.releaseSummary.complete(Unit)
                    withTimeout(10000) { model.state.first { it.data.usage.summary.requests == 1 } }
                    assertNull(model.state.value.data.summary)
                    assertEquals(1, fake.summaries)
                    if (action == "send") {
                        assertTrue("Old finally cleared the new request", model.state.value.busy)
                        fake.releaseNextChat.complete(Unit)
                        withTimeout(5000) { model.state.first { !it.busy && it.data.usage.chat.requests == 2 } }
                    }
                    if (action in setOf("failure", "clear")) {
                        if (action == "failure") withTimeout(5000) { model.state.first { it.notice.contains("摘要整理未完成") } }
                        assertEquals(12, model.state.value.data.summaryAttemptTurns)
                        withContext(Dispatchers.Main) { model.dismissNotice(); model.send("继续说两句。") }
                        withTimeout(5000) { model.state.first { !it.busy && it.data.usage.chat.requests == 2 } }
                        assertEquals(1, fake.summaries)
                        assertEquals(12, model.state.value.data.summaryAttemptTurns)
                        if (action == "clear") {
                            repeat(7) { index ->
                                withContext(Dispatchers.Main) { model.send("虚构故事的新一轮 " + index) }
                                withTimeout(5000) { model.state.first { !it.busy && it.data.usage.chat.requests == index + 3 } }
                            }
                            withTimeout(5000) { model.state.first { it.data.usage.summary.requests == 2 && it.data.summary != null } }
                            assertEquals(2, fake.summaries)
                            assertEquals(20, model.state.value.data.summaryAttemptTurns)
                        }
                    }
                    if (action == "restore") {
                        assertEquals(seed.messages, durable.messages)
                        assertEquals(1, durable.usage.chat.requests); assertEquals(1, durable.usage.summary.requests)
                        assertEquals(0, durable.summaryAttemptTurns)
                    }
                    assertTrue(durable.messages.none { it.text.startsWith("虚构故事：蓝门旁的信") })
                    outcomes.put(JSONObject().put("action", action).put("summaryRequests", fake.summaries).put("lateSummaryWasRejected", true).put("newSummaryAfterEightTurns", action == "clear").put("chatUsageRequests", durable.usage.chat.requests).put("summaryUsageRequests", durable.usage.summary.requests))
                } finally {
                    fake.releaseSummary.complete(Unit); fake.releaseNextChat.complete(Unit)
                    withContext(Dispatchers.Main) { holder.clear() }
                }
            }
        } finally {
            secrets.write("deepseek", "")
            File(evidence, "receipt.json").writeText(JSONObject().put("scope", "ISOLATED_FIXTURE_IN_MEMORY").put("realApiCalls", 0).put("cases", outcomes).toString(2))
        }
    }

    @Test fun failedUsageSaveRetainsObservedLedgerUntilALaterSuccessfulSnapshot() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val secrets = SecretStore(app); secrets.write("deepseek", "fixture-not-real-key")
        var durable = summarySeed()
        val backup = StateCodec.encode(durable)
        var rejectUsage = true
        var failures = 0
        val store = object : AppStore(app) {
            override suspend fun load() = durable
            override suspend fun save(data: AppData) {
                if (rejectUsage && data.usage.summary.requests > 0) { rejectUsage = false; failures++; throw java.io.IOException("isolated one usage save failure") }
                durable = data
            }
        }
        val fake = SummaryFixture(gated = false, failSummary = true)
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("r13-usage-write", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.foreground(); model.send("继续虚构故事。") }
            withTimeout(10000) { model.state.first { it.usageSavePending && it.notice.contains("摘要整理未完成") } }
            assertEquals(1, failures)
            assertEquals(1, model.state.value.data.usage.summary.requests)
            assertEquals(0, durable.usage.summary.requests)
            withContext(Dispatchers.Main) { model.dismissNotice() }
            assertTrue(model.state.value.usageSavePending)
            assertTrue(model.state.value.notice.isEmpty())
            withContext(Dispatchers.Main) { model.restoreBackup(backup) }
            assertFalse(model.state.value.usageSavePending)
            assertEquals(1, durable.usage.chat.requests); assertEquals(1, durable.usage.summary.requests)
            assertEquals(2, durable.usage.recentRequestIds.size)
            assertEquals(durable.usage, model.state.value.data.usage)
        } finally { withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "") }
    }

    @Test fun summaryAndUsagePersistThroughRecreateAndNewUiShowsBothAccountingStates() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val secrets = SecretStore(app); secrets.write("deepseek", "fixture-not-real-key")
        val store = AppStore(app)
        store.save(summarySeed())
        val fake = SummaryFixture(gated = false)
        var holder = ViewModelStore(); lateinit var model: ChatViewModel
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "r13-persistence-ui").apply { mkdirs() }
        val receipt = JSONObject().put("scope", "ISOLATED_FIXTURE_ROOM").put("realApiCalls", 0)
        fun openMenu(label: String) {
            checkNotNull(device.wait(Until.findObject(By.desc("更多")), 5000)).click()
            checkNotNull(device.wait(Until.findObject(By.text(label)), 5000)).parent.click()
        }
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("r13-persist", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.foreground(); model.send("继续我们的虚构故事。") }
            withTimeout(10000) { model.state.first { it.data.summary != null && it.data.usage.summary.requests == 1 } }
            val before = model.state.value.data
            assertEquals(1, before.usage.chat.requests); assertEquals(1, before.usage.summary.requests)
            assertEquals(1, before.memory.items.size)
            withContext(Dispatchers.Main) { holder.clear(); holder = ViewModelStore(); model = ChatViewModel(app, AppStore(app), fake); holder.put("r13-recreated", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            assertEquals(before.summary, model.state.value.data.summary)
            assertEquals(before.usage, model.state.value.data.usage)
            assertEquals(before.messages, model.state.value.data.messages)
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            openMenu("记忆")
            checkNotNull(device.wait(Until.findObject(By.text("查看对话摘要")), 5000)).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("机器整理，可能有误")), 5000))
            check(device.takeScreenshot(File(evidence, "summary.png"))); device.dumpWindowHierarchy(File(evidence, "summary.xml"))
            checkNotNull(device.findObject(By.text("清除摘要，保留聊天"))).click()
            withTimeout(5000) { model.state.first { it.data.summary == null } }
            assertEquals(before.messages, model.state.value.data.messages)
            checkNotNull(device.findObject(By.text("陪伴"))).parent.click()
            openMenu("设置")
            assertTrue(device.wait(Until.hasObject(By.text("实际用量与参考费用")), 5000))
            assertTrue(device.hasObject(By.textStartsWith("本轮摘要")))
            check(device.takeScreenshot(File(evidence, "usage-complete.png"))); device.dumpWindowHierarchy(File(evidence, "usage-complete.xml"))
            @Suppress("UNCHECKED_CAST")
            val ui = ChatViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as kotlinx.coroutines.flow.MutableStateFlow<CompanionUiState>
            withContext(Dispatchers.Main) {
                val unknown = UsageTotals().add(RequestUsage("unknown-ui", "unknown-round", RequestPurpose.CHAT, "unknown", 1))
                ui.value = ui.value.copy(data = ui.value.data.copy(usage = ui.value.data.usage.copy(roundChat = unknown, roundSummary = UsageTotals())))
            }
            assertTrue(device.wait(Until.hasObject(By.textContains("参考费用未确认")), 5000))
            check(device.takeScreenshot(File(evidence, "usage-unknown.png"))); device.dumpWindowHierarchy(File(evidence, "usage-unknown.xml"))
            receipt.put("summaryAndUsageRoomReadback", true).put("messagesPreservedAfterClear", true).put("machineWarning", true).put("usageCompleteAndUnknown", true)
        } finally {
            scenario.close(); withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "")
            File(evidence, "receipt.json").writeText(receipt.toString(2))
        }
    }

    @Before fun requireIsolatedFixturePackages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("cn.xingban.app.fixture.xbr2diag27", instrumentation.targetContext.packageName)
        assertEquals("cn.xingban.app.fixture.xbr2diag27.test", instrumentation.context.packageName)
    }

    /** No network: observe targeted UI booleans while the host supplies a key over private stdin. */
    @Test fun observePrivateFixtureKeySetup() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("r10Configure") == "true")
        val app = instrumentation.targetContext.applicationContext as Application
        val device = UiDevice.getInstance(instrumentation)
        val setupId = checkNotNull(InstrumentationRegistry.getArguments().getString("setupId"))
        require(setupId.matches(Regex("r10-setup-[0-9]{2}")))
        val secretName = InstrumentationRegistry.getArguments().getString("r17SetupProvider") ?: "deepseek"
        require(secretName in setOf("deepseek", "opencode_go"))
        val fieldLabel = if (secretName == "opencode_go") "OpenCode Go API Key" else "DeepSeek API Key"
        val deleteLabel = if (secretName == "opencode_go") "删除已保存的 OpenCode Go Key" else "删除已保存的 DeepSeek Key"
        val inputGate = File(app.cacheDir, "$setupId.ready")
        check(!inputGate.exists()) { "Input completion gate already exists" }
        val receipt = JSONObject().put("package", app.packageName).put("networkCalls", 0)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        fun readIme(): Boolean? {
            var visible: Boolean? = null
            if (Build.VERSION.SDK_INT >= 30) scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) }
            return visible
        }
        fun recordPage(stage: String, scrollReturned: Boolean? = null): Boolean {
            val page = JSONObject().put("scenarioState", scenario.state.name)
            var finishing: Boolean? = null; var destroyed: Boolean? = null; var ime: Boolean? = null
            if (scenario.state.name != "DESTROYED") {
                try { scenario.onActivity {
                    finishing = it.isFinishing; destroyed = it.isDestroyed
                    if (Build.VERSION.SDK_INT >= 30) ime = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())
                } } catch (error: Exception) { page.put("activityReadErrorType", error.javaClass.simpleName) }
            }
            val homeVisible = device.hasObject(By.pkg(app.packageName).desc("聊天输入"))
            val scrollNode = device.findObject(By.pkg(app.packageName).clazz("android.widget.ScrollView").scrollable(true))
            page.put("finishing", finishing ?: JSONObject.NULL).put("destroyed", destroyed ?: JSONObject.NULL).put("imeVisible", ime ?: JSONObject.NULL)
                .put("settingsTitleVisible", device.hasObject(By.pkg(app.packageName).text("把陪伴调成你的样子")))
                .put("homeInputVisible", homeVisible).put("saveVisible", device.hasObject(By.pkg(app.packageName).text("保存设置")))
                .put("scrollExists", scrollNode != null).put("scrollBounds", scrollNode?.visibleBounds?.toShortString() ?: JSONObject.NULL)
                .put("scrollReturned", scrollReturned ?: JSONObject.NULL)
            receipt.put(stage, page)
            check(finishing == false && destroyed == false && !homeVisible && scenario.state.name != "DESTROYED") { "Settings activity ended or Home became visible" }
            return checkNotNull(ime) { "IME state is UNKNOWN" }
        }
        suspend fun keyViewport(): android.graphics.Rect {
            scenario.onActivity { it.window.insetsController?.hide(WindowInsets.Type.ime()) }
            withTimeout(5000) { while (checkNotNull(readIme()) { "IME state is UNKNOWN" }) delay(50) }
            check(device.currentPackageName == app.packageName) { "Wrong Settings window" }
            checkNotNull(device.findObject(By.pkg(app.packageName).text("保存设置")))
            val scroll = checkNotNull(device.wait(Until.findObject(By.pkg(app.packageName).clazz("android.widget.ScrollView")), 5000)).visibleBounds
            val content = android.graphics.Rect()
            scenario.onActivity {
                check(it.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(content))
                val bars = checkNotNull(it.window.decorView.rootWindowInsets).getInsets(WindowInsets.Type.systemBars())
                content.top = maxOf(content.top, bars.top); content.bottom = minOf(content.bottom, device.displayHeight - bars.bottom)
            }
            return android.graphics.Rect(scroll).also { check(it.intersect(content) && !it.isEmpty) }
        }
        fun swipeKeyViewport(viewport: android.graphics.Rect, down: Boolean) {
            val upper = viewport.top + (viewport.height() * 0.30f).toInt()
            val lower = viewport.top + (viewport.height() * 0.70f).toInt()
            check(device.swipe(viewport.centerX(), if (down) upper else lower, viewport.centerX(), if (down) lower else upper, 20))
        }
        suspend fun revealKey(): androidx.test.uiautomator.UiObject2 {
            var atTop = false
            repeat(9) {
                if (!atTop) {
                    val viewport = keyViewport()
                    val title = device.findObject(By.text("把陪伴调成你的样子"))?.visibleBounds
                    atTop = title != null && !title.isEmpty && viewport.contains(title)
                    if (!atTop) swipeKeyViewport(viewport, true)
                }
            }
            check(atTop) { "Settings title did not enter its viewport" }
            repeat(9) {
                val viewport = keyViewport()
                val node = device.findObject(By.pkg(app.packageName).desc(fieldLabel))
                val field = node?.let { generateSequence(it) { parent -> parent.parent }.firstOrNull { parent -> parent.className == "android.widget.EditText" } }
                val bounds = field?.visibleBounds
                if (field != null && bounds != null && !bounds.isEmpty && viewport.contains(bounds) && bounds.bottom <= viewport.centerY()) return field
                swipeKeyViewport(viewport, false)
            }
            error("Provider key field did not enter its Settings viewport")
        }
        try {
            if (!device.hasObject(By.text("把陪伴调成你的样子"))) {
                checkNotNull(device.wait(Until.findObject(By.desc("更多")), 10000)).click()
                checkNotNull(device.wait(Until.findObject(By.text("设置")), 5000)).parent.click()
            }
            val field = revealKey()
            check(field.className == "android.widget.EditText" && field.applicationPackage == app.packageName)
            val deletion = checkNotNull(device.findObject(By.text(deleteLabel))).parent.findObject(By.clazz("android.widget.CheckBox"))
            check(deletion == null || !deletion.isChecked) { "Delete-key option must remain unselected" }
            field.click()
            val focused = device.wait(Until.hasObject(By.clazz("android.widget.EditText").focused(true)), 5000)
            receipt.put("correctFieldFocused", focused)
            check(focused)
            instrumentation.sendStatus(2, Bundle().apply { putString("r10Setup", "FIELD_FOCUSED_READY"); putBoolean("correctPackage", true); putBoolean("focused", focused) })
            withTimeout(45000) {
                while (true) {
                    val nonEmpty = !field.text.isNullOrBlank()
                    if (nonEmpty && !receipt.has("firstNonEmptyAtMillis")) receipt.put("firstNonEmptyAtMillis", System.currentTimeMillis())
                    if (inputGate.exists() && nonEmpty) break
                    delay(100)
                }
            }
            receipt.put("fieldNonEmpty", true).put("inputCompletionGate", true)
            instrumentation.sendStatus(2, Bundle().apply { putString("r10Setup", "FIELD_NONEMPTY"); putBoolean("nonEmpty", true) })
            if (recordPage("afterInput")) {
                check(Build.VERSION.SDK_INT >= 30)
                scenario.onActivity { checkNotNull(it.window.insetsController).hide(WindowInsets.Type.ime()) }
                withTimeout(5000) { while (checkNotNull(readIme()) { "IME state is UNKNOWN" }) delay(100) }
            }
            check(!recordPage("afterImeHidden"))
            val saveSelector = By.pkg(app.packageName).text("保存设置")
            recordPage("beforeSave")
            val saveButton = checkNotNull(device.wait(Until.findObject(saveSelector), 5000)).parent
            check(saveButton.isClickable)
            saveButton.click()
            val savedNotice = device.wait(Until.hasObject(By.text("设置已保存。")), 10000)
            receipt.put("savedNotice", savedNotice)
            check(savedNotice) { "No saved-settings notice" }
            revealKey()
            val cleared = device.wait(Until.hasObject(By.text("已安全保存；留空保持")), 5000)
            receipt.put("emptySavedPlaceholder", cleared).put("secretStoreHas", SecretStore(app).has(secretName)).put("provider", secretName)
            check(SecretStore(app).has(secretName))
            checkNotNull(device.findObject(By.text("陪伴"))).parent.click()
            checkNotNull(device.wait(Until.findObject(By.desc("更多")), 5000)).click()
            checkNotNull(device.wait(Until.findObject(By.text("设置")), 5000)).parent.click()
            revealKey()
            val persisted = device.wait(Until.hasObject(By.text("已安全保存；留空保持")), 5000)
            receipt.put("persistedPlaceholderAfterReentry", persisted)
            check(SecretStore(app).has(secretName))
            checkNotNull(device.findObject(By.text("陪伴"))).parent.click()
        } finally {
            File(app.cacheDir, if (InstrumentationRegistry.getArguments().containsKey("r17SetupProvider")) "$setupId-$secretName.json" else "r10-private-setup-02.json").writeText(receipt.toString(2))
            if (inputGate.exists()) check(inputGate.delete())
            scenario.close()
        }
    }

    /** Opt-in, one synthetic round through the production provider/tool/VM/UI path. */
    @Test fun r17RealProviderSample() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit real API authorization required", args.getString("r17RealSample") == "true")
        val mode = requireNotNull(args.getString("r17Provider")); require(mode in setOf("official", "go", "search"))
        val sampleId = requireNotNull(args.getString("sampleId")); require(sampleId.matches(Regex("r17-(official|go|search)-[0-9]{2}")))
        val app = instrumentation.targetContext.applicationContext as Application
        val provider = if (mode == "go") ChatProvider.OPENCODE_GO else ChatProvider.DEEPSEEK
        check(SecretStore(app).has(provider.secretName)); if (mode == "search") check(SecretStore(app).has("deepseek"))
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, sampleId).apply { mkdirs() }
        val requests = JSONArray(); val usages = JSONArray(); val searches = JSONArray(); val finals = JSONArray()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(180, TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val encoded = JSONObject(body)
            val row = JSONObject().put("url", request.url.toString()).put("model", encoded.opt("model") ?: JSONObject.NULL).put("thinking", encoded.optJSONObject("thinking") ?: JSONObject.NULL).put("reasoning_effort", encoded.opt("reasoning_effort") ?: JSONObject.NULL).put("max_tokens", encoded.opt("max_tokens") ?: JSONObject.NULL).put("hasTools", encoded.has("tools")).put("jsonFinal", encoded.optJSONObject("response_format")?.optString("type") == "json_object")
            val wireMessages = encoded.optJSONArray("messages")
            val names = JSONArray(); var toolResults = 0
            if (wireMessages != null) for (i in 0 until wireMessages.length()) {
                val message = wireMessages.getJSONObject(i)
                if (message.optString("role") == "tool") toolResults++
                val calls = message.optJSONArray("tool_calls") ?: continue
                for (n in 0 until calls.length()) names.put(calls.getJSONObject(n).getJSONObject("function").getString("name"))
            }
            row.put("toolResults", toolResults).put("toolNames", names)
            synchronized(requests) { requests.put(row) }
            val response = chain.proceed(request); row.put("http", response.code); response
        }.build()
        val real = DeepSeekDialogueClient(http)
        val tapped = object : DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit) = real.stream(apiKey, model, messages, purpose, requestId, roundId, onUsage, onDelta)
            override suspend fun reply(route: DialogueRoute, messages: List<DialogueMessage>, purpose: RequestPurpose, roundId: String, onUsage: (RequestUsage) -> Unit, onSearchUsage: (String, Boolean) -> Unit, ownerActive: () -> Boolean, onDelta: suspend (String) -> Unit): DialogueResult =
                real.reply(route, messages, purpose, roundId, { usage ->
                    synchronized(usages) { usages.put(JSONObject().put("id", usage.requestId).put("provider", usage.provider).put("model", usage.model).put("purpose", usage.purpose.name).put("prompt", usage.prompt ?: JSONObject.NULL).put("completion", usage.completion ?: JSONObject.NULL).put("cacheHit", usage.cacheHit ?: JSONObject.NULL).put("cacheMiss", usage.cacheMiss ?: JSONObject.NULL).put("cost", usage.reportedCost ?: JSONObject.NULL).put("billing_source", usage.billingSource ?: JSONObject.NULL)) }; onUsage(usage)
                }, { id, ok -> synchronized(searches) { searches.put(JSONObject().put("id", id).put("success", ok)) }; onSearchUsage(id, ok) }, ownerActive, onDelta).also { result ->
                    finals.put(JSONObject().put("completed", result.completed).put("memoryValid", result.memoryValid).put("done", result.diagnostics?.done ?: false).put("finishReason", result.diagnostics?.finishReason?.name ?: "UNKNOWN").put("rootClosed", result.diagnostics?.rootClosed ?: false))
                }
        }
        var durable = AppData(settings = AppSettings(provider = provider.setting, model = provider.model, musicEnabled = false))
        val store = object : AppStore(app) { override suspend fun load() = durable; override suspend fun save(data: AppData) { durable = data } }
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val receipt = JSONObject().put("scope", "ISOLATED_FIXTURE_REAL_VM_CLIENT_UI_IN_MEMORY").put("provider", provider.setting).put("mode", mode).put("requests", requests).put("usages", usages).put("searches", searches).put("finals", finals)
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, tapped); holder.put("r17-real", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            scenario.onActivity { activity -> model.foreground(); activity.setContent { CompanionApp(model, {}, {}, {}) } }
            val input = when (mode) { "search" -> "联网搜索DeepSeek官方Search文档，简短说明接口用途并给来源链接。"; "go" -> "现在几点，请用一句话告诉我当前设备时间。"; else -> "你好，今天想轻松聊两句，用一句话回应就好。" }
            withContext(Dispatchers.Main) { model.send(input) }
            val ended = withTimeout(210000) { model.state.first { !it.busy && it.data.messages.any { message -> message.role == "user" && message.status != "pending" } } }
            receipt.put("notice", ended.notice).put("messages", JSONArray(ended.data.messages.map { JSONObject().put("role", it.role).put("text", it.text).put("status", it.status) })).put("usage", JSONObject(StateCodec.encode(ended.data)).getJSONObject("usage"))
            check(ended.data.messages.any { it.role == "assistant" && it.status == "completed" }); check(ended.notice.isBlank()); check(ended.data.memory.items.isEmpty())
            if (mode == "search") { check(ended.data.usage.searchSuccess == 1); check(ended.data.messages.any { it.role == "assistant" && it.text.contains("https://") }) }
            check(requests.length() == when (mode) { "search" -> 3; "go" -> 2; else -> 1 })
            if (mode == "go") { check(requests.getJSONObject(1).getInt("toolResults") == 1); check(requests.getJSONObject(1).getJSONArray("toolNames").getString(0) == "get_device_time") }
            check(device.takeScreenshot(File(evidence, "completed.png"))); device.dumpWindowHierarchy(File(evidence, "completed.xml"))
            receipt.put("completed", true)
        } catch (error: Throwable) { receipt.put("failureType", error.javaClass.simpleName); throw error }
        finally { scenario.close(); withContext(Dispatchers.Main) { holder.clear() }; http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); File(evidence, "receipt.json").writeText(receipt.toString(2)) }
    }

    /** Explicit opt-in only. Captures one synthetic real App turn without changing its reader. */
    @Test fun realSyntheticDialogueSample() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real network sampling requires explicit invocation", args.getString("r10RealSample") == "true")
        val sampleId = args.getString("sampleId") ?: "r10-real-01"
        val natural = args.getString("r15Natural") == "true"
        val rolling = args.getString("r13Rolling") == "true"
        val sequence = args.getString("r10Sequence") == "true"
        val wrappedHistory = args.getString("r10WrapHistory") == "true"
        val resume = args.getString("r10Resume") == "true"
        require(!natural || (!rolling && !sequence && !wrappedHistory && !resume)) { "r15Natural cannot be combined with another sample mode" }
        require(sampleId.matches(Regex(if (natural) "r15-ux-real-[0-9]{2}" else if (rolling) "r13-real-[0-9]{2}" else "r10-real-[0-9]{2}")))
        require(!sequence || sampleId in setOf("r10-real-02", "r10-real-04"))
        require(!wrappedHistory || (sampleId == "r10-real-03" && !sequence))
        require(!rolling || (!sequence && !wrappedHistory && !resume))
        require(!resume || (sampleId == "r10-real-05" && !sequence && !wrappedHistory))
        require(natural || !args.containsKey("r15InputsB64")) { "r15InputsB64 requires r15Natural" }
        val naturalInputs = if (natural) {
            assertEquals("cn.xingban.app.fixture.xbr2diag27", instrumentation.targetContext.packageName)
            assertEquals("cn.xingban.app.fixture.xbr2diag27.test", instrumentation.context.packageName)
            val encoded = requireNotNull(args.getString("r15InputsB64")) { "r15InputsB64 is required for r15Natural" }
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val json = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val inputs = JSONArray(json)
            require(inputs.length() in 3..8) { "r15Natural requires 3 to 8 inputs" }
            (0 until inputs.length()).map { index ->
                val input = inputs.get(index)
                require(input is String && input.isNotBlank() && input.length <= 300) { "Each r15 input must be a nonblank string of at most 300 characters" }
                input
            }
        } else emptyList()
        val app = instrumentation.targetContext.applicationContext as Application
        check(SecretStore(app).has("deepseek")) { "Configure the isolated fixture key through Settings first" }
        val evidence = File(app.cacheDir, sampleId)
        check(!evidence.exists()) { "Sample evidence already exists; use a new sample ID" }
        check(evidence.mkdirs())
        val attempts = AtomicInteger()
        val network = JSONArray()
        val decoded = JSONArray()
        val observed = JSONArray()
        val turns = JSONArray()
        val activeCapture = AtomicReference<JSONObject?>(null)
        val seedTime = System.currentTimeMillis() - 2000
        val resumed = if (resume) {
            val previous = JSONObject(File(app.cacheDir, "r10-real-04/receipt.json").readText())
            val previousTurns = previous.getJSONArray("turns")
            check((0..1).all { previousTurns.getJSONObject(it).getBoolean("completed") && previousTurns.getJSONObject(it).getBoolean("memoryValid") })
            val memories = previousTurns.getJSONObject(1).getJSONObject("memoryState")
            val factSource = memories.getJSONArray("items").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
            val factTime = factSource.getLong("time")
            val history = mutableListOf<SavedMessage>()
            val oldSeed = previous.getJSONArray("seedMessages")
            for (index in 0 until oldSeed.length()) {
                val item = oldSeed.getJSONObject(index)
                history += SavedMessage(item.getString("id"), item.getString("role"), item.getString("text"), item.getLong("time"), "completed", if (item.getString("role") == "assistant") oldSeed.getJSONObject(0).getString("id") else null)
            }
            for (turnIndex in 0..1) {
                val messages = previousTurns.getJSONObject(turnIndex).getJSONArray("messages")
                for (index in 0 until messages.length()) {
                    val item = messages.getJSONObject(index)
                    val time = if (turnIndex == 0) factTime - 2 + index else factTime + index
                    history += SavedMessage(item.getString("id"), item.getString("role"), item.getString("text"), time, "completed", if (item.isNull("turnId")) null else item.getString("turnId"))
                }
            }
            check(history.any { it.id == factSource.getString("id") && it.text == factSource.getString("text") && it.time == factTime })
            val base = AppData(messages = history, activity = CompanionActivity(ActivityType.WATCH, ActivityPhase.ACTIVE, ActivityMode.EXPLICIT, revision = 1))
            StateCodec.decode(JSONObject(StateCodec.encode(base)).put("memory", memories).toString())
        } else null
        val seed = if (rolling) (1..11).flatMap { n -> listOf(
            SavedMessage("r13-story-u$n", "user", if (n == 1) "虚构故事里，小岚把银色钥匙藏在灯塔第三阶，阿舟约好黄昏用它开蓝门。请按虚构故事聊，不要记为我的真实经历。" else "虚构故事第${n}幕：他们沿海边走，聊了风声和云，先不打开那封信。", seedTime + n * 2),
            SavedMessage("r13-story-a$n", "assistant", "海风轻轻吹着，他们继续走，等故事下一幕。", seedTime + n * 2 + 1, turnId = "r13-story-u$n")
        ) } else resumed?.messages ?: if (sequence || wrappedHistory) listOf(
            SavedMessage("r10-seed-user-01", "user", "哈喽?", seedTime, "completed"),
            SavedMessage("r10-seed-assistant-01", "assistant", "哈喽～我在呢，刚刚在发呆，被你逮到了。", seedTime + 1, "completed", "r10-seed-user-01")
        ) else emptyList()
        val receipt = JSONObject().put("sampleId", sampleId).put("synthetic", true).put("input", "哈喽?").put("productionDataTouched", false)
        if (natural) {
            receipt.remove("input")
            receipt.put("inputs", JSONArray(naturalInputs)).put("inputRoute", "DIRECT_VM_SEND_REAL_UI_RENDER")
                .put("initialData", "DEFAULT_APPDATA_NO_SEED").put("testOnlyRequestRewrite", false)
        }
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).addNetworkInterceptor { chain ->
            val number = attempts.get() + 1
            var request = chain.request()
            val requestBuffer = Buffer()
            request.body?.writeTo(requestBuffer)
            var requestJson = requestBuffer.readUtf8()
            if (wrappedHistory) {
                File(evidence, "request-$number-before-test-rewrite.json").writeText(requestJson)
                val baseline = JSONObject(File(app.cacheDir, "r10-real-02/request-1.json").readText())
                val actual = JSONObject(requestJson)
                val beforeMatches = actual.toString() == baseline.toString()
                val messages = actual.getJSONArray("messages")
                val assistantIndices = (0 until messages.length()).filter { messages.getJSONObject(it).getString("role") == "assistant" }
                check(assistantIndices.size == 1) { "Control requires exactly one historical assistant" }
                val index = assistantIndices.single()
                val original = messages.getJSONObject(index).getString("content")
                check(original == seed[1].text) { "Historical reply differs from the approved seed" }
                val wrapped = JSONObject().put("reply", original).toString()
                check(JSONObject(wrapped).getString("reply") == original)
                messages.getJSONObject(index).put("content", wrapped)
                baseline.getJSONArray("messages").getJSONObject(index).put("content", wrapped)
                val onlyChange = actual.toString() == baseline.toString()
                File(evidence, "request-$number-preflight.json").writeText(JSONObject().put("beforeMatchesReal02", beforeMatches).put("matchesReal02ExceptSingleAssistantContent", onlyChange).put("unwrappedReplyMatchesExactSeed", true).put("assistantIndex", index).toString(2))
                check(beforeMatches && onlyChange) { "Control request changed more than the historical assistant representation; not submitted" }
                requestJson = actual.toString()
                val rewrittenBody = requestJson.toRequestBody(checkNotNull(request.body?.contentType()))
                request = request.newBuilder().post(rewrittenBody).header("Content-Length", rewrittenBody.contentLength().toString()).build()
            }
            File(evidence, "request-$number.json").writeText(requestJson)
            if (sequence && activeCapture.get()?.optString("label") == "r10-real-02") {
                val expected = JSONObject(File(app.cacheDir, "r10-real-01/request-1.json").readText())
                val originalMessages = expected.getJSONArray("messages")
                val expectedMessages = JSONArray()
                for (index in 0 until originalMessages.length() - 1) expectedMessages.put(originalMessages.getJSONObject(index))
                seed.forEach { expectedMessages.put(JSONObject().put("role", it.role).put("content", it.text)) }
                expectedMessages.put(originalMessages.getJSONObject(originalMessages.length() - 1))
                expected.put("messages", expectedMessages)
                val matches = JSONObject(requestJson).toString() == expected.toString()
                File(evidence, "request-$number-preflight.json").writeText(JSONObject().put("matchesBaselinePlusSeedOnly", matches).put("expected", expected).toString(2))
                check(matches) { "Request differs from baseline plus the two approved seed messages; not submitted" }
            }
            if (sequence && activeCapture.get()?.optString("label") == "r10-real-04") {
                val expected = JSONObject(File(app.cacheDir, "r10-real-02/request-1.json").readText())
                val expectedMessages = expected.getJSONArray("messages")
                var normalized = 0
                var protocolUpdates = 0
                val addition = """
                    每轮都必须包含reply字符串和memory_candidates数组；没有可提取稳定事实时也必须输出"memory_candidates":[]，不得省略这两个键。
                    历史assistant消息由App从已经显示的台词重建为reply加memory_candidates空数组。此空数组只是历史格式占位，表示本轮不重复处理历史，不声明过去没有提取记忆；当前回合仍须按最后一条USER独立提取。
                """.trimIndent()
                for (index in 0 until expectedMessages.length()) {
                    val message = expectedMessages.getJSONObject(index)
                    val text = message.getString("content")
                    if (message.getString("role") == "assistant") {
                        message.put("content", JSONObject().put("reply", text).put("memory_candidates", JSONArray()).toString()); normalized++
                    } else if (message.getString("role") == "system" && text.startsWith("输出一个json对象，固定格式：")) {
                        val firstBreak = text.indexOf('\n'); check(firstBreak >= 0)
                        message.put("content", text.take(firstBreak + 1) + addition + "\n" + text.drop(firstBreak + 1)); protocolUpdates++
                    }
                }
                val matches = JSONObject(requestJson).toString() == expected.toString()
                File(evidence, "request-$number-preflight.json").writeText(JSONObject().put("matchesReal02WithProductHistoryAndExplicitRequiredPromptOnly", matches).put("assistantNormalizedCount", normalized).put("protocolUpdates", protocolUpdates).put("testOnlyRewrite", false).toString(2))
                check(matches && normalized == 1 && protocolUpdates == 1) { "Product request changed beyond the approved history format and required-field prompt; not submitted" }
            }
            if (resume && attempts.get() == 0) {
                val previous = JSONObject(File(app.cacheDir, "r10-real-04/request-3.json").readText())
                val current = JSONObject(requestJson)
                val fields = listOf("model", "stream", "thinking", "response_format", "max_tokens")
                val transportSame = fields.all { current.get(it).toString() == previous.get(it).toString() }
                fun nonSystem(root: JSONObject): String {
                    val selected = JSONArray(); val messages = root.getJSONArray("messages")
                    for (index in 0 until messages.length()) if (messages.getJSONObject(index).getString("role") != "system") selected.put(messages.getJSONObject(index))
                    return selected.toString()
                }
                val historySame = nonSystem(current) == nonSystem(previous)
                File(evidence, "request-$number-preflight.json").writeText(JSONObject().put("transportFieldsUnchanged", transportSame).put("nonSystemMessagesIdenticalToRecordedThirdTurn", historySame).put("testOnlyRewrite", false).put("characterState", "FRESH_FIXTURE_DEFAULT_NOT_ORIGINAL_FULL_SNAPSHOT").toString(2))
                check(transportSame && historySame) { "Resumed request differs from the recorded synthetic history or transport; not submitted" }
            }
            attempts.incrementAndGet()
            val transport = JSONObject().put("attempt", number).put("turn", activeCapture.get()?.optString("label")).put("method", request.method).put("url", request.url.toString()).put("eof", false).put("closed", false).put("bytes", 0)
            synchronized(network) { network.put(transport) }
            val response = try { chain.proceed(request) } catch (error: Exception) {
                transport.put("transportErrorType", error.javaClass.simpleName)
                throw error
            }
            transport.put("httpStatus", response.code)
            val headers = JSONObject()
            for (name in listOf("Content-Type", "X-Request-Id", "X-Ds-Request-Id", "Content-Encoding")) response.header(name)?.let { headers.put(name, it) }
            transport.put("headers", headers)
            val original = checkNotNull(response.body)
            val raw = File(evidence, "response-$number.bin").outputStream()
            val tee = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    return try {
                        val count = super.read(sink, byteCount)
                        if (count < 0) transport.put("eof", true)
                        else if (count > 0) {
                            val copy = Buffer(); sink.copyTo(copy, sink.size - count, count)
                            raw.write(copy.readByteArray())
                            transport.put("bytes", transport.getLong("bytes") + count)
                        }
                        count
                    } catch (error: Exception) { transport.put("readErrorType", error.javaClass.simpleName); throw error }
                }
                override fun close() {
                    try { super.close() } finally { raw.close(); transport.put("closed", true) }
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = original.contentType()
                override fun contentLength(): Long = original.contentLength()
                override fun source(): BufferedSource = tee
            }).build()
        }.build()
        val real = DeepSeekDialogueClient(http)
        val tapped = object : DialogueClient {
            override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
                val turn = if (purpose == RequestPurpose.SUMMARY) JSONObject().put("label", sampleId + "-summary").also { turns.put(it); activeCapture.set(it) } else checkNotNull(activeCapture.get())
                turn.put("purpose", purpose.name).put("requestId", requestId)
                return try {
                    real.stream(apiKey, model, messages, purpose, requestId, roundId, { usage ->
                        turn.put("usage", JSONObject().put("prompt", usage.prompt ?: JSONObject.NULL).put("completion", usage.completion ?: JSONObject.NULL).put("cacheHit", usage.cacheHit ?: JSONObject.NULL).put("cacheMiss", usage.cacheMiss ?: JSONObject.NULL).put("complete", usage.complete))
                        onUsage(usage)
                    }) { delta -> synchronized(decoded) { decoded.put(JSONObject().put("turn", turn.getString("label")).put("delta", delta)) }; onDelta(delta) }.also {
                        turn.put("completed", it.completed).put("memoryValid", it.memoryValid).put("memoryCandidates", JSONArray(it.memoryCandidates.map { candidate -> JSONObject().put("key", candidate.key).put("evidence", candidate.evidence) })).put("activityCommand", it.activity?.toString() ?: JSONObject.NULL).put("diagnostics", it.diagnostics?.debugSummary() ?: JSONObject.NULL)
                    }
                } catch (error: CancellationException) { turn.put("clientCancelled", true); throw error }
                catch (error: Exception) { turn.put("clientErrorType", error.javaClass.simpleName); throw error }
            }
        }
        var durable = if (natural) AppData() else resumed ?: AppData(messages = seed)
        val store = object : AppStore(app) { override suspend fun load() = durable; override suspend fun save(data: AppData) { durable = data } }
        val holder = ViewModelStore()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: ChatViewModel
        var observation: Job? = null
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, tapped); holder.put(sampleId, model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            scenario.onActivity { activity -> activity.setContent { CompanionApp(model, {}, {}, {}) } }
            observation = launch(Dispatchers.Main.immediate) {
                model.state.collect { value -> observed.put(JSONObject().put("turn", activeCapture.get()?.optString("label")).put("busy", value.busy).put("liveReply", value.liveReply).put("notice", value.notice).put("messageCount", value.data.messages.size)) }
            }
            withContext(Dispatchers.Main) { model.foreground() }
            val device = UiDevice.getInstance(instrumentation)
            suspend fun captureTurn(label: String, input: String, stopAfterDelta: Boolean = false): String {
                val turn = JSONObject().put("label", label).put("input", input).put("networkBefore", attempts.get())
                turns.put(turn); activeCapture.set(turn)
                val previousIds = model.state.value.data.messages.map { it.id }.toSet()
                withContext(Dispatchers.Main) { model.send(input) }
                val started = withTimeout(10000) { model.state.first { value -> value.data.messages.any { it.role == "user" && it.id !in previousIds } } }
                val userId = started.data.messages.single { it.role == "user" && it.id !in previousIds }.id
                turn.put("userId", userId)
                if (stopAfterDelta) {
                    val firstVisible = withTimeout(180000) { model.state.first { value -> (value.busy && value.liveReply.isNotBlank()) || (!value.busy && value.data.messages.any { it.id == userId && it.status != "pending" }) } }
                    check(firstVisible.busy && firstVisible.liveReply.isNotBlank()) { "Stop turn ended before its first live delta" }
                    turn.put("displayedAtStop", model.state.value.liveReply)
                    withContext(Dispatchers.Main) { model.stop() }
                }
                val ended = withTimeout(180000) { model.state.first { value -> !value.busy && value.data.messages.any { it.id == userId && it.status != "pending" } } }
                val messages = ended.data.messages.filter { it.id == userId || it.turnId == userId }
                if (natural) check(messages.single { it.role == "user" }.text == input) { "The recorded USER input differs from the supplied input" }
                turn.put("networkAfter", attempts.get()).put("notice", ended.notice).put("activityState", ended.data.activity.toString()).put("quiet", ended.data.activity.quiet)
                    .put("memoryState", JSONObject(StateCodec.encode(ended.data)).getJSONObject("memory"))
                    .put("messages", JSONArray(messages.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("status", it.status).put("turnId", it.turnId ?: JSONObject.NULL) }))
                if (stopAfterDelta) {
                    check(messages.first { it.id == userId }.status == "interrupted") { "Stopped user turn did not become interrupted" }
                    check(messages.none { it.role == "assistant" && it.status == "completed" }) { "Stopped turn committed a completed assistant message" }
                    val stable = ended.data
                    delay(1200)
                    check(model.state.value.data == stable) { "Late commit changed the stopped turn" }
                    turn.put("noLateCommitAfterMillis", 1200)
                } else {
                    check(turn.optBoolean("completed") && turn.optBoolean("memoryValid") && messages.first { it.id == userId }.status == "completed" && messages.any { it.role == "assistant" && it.status == "completed" && it.text.isNotBlank() }) { "Real turn did not produce a complete displayable reply with valid memory metadata" }
                    check(ended.notice.isBlank() || (rolling && ended.notice.contains("应用预算"))) { "Real turn ended with a notice" }
                }
                delay(250)
                check(device.takeScreenshot(File(evidence, "$label.png")))
                device.dumpWindowHierarchy(File(evidence, "$label.xml"))
                return userId
            }
            if (natural) {
                naturalInputs.forEachIndexed { index, input ->
                    val before = attempts.get()
                    captureTurn("$sampleId-turn-${index + 1}", input)
                    check(attempts.get() == before + 1) { "Unexpected request count; no retry is allowed" }
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("r15Progress", "TURN_COMPLETED"); putInt("turn", index + 1); putInt("networkAttempts", attempts.get())
                    })
                }
                check(attempts.get() == naturalInputs.size && turns.length() == naturalInputs.size) { "Natural sample did not complete exactly the supplied turns" }
            } else if (rolling) {
                captureTurn(sampleId, "先继续虚构故事，走近海边，蓝门暂时不开。")
                withTimeout(180000) { model.state.first { it.data.summary != null && it.data.usage.summary.requests == 1 } }
                val summarized = model.state.value.data
                check(summarized.summary!!.text.contains("灯塔") && summarized.summary.text.contains("蓝门")) { "Summary lost the important synthetic story locations" }
                check(summarized.memory.items.isEmpty()) { "Synthetic story became a user fact" }
                withContext(Dispatchers.Main) { model.dismissNotice() }
                val recalledId = captureTurn(sampleId + "-continuation", "我们虚构故事中的银色钥匙到底藏在哪，黄昏准备用它开什么？只按故事回答。")
                val recalled = model.state.value.data.messages.filter { it.turnId == recalledId && it.role == "assistant" }.joinToString { it.text }
                check(recalled.contains("灯塔") && recalled.contains("第三") && recalled.contains("蓝门")) { "Continuation did not retain the important synthetic story event" }
                val ledger = model.state.value.data.usage
                check(ledger.chat.requests == 2 && ledger.summary.requests == 1 && ledger.chat.unpriced == 0 && ledger.summary.unpriced == 0) { "Actual chat and summary usage was not completely recorded" }
                check(model.state.value.data.memory.items.isEmpty()) { "Summary or story became user facts" }
                check(attempts.get() == 3) { "Unexpected request count; no retry is allowed" }
                receipt.put("importantStoryContinuation", true).put("summaryIsNotUserFact", true).put("bothPurposesActualUsage", true)
            } else if (!resume) captureTurn(sampleId, "哈喽?")
            if (sequence || resume) {
                if (!resume) {
                    val factInput = "我最想去青岛看海。陪我安静看会儿比赛吧"
                    val factId = captureTurn("r10-accept-activity", factInput)
                    check(model.state.value.data.activity.type == ActivityType.WATCH && model.state.value.data.activity.phase == ActivityPhase.ACTIVE && model.state.value.data.activity.quiet) { "Explicit quiet WATCH activity was not applied" }
                    check(model.state.value.data.memory.items.any { item -> item.status == MemoryStatus.ACTIVE && item.text.contains("青岛") && factInput.contains(item.text) && item.sources.any { source -> source.id == factId && source.kind == MemorySourceKind.USER_MESSAGE && source.text == factInput } }) { "The current synthetic fact was not committed with its exact USER source" }
                }
                val recallId = captureTurn("r10-accept-recall-finish", "我刚才说最想去哪里？比赛看完啦，我们结束陪看吧，可以多说几句。")
                check(model.state.value.data.messages.any { it.turnId == recallId && it.role == "assistant" && it.text.contains("青岛") }) { "Reply did not recall the synthetic destination" }
                check(model.state.value.data.activity.type != ActivityType.WATCH || model.state.value.data.activity.phase != ActivityPhase.ACTIVE) { "WATCH activity did not finish" }
                captureTurn("r10-accept-stop", "给我讲一个完整的、稍微长一点的海边小故事吧。", stopAfterDelta = true)
                captureTurn("r10-accept-resend", "我回来啦，我们继续聊天。")
            }
            receipt.put("notice", model.state.value.notice).put("busy", model.state.value.busy).put("liveReply", model.state.value.liveReply)
                .put("usage", JSONObject(StateCodec.encode(durable)).getJSONObject("usage")).put("summary", JSONObject(StateCodec.encode(durable)).opt("summary"))
                .put("messages", JSONArray(durable.messages.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("status", it.status).put("turnId", it.turnId ?: JSONObject.NULL) }))
            check(device.takeScreenshot(File(evidence, "home.png")))
            device.dumpWindowHierarchy(File(evidence, "home.xml"))
        } catch (error: Throwable) {
            receipt.put("failureType", error.javaClass.simpleName).put("failedTurn", activeCapture.get()?.optString("label"))
            throw error
        } finally {
            observation?.cancelAndJoin()
            scenario.close()
            withContext(Dispatchers.Main) { holder.clear() }
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
            receipt.put("seedMessages", JSONArray(seed.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("time", it.time) }))
                .put("storageScope", "IN_MEMORY_APPSTORE_ONLY").put("musicIntegratedBuild", sequence || wrappedHistory || resume).put("testOnlyWrappedHistory", wrappedHistory).put("resumedFromRecordedReal04", resume)
                .put("seedTimestampPolicy", if (natural) "DEFAULT_APPDATA_NO_SEED" else if (resume) "FACT_USER_ID_TEXT_TIME_AND_MEMORY_PRESERVED; OTHER_TURN_TIMES_RECONSTRUCTED; CHARACTER_FRESH_DEFAULT" else "NEW_SYNTHETIC_SEED").put("turns", turns)
                .put("networkAttempts", attempts.get()).put("network", network).put("decodedDeltas", decoded).put("observedVmStates", observed)
            File(evidence, "receipt.json").writeText(receipt.toString(2))
        }
    }

    @Test fun protocolFailureKeepsOnlyVisiblePartialAndNeverCommitsMemoryActivityOrRetries() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(app.cacheDir, "r16-protocol-errors")
        check(!evidence.exists()); check(evidence.mkdirs())
        val receipt = JSONObject().put("networkCalls", 0).put("storageScope", "IN_MEMORY_APPSTORE_ONLY")
        val cases = JSONArray()
        val secrets = SecretStore(app); secrets.write("deepseek", "fixture-not-real-key")
        try {
            for (partial in listOf(true, false)) {
                val initial = AppData(activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.READ)))
                var latest = initial
                val writes = mutableListOf<AppData>()
                val store = object : AppStore(app) {
                    override suspend fun load() = latest
                    override suspend fun save(data: AppData) { latest = data; writes += data }
                }
                val calls = AtomicInteger()
                val fake = object : DialogueClient {
                    override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
                        calls.incrementAndGet()
                        if (partial) onDelta("先陪你歇会儿。\n\n这段尚未显示。")
                        throw DialogueProtocolException()
                    }
                }
                val holder = ViewModelStore()
                val scenario = ActivityScenario.launch(MainActivity::class.java)
                lateinit var model: ChatViewModel
                try {
                    withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("protocol-$partial", model) }
                    withTimeout(10000) { model.state.first { it.loaded } }
                    scenario.onActivity { activity -> activity.setContent { CompanionApp(model, {}, {}, {}) } }
                    withContext(Dispatchers.Main) { model.foreground(); model.send("我每天喝绿茶。陪我一起工作吧。") }
                    val ended = withTimeout(10000) { model.state.first { !it.busy && it.data.messages.any { message -> message.role == "user" && message.status == "failed" } } }
                    val user = latest.messages.single { it.role == "user" }
                    val replies = latest.messages.filter { it.role == "assistant" }
                    assertEquals(1, calls.get()); assertEquals("failed", user.status)
                    assertEquals(initial.memory, latest.memory); assertEquals(initial.activity, latest.activity)
                    assertTrue(writes.all { it.memory == initial.memory && it.activity == initial.activity })
                    assertEquals(latest, ended.data); assertEquals("", ended.liveReply)
                    assertTrue(ended.notice.startsWith("这次回复格式出了点问题")); assertFalse(ended.notice.contains("Key"))
                    assertTrue(ended.notice.contains("这轮没有新增记忆"))
                    if (partial) {
                        assertEquals(listOf("先陪你歇会儿。"), replies.map { it.text })
                        assertTrue(replies.all { it.status == "interrupted" && it.turnId == user.id })
                        assertTrue(ended.notice.contains("前面的话已保留"))
                    } else {
                        assertTrue(replies.isEmpty()); assertTrue(ended.notice.contains("没有接上"))
                        assertFalse(ended.notice.contains("已保留"))
                    }
                    check(device.wait(Until.hasObject(By.text(ended.notice)), 5000))
                    val name = if (partial) "partial" else "empty"
                    check(device.takeScreenshot(File(evidence, "$name.png")))
                    device.dumpWindowHierarchy(File(evidence, "$name.xml"))
                    cases.put(JSONObject().put("partial", partial).put("calls", calls.get()).put("notice", ended.notice)
                        .put("userStatus", user.status).put("memoryUnchanged", latest.memory == initial.memory).put("activityUnchanged", latest.activity == initial.activity)
                        .put("replies", JSONArray(replies.map { JSONObject().put("text", it.text).put("status", it.status) })))
                } finally { scenario.close(); withContext(Dispatchers.Main) { holder.clear() } }
            }
        } finally {
            secrets.write("deepseek", "")
            File(evidence, "receipt.json").writeText(receipt.put("cases", cases).toString(2))
        }
    }

    @Test fun touchKeepsNoticeAndListeningPlaybackStateWithoutDialogue() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val saved = AppData()
        var latest = saved
        val store = object : AppStore(app) {
            override suspend fun load() = latest
            override suspend fun save(data: AppData) { latest = data }
        }
        val fake = FixtureDialogue()
        val holder = ViewModelStore()
        lateinit var model: ChatViewModel
        lateinit var before: CompanionUiState
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("touch-fixture", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) {
                model.notify("隔离错误提示，触摸不能覆盖")
                model.onListeningChanged(true); model.onPlayingChanged(true)
                before = model.state.value
                model.touch()
            }
            withTimeout(5000) { model.state.first { it.data.character.lastTouchAtMillis != null } }
            val after = model.state.value
            assertEquals(before.copy(data = before.data.copy(character = after.data.character)), after)
            assertEquals("隔离错误提示，触摸不能覆盖", model.state.value.notice)
            assertTrue(model.state.value.listening); assertTrue(model.state.value.speaking)
            assertEquals(saved.messages, latest.messages); assertEquals(saved.memory, latest.memory)
            assertTrue(fake.inputs.isEmpty())
        } finally { withContext(Dispatchers.Main) { holder.clear() } }
    }

    @Test fun failedStartupWriteStillExposesExactSavedDataAndBackup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val saved = AppData(messages = listOf(SavedMessage("old-pending", "user", "隔离未完成资料", 1000, "pending")))
        val loadGate = CompletableDeferred<Unit>()
        var saves = 0
        val store = object : AppStore(app) {
            override suspend fun load(): AppData { loadGate.await(); return saved }
            override suspend fun save(data: AppData) { saves++; throw java.io.IOException("isolated startup save failure") }
        }
        val fake = FixtureDialogue(); val holder = ViewModelStore()
        lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("startup-fixture", model); model.foreground() }
            loadGate.complete(Unit)
            withTimeout(10000) { model.state.first { it.loaded } }
            assertEquals(saved, model.state.value.data)
            assertTrue(model.state.value.notice.contains("启动恢复未保存"))
            assertEquals(saved, StateCodec.decode(model.exportBackup()))
            assertEquals(1, saves)
            assertTrue(fake.inputs.isEmpty())
        } finally { withContext(Dispatchers.Main) { holder.clear() } }
    }
    private class PausingStore(context: Context) : AppStore(context) {
        var pauseNextRestore = false
        val entered = CompletableDeferred<Unit>()
        val resumeSave = CompletableDeferred<Unit>()
        val writes = mutableListOf<AppData>()
        override suspend fun save(data: AppData) {
            if (pauseNextRestore && data.messages.any { it.id == "restored" }) {
                pauseNextRestore = false; entered.complete(Unit); resumeSave.await()
            }
            super.save(data); writes.add(data)
        }
    }
    private class FixtureDialogue : DialogueClient {
        val inputs = mutableListOf<String>()
        val contexts = mutableListOf<List<DialogueMessage>>()
        var activity: ActivityCommand? = null
        var reply = "隔离测试回复，不是真实API。"
        var overflow = false
        var emptyReply = false
        var candidates = emptyList<DialogueMemoryCandidate>()
        var enteredResult: CompletableDeferred<Unit>? = null
        var releaseResult: CompletableDeferred<Unit>? = null
        override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
            inputs.add(messages.last().content)
            contexts.add(messages)
            if (emptyReply) return DialogueResult(false)
            if (overflow) { onDelta("好".repeat(100000)); onDelta("超限") }
            else onDelta(reply)
            val result = DialogueResult(true, candidates, memoryValid = true, activity = activity)
            val entered = enteredResult; val release = releaseResult
            if (entered != null && release != null) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            return result
        }
    }
    @Test fun activityLocalPauseQuietAndOneTurnQuestionContextUseOneRequest() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var latest = AppData()
        val store = object : AppStore(app) { override suspend fun load() = latest; override suspend fun save(data: AppData) { latest = data } }
        val fake = FixtureDialogue(); val holder = ViewModelStore(); val secrets = SecretStore(app)
        secrets.write("deepseek", "fixture-not-real-key")
        lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("activity", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.foreground(); model.showIdleQuestion(); model.userInteraction() }
            assertTrue(model.state.value.localQuestion.isEmpty())
            fake.activity = ActivityCommand(ActivityAction.START, ActivityType.READ)
            withContext(Dispatchers.Main) { model.send("陪我看书") }
            withTimeout(10000) { model.state.first { !it.busy && it.data.activity.type == ActivityType.READ } }
            assertEquals(1, fake.inputs.size)
            assertTrue(fake.contexts.single().any { it.role == "assistant" && it.content.contains("还在忙呀") })
            assertTrue(latest.messages.none { it.text.contains("还在忙呀") }); assertTrue(latest.memory.items.isEmpty())
            withContext(Dispatchers.Main) { model.showIdleQuestion(); model.rotateCompanionScene() }
            assertTrue(model.state.value.localQuestion.isEmpty()); assertEquals(0, latest.activity.autonomousScene)
            withContext(Dispatchers.Main) { model.send("暂停") }
            withTimeout(5000) { model.state.first { it.data.activity.phase == ActivityPhase.PAUSED } }
            assertEquals(1, fake.inputs.size)
            withContext(Dispatchers.Main) { model.send("安静"); model.showIdleQuestion() }
            withTimeout(5000) { model.state.first { it.data.activity.silent } }
            assertTrue(model.state.value.localQuestion.isEmpty())
            fake.activity = ActivityCommand(ActivityAction.CONTINUE)
            withContext(Dispatchers.Main) { model.send("继续吧") }
            withTimeout(10000) { model.state.first { !it.busy && it.data.activity.phase == ActivityPhase.ACTIVE && !it.data.activity.silent } }
            assertTrue(fake.contexts.last().none { it.content.contains("还在忙呀") && it.role == "assistant" })
            // A locally newer pause cancels a non-cooperative late result, including its metadata.
            fake.activity = ActivityCommand(ActivityAction.SWITCH, ActivityType.WATCH)
            fake.enteredResult = CompletableDeferred(); fake.releaseResult = CompletableDeferred()
            withContext(Dispatchers.Main) { model.send("换成陪看") }
            withTimeout(10000) { fake.enteredResult!!.await() }
            withContext(Dispatchers.Main) { model.send("暂停") }
            withTimeout(5000) { model.state.first { it.data.activity.phase == ActivityPhase.PAUSED } }
            fake.releaseResult!!.complete(Unit)
            withContext(Dispatchers.Main) { yield() }
            assertEquals(ActivityType.READ, latest.activity.type); assertEquals(ActivityPhase.PAUSED, latest.activity.phase)
        } finally { fake.releaseResult?.complete(Unit); withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "") }
    }

    @Test fun wallClockReunionConsumedOnceAndImportedDepartureIsDiscarded() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val time = System.currentTimeMillis()
        var latest = AppData(activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.READ)), departedAtMillis = time - 259_200_000L)
        val store = object : AppStore(app) { override suspend fun load() = latest; override suspend fun save(data: AppData) { latest = data } }
        val fake = FixtureDialogue(); val holder = ViewModelStore()
        lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("reunion", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.foreground(time) }
            withTimeout(5000) { model.state.first { it.data.departedAtMillis == null } }
            assertEquals(ActivityPhase.AWAITING_CONTINUE, latest.activity.phase)
            assertTrue(latest.messages.single().text.contains("要继续吗"))
            val greeted = latest.messages
            withContext(Dispatchers.Main) { model.foreground(time + 1); model.background(time + 2); model.foreground(time + 3) }
            withContext(Dispatchers.Main) { yield() }
            assertEquals(greeted, latest.messages); assertNull(latest.departedAtMillis)
            val imported = latest.copy(departedAtMillis = time - 300_000_000L, messages = latest.messages + SavedMessage("pending-import", "user", "隔离未完成", time, "pending"))
            withContext(Dispatchers.Main) { model.restoreBackup(StateCodec.encode(imported)) }
            assertNull(latest.departedAtMillis); assertEquals("interrupted", latest.messages.last().status)
            withContext(Dispatchers.Main) { model.dismissNotice(); model.foreground(time + 4) }
            assertEquals(imported.messages.size, latest.messages.size)
            assertTrue(fake.inputs.isEmpty())
        } finally { withContext(Dispatchers.Main) { holder.clear() } }
    }

    @Test fun oneReplyPublishesAtMostThreeMessagesWithOneMetadataCommit() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var latest = AppData()
        val writes = mutableListOf<AppData>()
        val store = object : AppStore(app) { override suspend fun load() = latest; override suspend fun save(data: AppData) { latest = data; writes += data } }
        val fake = FixtureDialogue().apply { reply = "第一条。\n\n第二条。\n\n第三条。\n\n尾文不能丢。"; activity = ActivityCommand(ActivityAction.START, ActivityType.READ); candidates = listOf(DialogueMemoryCandidate("user.drink", "我每天喝绿茶。")) }
        val secrets = SecretStore(app); secrets.write("deepseek", "fixture-not-real-key")
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("paragraphs", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            scenario.onActivity { activity -> activity.setContent { CompanionApp(model, {}, {}, {}) } }
            withContext(Dispatchers.Main) { model.foreground(); model.send("我每天喝绿茶。"); model.draftChanged("") }
            withTimeout(10000) { model.state.first { it.data.messages.count { m -> m.role == "assistant" } == 1 } }
            assertTrue(model.state.value.busy); assertEquals("第一条。", latest.messages.last().text)
            assertEquals(1L, latest.memory.revision); assertEquals(1L, latest.activity.revision)
            withTimeout(10000) { model.state.first { !it.busy && it.data.messages.count { m -> m.role == "assistant" } == 3 } }
            val userId = latest.messages.single { it.role == "user" }.id
            val replies = latest.messages.filter { it.role == "assistant" }
            assertEquals(listOf("第一条。", "第二条。", "第三条。\n\n尾文不能丢。"), replies.map { it.text })
            assertTrue(replies.all { it.turnId == userId }); assertEquals(1, fake.inputs.size)
            assertEquals(1L, latest.memory.revision); assertEquals(1L, latest.activity.revision)
            assertTrue(writes.filter { it.messages.any { m -> m.role == "assistant" } }.all { it.memory == latest.memory && it.activity == latest.activity })
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.hasObject(By.text("第三条。\n\n尾文不能丢。")), 5000))
            val evidence = java.io.File(app.cacheDir, "device-evidence").apply { mkdirs() }
            assertTrue(device.takeScreenshot(java.io.File(evidence, "r8-multiple-home-messages.png")))
        } finally { scenario.close(); withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "") }
    }

    @Test fun interruptionNeverPersistsHiddenParagraphsAndLaterSaveFailureKeepsFirst() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var latest = AppData()
        var rejectTail = false
        val store = object : AppStore(app) {
            override suspend fun load() = latest
            override suspend fun save(data: AppData) {
                if (rejectTail && data.messages.count { it.role == "assistant" } > 1) throw java.io.IOException("expected isolated tail save failure")
                latest = data
            }
        }
        val fake = FixtureDialogue().apply { reply = "已显示首段。\n\n隐藏第二段。\n\n隐藏第三段。" }
        val secrets = SecretStore(app); secrets.write("deepseek", "fixture-not-real-key")
        val holder = ViewModelStore(); lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("interrupt-paragraphs", model) }
            withTimeout(10000) { model.state.first { it.loaded } }
            fake.enteredResult = CompletableDeferred(); fake.releaseResult = CompletableDeferred()
            withContext(Dispatchers.Main) { model.foreground(); model.send("首段流式中断") }
            withTimeout(10000) { fake.enteredResult!!.await() }
            assertEquals("已显示首段。", model.state.value.liveReply)
            withContext(Dispatchers.Main) { model.stop() }
            fake.releaseResult!!.complete(Unit)
            withTimeout(5000) { model.state.first { it.data.messages.any { m -> m.role == "assistant" && m.status == "interrupted" } } }
            assertTrue(latest.messages.none { it.text.contains("隐藏") })
            fake.enteredResult = null; fake.releaseResult = null
            for (action in listOf("draft", "stop", "background", "send", "restore")) {
                withContext(Dispatchers.Main) { model.restoreBackup(StateCodec.encode(AppData())); model.dismissNotice(); model.foreground(); model.send("cancel-$action") }
                withTimeout(5000) { model.state.first { it.busy && it.data.messages.count { m -> m.role == "assistant" } == 1 } }
                withContext(Dispatchers.Main) {
                    when (action) {
                        "draft" -> model.draftChanged("新的输入")
                        "stop" -> model.stop()
                        "background" -> model.background()
                        "restore" -> model.restoreBackup(StateCodec.encode(AppData(messages = listOf(SavedMessage("retained", "user", "恢复事实", 1)))))
                        else -> { fake.reply = "新回合。"; model.send("new-turn") }
                    }
                }
                delay(900)
                assertTrue("${action}不能保留隐藏尾段", latest.messages.none { it.text.contains("隐藏") })
                assertFalse(model.state.value.busy)
                fake.reply = "已显示首段。\n\n隐藏第二段。\n\n隐藏第三段。"
            }
            withContext(Dispatchers.Main) { model.restoreBackup(StateCodec.encode(AppData())); model.dismissNotice(); model.foreground() }
            rejectTail = true
            withContext(Dispatchers.Main) { model.send("expected-tail-save-failure") }
            withTimeout(10000) { model.state.first { !it.busy && it.notice.contains("后续回复未能保存") } }
            assertEquals(listOf("已显示首段。"), latest.messages.filter { it.role == "assistant" }.map { it.text })
            assertEquals(latest, model.state.value.data)
        } finally { fake.releaseResult?.complete(Unit); withContext(Dispatchers.Main) { holder.clear() }; secrets.write("deepseek", "") }
    }
    @Test fun semanticBatchUsesOneRequestAndRejectsLateResultsAfterForgetAndRestore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val store = AppStore(app); val fake = FixtureDialogue(); val holder = ViewModelStore(); val secrets = SecretStore(app)
        val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        secrets.write("deepseek", "fixture-not-real-key")
        store.save(AppData(reunionDay = LocalDate.now().toString()))
        lateinit var model: ChatViewModel
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("semantic-fixture", model); model.foreground() }
            withTimeout(10000) { model.state.first { it.loaded } }
            fake.candidates = listOf(DialogueMemoryCandidate("user.preference.drink", "我每天午饭后喝绿茶。"), DialogueMemoryCandidate("user.preference.music", "我平常听爵士乐。"))
            withContext(Dispatchers.Main) { model.send("我每天午饭后喝绿茶。我平常听爵士乐。") }
            withTimeout(10000) { model.state.first { !it.busy && it.data.memory.items.size == 2 } }
            val first = store.load(); val sourceId = first.messages.single { it.role == "user" }.id
            assertEquals(1, fake.inputs.size); assertEquals(2L, first.memory.revision)
            assertTrue(first.memory.items.all { it.sources.single().id == sourceId })
            assertTrue(first.messages.all { it.status == "completed" })
            fake.candidates = listOf(DialogueMemoryCandidate("user.late", "我固定周末晨跑。"))
            fake.enteredResult = CompletableDeferred(); fake.releaseResult = CompletableDeferred()
            withContext(Dispatchers.Main) { model.send("我固定周末晨跑。") }
            withTimeout(10000) { fake.enteredResult!!.await() }
            withContext(Dispatchers.Main) { model.editMemory(first.memory.items.first().id, null) }
            withTimeout(10000) { model.state.first { it.data.memory.revision > first.memory.revision } }
            fake.releaseResult!!.complete(Unit)
            withContext(Dispatchers.Main) { yield() }
            assertTrue(store.load().memory.items.none { it.key == "user.late" })
            fake.enteredResult = CompletableDeferred(); fake.releaseResult = CompletableDeferred()
            withContext(Dispatchers.Main) { model.send("我固定周末晨跑。") }
            withTimeout(10000) { fake.enteredResult!!.await() }
            val restore = tasks.launch { model.restoreBackup(StateCodec.encode(AppData(reunionDay = LocalDate.now().toString()))) }
            withTimeout(10000) { restore.join() }
            fake.releaseResult!!.complete(Unit)
            withContext(Dispatchers.Main) { yield() }
            assertTrue(store.load().memory.items.isEmpty()); assertEquals(0L, store.load().memory.revision)
            assertEquals(3, fake.inputs.size)
        } finally {
            fake.releaseResult?.complete(Unit)
            withContext(Dispatchers.Main) { holder.clear() }
            tasks.cancel(); secrets.write("deepseek", ""); store.save(AppData())
        }
    }
    @Test fun queuedSendUsesRestoredSessionAndOversizeReplyStaysRecoverable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val store = PausingStore(app); val fake = FixtureDialogue(); val holder = ViewModelStore()
        val secrets = SecretStore(app)
        secrets.write("deepseek", "fixture-not-real-key")
        store.save(AppData(memory = MemoryState(revision = 5), reunionDay = LocalDate.now().toString()))
        lateinit var model: ChatViewModel
        val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main) { model = ChatViewModel(app, store, fake); holder.put("fixture", model); model.foreground() }
            withTimeout(10000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.send("before-restore") }
            withTimeout(10000) { model.state.first { s -> s.data.messages.any { it.role == "assistant" && it.status == "completed" } && !s.busy } }
            val target = AppData(messages = listOf(SavedMessage("restored", "user", "恢复的隔离资料", 1000)), reunionDay = LocalDate.now().toString())
            store.pauseNextRestore = true
            val restore = tasks.launch { model.restoreBackup(StateCodec.encode(target)) }
            withTimeout(10000) { store.entered.await() }
            withContext(Dispatchers.Main) { model.send("queued-after-restore") }
            store.resumeSave.complete(Unit)
            withTimeout(10000) { restore.join(); model.state.first { s -> s.data.messages.any { it.role == "user" && it.text == "queued-after-restore" && it.status == "completed" } && !s.busy } }
            assertEquals(listOf("before-restore", "queued-after-restore"), fake.inputs)
            val after = store.load()
            assertEquals(0L, after.memory.revision)
            assertEquals(1, after.messages.count { it.text == "queued-after-restore" })
            assertEquals(1, store.writes.count { d -> d.messages.any { it.text == "queued-after-restore" && it.status == "pending" } })
            fake.overflow = true
            withContext(Dispatchers.Main) { model.send("oversize-fixture") }
            withTimeout(10000) { model.state.first { s -> !s.busy && s.data.messages.any { it.role == "assistant" && it.status == "interrupted" && it.text.length == 100000 } } }
            val persisted = store.load()
            assertTrue(persisted.messages.all { it.text.length <= 100000 })
            assertEquals(persisted, StateCodec.decode(StateCodec.encode(persisted)))
            assertTrue(model.state.value.notice.contains("容量"))
            fake.overflow = false; fake.emptyReply = true
            withContext(Dispatchers.Main) { model.send("empty-fixture") }
            withTimeout(10000) { model.state.first { !it.busy && it.notice.contains("没有返回可显示") } }
            assertEquals(persisted.memory.revision, store.load().memory.revision)
            assertTrue(store.load().messages.none { it.role == "assistant" && it.text.isBlank() })
            assertEquals(1, fake.inputs.count { it == "empty-fixture" })
        } finally {
            withContext(Dispatchers.Main) { holder.clear() }
            tasks.cancel(); secrets.write("deepseek", ""); store.save(AppData())
        }
    }
}
