package cn.xingban.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.xingban.app.data.*
import cn.xingban.app.integrations.*
import cn.xingban.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.time.LocalDate

data class CompanionUiState(
    val loaded: Boolean = false, val data: AppData = AppData(), val busy: Boolean = false,
    val liveReply: String = "", val listening: Boolean = false, val speaking: Boolean = false,
    val partial: String = "", val notice: String = "", val expression: Expression = Expression.NEUTRAL,
    val hasDeepSeekKey: Boolean = false, val hasMiniMaxKey: Boolean = false, val hasGoKey: Boolean = false,
    val usageSavePending: Boolean = false,
    val localQuestion: String = "", val musicPlaying: Boolean = false,
    val musicTrack: BackgroundMusicTrack = BackgroundMusicTrack.MEDITATION_IMPROMPTU_01,
    val qqMusic: QqMusicState = QqMusicState(),
) {
    val hasSelectedChatKey: Boolean get() = if (data.settings.provider == "OpenCode Go") hasGoKey else hasDeepSeekKey
}
private class NoDisplayableReplyException : java.io.IOException()
private val replyParagraphBreak = Regex("\n[\\t \\r]*\n+")
internal fun companionReplyParts(raw: String): List<String> = raw.trim().split(replyParagraphBreak, limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
private fun firstCompanionParagraph(raw: String): String = raw.take(replyParagraphBreak.find(raw)?.range?.first ?: raw.length).trim()

/** The main dispatcher owns the session; the mutex serializes complete durable snapshots. */
class ChatViewModel internal constructor(application: Application, private val store: AppStore, private val dialogue: DialogueClient, private val phone: PhoneRuntime = PhoneRuntime(application)) : AndroidViewModel(application), SpeechRuntime.Listener {
    constructor(application: Application) : this(application, AppStore(application), DeepSeekDialogueClient())
    private val secrets = SecretStore(application)
    @Volatile private var session = CompanionSession()
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(CompanionUiState())
    val state = mutable.asStateFlow()
    private val speech = SpeechRuntime(application, this)
    private var request: Job? = null
    @Volatile private var requestOwner: String? = null
    private var currentUserId: String? = null
    @Volatile private var visible = false
    private var visibilityRevision = 0L
    private var localDeparture: Long? = null
    private var homeAvailable = true
    private var pendingLocalQuestion: String? = null
    private var recognitionHeld = false
    private var logicalTime = 0L
    private val providerSessionId = UUID.randomUUID().toString()
    private fun id() = UUID.randomUUID().toString()
    private fun now(): Long {
        val d = state.value.data
        val floor = maxOf(logicalTime, d.messages.maxOfOrNull { it.time } ?: 0L, d.memory.contextBoundaryMillis ?: 0L, d.character.updatedAtMillis)
        return maxOf(System.currentTimeMillis(), Math.incrementExact(floor)).also { logicalTime = it }
    }
    private fun update(block: (CompanionUiState) -> CompanionUiState) { mutable.value = block(mutable.value) }

    init {
        viewModelScope.launch { phone.state.collect { music -> update { it.copy(qqMusic = music) } } }
        viewModelScope.launch {
            var allowForeground = false
            try {
                mutex.withLock {
                    val saved = store.load()
                    logicalTime = maxOf(saved.messages.maxOfOrNull { it.time } ?: 0, saved.memory.contextBoundaryMillis ?: 0, saved.character.updatedAtMillis)
                    val recovered = saved.copy(messages = saved.messages.map { if (it.status == "pending") it.copy(status = "interrupted") else it }, character = CharacterReducer.advance(saved.character, now()))
                    try {
                        store.save(recovered)
                        update { it.copy(loaded = true, data = recovered, hasDeepSeekKey = secrets.has("deepseek"), hasMiniMaxKey = secrets.has("minimax"), hasGoKey = secrets.has("opencode_go")) }
                        allowForeground = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        update { it.copy(loaded = true, data = saved, hasDeepSeekKey = secrets.has("deepseek"), hasMiniMaxKey = secrets.has("minimax"), hasGoKey = secrets.has("opencode_go"), notice = "原存档已读取，启动恢复未保存；可导出或恢复备份，未续发未完成对话。") }
                    }
                }
                if (allowForeground) {
                    val settings = state.value.data.settings
                    speech.configureMusic(settings.musicEnabled, settings.musicVolume, settings.musicTrack, musicSource = settings.musicSource)
                    speech.setForeground(visible)
                    if (visible) foreground()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { update { it.copy(notice = "本地数据读取失败。原数据已保留，请重新打开应用。") } }
        }
    }
    private suspend fun commit(data: AppData) { store.save(data); update { it.copy(data = data, usageSavePending = false) } }
    fun dismissNotice() { update { it.copy(notice = "") } }
    fun notify(message: String) { update { it.copy(notice = message) } }

    fun foreground(wallTimeMillis: Long = System.currentTimeMillis()) {
        visible = true
        val visibility = ++visibilityRevision
        if (!state.value.loaded) return
        speech.setForeground(true)
        phone.setForeground(true)
        viewModelScope.launch {
            mutex.withLock {
                if (!visible || visibility != visibilityRevision) return@withLock
                val d = state.value.data
                val departed = localDeparture ?: d.departedAtMillis
                if (departed == null) return@withLock
                val away = CompanionReunion.elapsed(departed, wallTimeMillis)
                val activity = d.activity.returnedAfter(away)
                val reply = if (homeAvailable && !state.value.busy && !state.value.listening && !state.value.speaking && state.value.notice.isBlank()) CompanionReunion.reply(away, activity) else null
                try {
                    commit(d.copy(activity = activity, departedAtMillis = null, reunionShownAt = if (reply != null) wallTimeMillis else d.reunionShownAt, messages = d.messages + if (reply != null) listOf(SavedMessage(id(), "assistant", reply, now(), "local")) else emptyList()))
                    localDeparture = null
                    if (reply != null) update { it.copy(expression = Expression.PLAYFUL) }
                } catch (_: Exception) { notify("重逢状态保存失败，请重试打开应用。") }
            }
        }
    }
    fun background(wallTimeMillis: Long = System.currentTimeMillis()) {
        visible = false
        val visibility = ++visibilityRevision
        localDeparture = wallTimeMillis
        pendingLocalQuestion = null
        dismissLocalQuestion()
        speech.setForeground(false)
        phone.setForeground(false)
        stop()
        viewModelScope.launch {
            mutex.withLock {
                if (!state.value.loaded || visible || visibility != visibilityRevision) return@withLock
                try { commit(state.value.data.copy(departedAtMillis = wallTimeMillis)) }
                catch (_: Exception) { notify("离开时间未保存，原有数据已保留。") }
            }
        }
    }
    fun setHomeAvailable(available: Boolean) { homeAvailable = available; if (!available) dismissLocalQuestion() }
    fun dismissLocalQuestion() { update { it.copy(localQuestion = "") } }
    fun userInteraction() { dismissLocalQuestion() }
    fun draftChanged(text: String) { userInteraction(); if (text.isNotEmpty() && (state.value.busy || request?.isActive == true)) stop() }
    fun showIdleQuestion() {
        val s = state.value
        if (!visible || !homeAvailable || !s.loaded || s.busy || s.listening || s.speaking || s.notice.isNotBlank() || s.data.activity.quiet) return
        val line = "还在忙呀？要不要和我说两句。"
        pendingLocalQuestion = line
        update { it.copy(localQuestion = line) }
    }
    fun rotateCompanionScene() {
        viewModelScope.launch {
            mutex.withLock {
                if (!visible || !homeAvailable || state.value.busy || state.value.listening || state.value.speaking) return@withLock
                val d = state.value.data; val activity = d.activity.rotate()
                if (activity != d.activity) try { commit(d.copy(activity = activity)) } catch (_: Exception) { notify("陪伴姿态未保存，原有数据已保留。") }
            }
        }
    }
    fun stop() {
        session.stop(); requestOwner = null; request?.cancel(); request = null
        speech.invalidateGeneration(); recognitionHeld = false
        val partial = state.value.liveReply; val userId = currentUserId; currentUserId = null
        update { it.copy(busy = false, liveReply = "", partial = "", listening = false, speaking = false, expression = Expression.NEUTRAL) }
        viewModelScope.launch {
            mutex.withLock {
                if (!state.value.loaded) return@withLock
                val d = state.value.data
                val messages = d.messages.map { if (it.id == userId && it.status == "pending") it.copy(status = "interrupted") else it } +
                    if (partial.isNotBlank() && d.messages.none { it.role == "assistant" && it.turnId == userId && it.status == "completed" }) listOf(SavedMessage(id(), "assistant", partial, now(), "interrupted", userId)) else emptyList()
                try { commit(d.copy(messages = messages, character = CharacterReducer.reduce(d.character, CharacterEvent(id(), CharacterEventKind.STOP, now())))) }
                catch (_: Exception) { notify("停止已生效，部分文本保存失败。") }
            }
        }
    }
    fun send(raw: String) {
        val text = raw.trim(); if (text.isBlank() || !state.value.loaded) return
        if (text.length > 12000) { notify("一次最多输入 12000 个字符。"); return }
        DeviceCommands.parse(text)?.let { command ->
            if (command is DeviceCommand.SetQqMusicVolume) {
                val scope = phone.state.value.volumeScope
                if (scope == null) sendLocalCommand(text, null, "当前QQ音乐没有可控制的音量目标，未执行音量操作。")
                else sendLocalCommand(text, command.copy(expectedScope = scope))
            } else sendLocalCommand(text, command)
            return
        }
        if (Regex("(?:(?:请|帮我|请帮我) *)?(?:设置.*闹钟|QQ音乐音量.*|打开QQ音乐.*|播放QQ音乐.*|暂停QQ音乐.*|控制手机.*)").matches(text)) { sendLocalCommand(text, null); return }
        val decision = BehaviorSelector.select(BehaviorInput(userText = text))
        if (decision.kind == BehaviorKind.STOP) {
            stop(); userInteraction()
            val silence = text.trimEnd('。', '！', '!', '，', ',', '.', ' ') in setOf("安静", "先别说话", "不要说了", "别说了")
            viewModelScope.launch {
                mutex.withLock {
                    val d = state.value.data; val activity = d.activity.localStop(silence)
                    if (activity != d.activity) try { commit(d.copy(activity = activity)) } catch (_: Exception) { notify("停止已生效，陪伴状态未保存。") }
                }
            }
            return
        }
        if (!state.value.hasSelectedChatKey) { notify("请先在设置中填写自己的 ${state.value.data.settings.provider} API Key，保存后即可聊天。"); return }
        stop()
        val owner = id()
        requestOwner = owner
        request = viewModelScope.launch {
            var token: TurnToken? = null
            lateinit var turnSession: CompanionSession
            fun active() = requestOwner == owner && token?.let { session === turnSession && turnSession.isCurrent(it) } == true
            var userId = ""
            var activityRevision = 0L
            var firstCommitted = false
            var committedMemoryRevision: Long? = null
            val answer = StringBuilder(); val sentence = StringBuilder()
            val speechGate = SpeechTextGate()
            try {
                lateinit var context: PromptContext
                lateinit var settings: AppSettings
                lateinit var route: DialogueRoute
                mutex.withLock {
                    turnSession = session
                    var d = state.value.data; settings = d.settings
                    val provider = ChatProvider.fromSetting(settings.provider)
                    val version = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "unknown"
                    route = DialogueRoute(provider, secrets.read(provider.secretName), secrets.read("deepseek"), providerSessionId, "Xingban/$version")
                    if (d.activity.silent) d = d.copy(activity = d.activity.copy(silent = false, revision = Math.incrementExact(d.activity.revision)))
                    activityRevision = d.activity.revision
                    val profile = CharacterProfile(userAddress = settings.userAddress, warmth = settings.warmth, playfulness = settings.playfulness, tsundere = settings.tsundere)
                    context = PromptAssembler.assemble(profile, d.character, d.memory, history(d), text, decision, summaries = listOfNotNull(d.summary), activity = d.activity, localQuestion = pendingLocalQuestion, currentTime = deviceWallClock())
                    pendingLocalQuestion = null; dismissLocalQuestion()
                    token = turnSession.beginTurn(d.memory.revision)
                    speech.beginTurn()
                    userId = id(); currentUserId = userId
                    commit(d.copy(messages = d.messages + SavedMessage(userId, "user", text, now(), "pending"), character = CharacterReducer.reduce(d.character, CharacterEvent(userId, CharacterEventKind.USER_MESSAGE, now())), usage = d.usage.beginRound(userId)))
                    if (!active()) return@launch
                    update { it.copy(busy = true, liveReply = "", expression = decision.expression) }
                    if (context.messages.last().content != text) notify("消息太长，本次只发送了前一部分。原文已保留，建议分几条发送。")
                }
                val output = speechOutput(settings)
                val result = trackedReply(route, context.messages.map { DialogueMessage(it.role.name.lowercase(), it.content) }, RequestPurpose.CHAT, userId, { requestOwner == owner && visible }) { delta ->
                    // DialogueClient invokes the callback on IO; await main ownership before accepting each chunk.
                    withContext(Dispatchers.Main.immediate) {
                        if (active() && visible) {
                            if (delta.length > 100000 - answer.length) throw IllegalStateException("回复超过本地单条容量，已停止")
                            answer.append(delta)
                            val first = firstCompanionParagraph(answer.toString())
                            val displayedDelta = first.drop(state.value.liveReply.length)
                            if (speechGate.accept(displayedDelta)) sentence.append(displayedDelta) else { sentence.clear(); speech.invalidateGeneration() }
                            update { it.copy(liveReply = first) }
                            if (output != null && speechGate.allowed && (sentence.lastOrNull()?.let { it in "。！？!?\n" } == true || sentence.length >= 100)) {
                                speech.enqueueSentence(sentence.toString(), output); sentence.clear()
                            }
                        }
                    }
                }
                val parts = if (result.completed) companionReplyParts(answer.toString()) else listOf(state.value.liveReply).filter { it.isNotBlank() }
                mutex.withLock {
                    if (!active()) return@launch
                    if (answer.isBlank()) {
                        if (getApplication<Application>().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                            Log.w("XingbanDialogue", "empty_reply ${result.diagnostics?.debugSummary() ?: "diagnostics=unavailable"} vmChars=${answer.length}")
                        }
                        throw NoDisplayableReplyException()
                    }
                    val d = state.value.data
                    var memory = d.memory
                    val original = d.messages.first { it.id == userId && it.role == "user" && it.text == text }
                    val source = MemorySource(original.id, MemorySourceKind.USER_MESSAGE, original.time, original.text)
                    if (result.completed && result.memoryValid && d.memory.revision == token!!.memoryRevision) {
                        val batch = MemoryRules.commitSemanticBatch(d.memory, source, token!!.memoryRevision, result.memoryCandidates.map { SemanticMemoryCandidate(it.key, it.evidence) })
                        memory = batch.state
                        if (batch.rejection != null) notify("本轮记忆来源或修订已失效，候选未保存。")
                        else if (batch.outcomes.any { it.reason == MemoryChangeReason.CONFLICT_REQUIRES_CONFIRMATION }) notify("发现记忆冲突，请到记忆页确认更正。")
                    }
                    val status = if (result.completed) "completed" else "interrupted"
                    val activity = if (result.completed && d.activity.revision == activityRevision) result.activity?.let(d.activity::apply) ?: d.activity else d.activity
                    commit(d.copy(messages = d.messages.map { if (it.id == userId) it.copy(status = status) else it } + SavedMessage(id(), "assistant", parts.first(), now(), status, userId), memory = memory, activity = activity))
                    firstCommitted = true; committedMemoryRevision = memory.revision
                    if (!active()) return@launch
                    update { it.copy(liveReply = "") }; currentUserId = null
                    if (!result.completed) { speech.invalidateGeneration(); notify(if (result.diagnostics?.finishReason == DialogueFinishReason.LENGTH) "回复达到生成上限，本轮未完整结束、未保存记忆；没有自动重试。" else "回复未完整结束，本轮未保存记忆；没有自动重试。") }
                    else if (!result.memoryValid) notify("回复已保留，记忆候选格式不正确，本轮未记忆。")
                    if (result.completed && output != null && speechGate.allowed && sentence.isNotBlank()) speech.enqueueSentence(sentence.toString(), output)
                }
                if (result.completed) for (part in parts.drop(1)) {
                    delay(700)
                    mutex.withLock {
                        if (!active() || !visible) return@launch
                        val d = state.value.data
                        commit(d.copy(messages = d.messages + SavedMessage(id(), "assistant", part, now(), "completed", userId)))
                        if (!active()) return@launch
                        // Never enqueue a hidden paragraph before its successful durable publication.
                        if (output != null && speechGate.allowed && speechGate.accept(part)) speech.enqueueSentence(part, output)
                    }
                }
                if (active()) {
                    update { it.copy(busy = false, liveReply = "") }
                    // Keep the current token valid while its remaining paragraphs are being published.
                    committedMemoryRevision?.let { session.onMemoryRevisionChanged(it) }
                    turnSession.stop()
                    if (result.completed) maintainSummary(owner, userId, turnSession, route)
                }
            } catch (_: CancellationException) { /* stop owns interrupted persistence */ }
            catch (error: Exception) {
                if (token == null || active()) {
                    speech.invalidateGeneration()
                    mutex.withLock {
                        val d = state.value.data
                        val displayed = state.value.liveReply
                        if (!firstCommitted) {
                            try { commit(d.copy(messages = d.messages.map { if (it.id == userId) it.copy(status = "failed") else it } + if (displayed.isNotBlank()) listOf(SavedMessage(id(), "assistant", displayed, now(), "interrupted", userId)) else emptyList())) }
                            catch (_: Exception) { notify("本地保存失败，原有数据已保留。请先导出备份。") }
                        }
                        val capacity = error is IllegalArgumentException || error is IllegalStateException
                        val notice = when {
                            firstCommitted -> "后续回复未能保存，已显示的内容和本轮事实仍保留；没有继续显示或自动重试。"
                            error is DialogueProtocolException -> if (displayed.isNotBlank()) "这次回复格式出了点问题，前面的话已保留；这轮没有新增记忆。可以继续聊。" else "这次回复格式出了点问题，没有接上；这轮没有新增记忆。可以继续聊。"
                            error is NoDisplayableReplyException -> "服务本次没有返回可显示的回复；本轮未记忆，没有自动重试。"
                            error is ReadOnlyToolException -> error.message ?: "本轮只读工具未完成，没有自动重试。"
                            capacity -> "本地数据或回复达到容量限制，已保留可保存内容。请先导出备份；没有清理历史。"
                            else -> "对话未完成。请检查网络、Key、模型名称或服务额度；没有自动重试。"
                        }
                        update { it.copy(busy = false, liveReply = "", notice = notice) }; currentUserId = null
                        session.stop()
                    }
                }
            } finally {
                if (requestOwner == owner) {
                    requestOwner = null; request = null
                    update { it.copy(busy = false, liveReply = "") }
                }
            }
        }
    }
    private fun history(data: AppData) = data.messages.map { ChatMessage(it.id, if (it.role == "user") ChatRole.USER else ChatRole.ASSISTANT, it.text, it.time, it.turnId, it.status) }

    fun sendMusicVolume(percent: Int, displayedScope: DeviceVolumeScope?) {
        if (!state.value.loaded || percent !in 0..100) return
        val text = "QQ音乐音量$percent%"
        if (displayedScope == null) sendLocalCommand(text, null, "当前QQ音乐没有可控制的音量目标，未执行音量操作。")
        else sendLocalCommand(text, DeviceCommand.SetQqMusicVolume(percent, expectedScope = displayedScope))
    }

    private fun sendLocalCommand(text: String, command: DeviceCommand?, unavailableMessage: String? = null) {
        stop()
        val owner = id(); requestOwner = owner
        request = viewModelScope.launch {
            var executed = false
            try {
                mutex.withLock {
                    if (requestOwner != owner || !visible) return@withLock
                    val d = state.value.data
                    val userId = id(); val replyId = id()
                    val handoff = command == DeviceCommand.OpenQqMusic || command is DeviceCommand.SetAlarm
                    val pending = when (command) {
                        DeviceCommand.OpenQqMusic -> "准备打开QQ音乐。"
                        is DeviceCommand.SetAlarm -> "准备向系统提交${command.hour}点${command.minute}分的闹钟设置请求。"
                        else -> ""
                    }
                    val user = SavedMessage(userId, "user", text, now(), if (handoff) "completed" else "pending")
                    val before = d.copy(messages = d.messages + user + if (handoff) listOf(SavedMessage(replyId, "assistant", pending, now(), "local", userId)) else emptyList())
                    // A foreground handoff is impossible until the future-tense reply is durably saved.
                    commit(before)
                    if (requestOwner != owner || !visible) return@withLock
                    currentUserId = userId
                    update { it.copy(busy = true) }
                    val result = if (command == null) PhoneCommandResult(false, unavailableMessage ?: "目前支持QQ音乐播放、暂停、上一首、下一首、打开QQ音乐、QQ音乐音量0–100%，以及“设置8点30分闹钟”这类明确整句。音量目标按实际QQ音乐输出标为系统共享或播放设备。引用、问句、否定和复合指令不会执行。") else phone.execute(command).also { executed = true }
                    // A completed platform action is a fact even if onStop cancels this owner next.
                    withContext(NonCancellable) {
                        val latest = state.value.data
                        val messages = latest.messages.map { when (it.id) { userId -> it.copy(status = "completed"); replyId -> it.copy(text = result.message); else -> it } } +
                            if (!handoff) listOf(SavedMessage(replyId, "assistant", result.message, now(), "local", userId)) else emptyList()
                        commit(latest.copy(messages = messages))
                    }
                    currentUserId = null
                }
            } catch (_: CancellationException) { /* stop prevents actions that have not run */ }
            catch (_: Exception) { notify(if (executed) "手机操作已返回，但结果未能保存；请在QQ音乐或系统时钟确认实际状态。" else "本地回应保存失败，未执行手机操作。") }
            finally { if (requestOwner == owner) { requestOwner = null; request = null; update { it.copy(busy = false) } } }
        }
    }

    fun openNotificationAccessSettings() { stop(); val result = phone.openNotificationAccessSettings(); if (!result.success) notify(result.message) }

    fun selectMusicSource(source: String) {
        if (!state.value.loaded || source !in setOf("local", "qq")) return
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val d = state.value.data; val settings = d.settings.copy(musicSource = source)
                    commit(d.copy(settings = settings))
                    speech.configureMusic(settings.musicEnabled, settings.musicVolume, settings.musicTrack, musicSource = settings.musicSource)
                } catch (_: Exception) { notify("音乐来源保存失败，原选择已保留。") }
            }
        }
    }

    /** Observe on IO, merge once against the newest durable snapshot even after cancellation. */
    private suspend fun trackedReply(route: DialogueRoute, messages: List<DialogueMessage>, purpose: RequestPurpose, roundId: String, ownerActive: () -> Boolean, onDelta: suspend (String) -> Unit): DialogueResult {
        val observed = java.util.Collections.synchronizedList(mutableListOf<RequestUsage>())
        val searches = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        try {
            return dialogue.reply(route, messages, purpose, roundId, { observed.add(it) }, { requestId, succeeded -> searches.add(requestId to succeeded) }, ownerActive, onDelta)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val d = state.value.data
                    val day = LocalDate.now().toString()
                    var recorded = d.usage
                    observed.toList().forEach { recorded = recorded.record(it, day) }
                    searches.toList().forEach { (requestId, succeeded) -> recorded = recorded.recordSearch(requestId, succeeded, day) }
                    if (recorded == d.usage) return@withLock
                    try { commit(d.copy(usage = recorded)) }
                    catch (_: Exception) {
                        // Retain only the already observed ledger in memory; no failed chat/settings write is claimed.
                        update { it.copy(data = it.data.copy(usage = recorded), usageSavePending = true) }
                        notify("服务可能已产生用量，但本机用量尚未保存；退出进程可能丢失这部分本机记录。")
                    }
                }
            }
        }
    }

    private suspend fun maintainSummary(owner: String, turnId: String, turnSession: CompanionSession, route: DialogueRoute) {
        var revision = 0L
        var boundary: Long? = null
        val batch = mutex.withLock {
            if (requestOwner != owner || session !== turnSession || !visible) return
            val d = state.value.data
            val next = ConversationHistory.summaryBatch(history(d), d.memory, d.summary, d.summaryAttemptTurns) ?: return
            revision = d.memory.revision; boundary = d.memory.contextBoundaryMillis
            try { commit(d.copy(summaryAttemptTurns = next.completedTurns)) }
            catch (_: Exception) { notify("摘要尝试位置未能保存，本轮未请求摘要。"); return }
            next
        }
        try {
            val answer = StringBuilder()
            val result = trackedReply(route, batch.messages.map { DialogueMessage(it.role.name.lowercase(), it.content) }, RequestPurpose.SUMMARY, turnId, { requestOwner == owner && session === turnSession && visible }) { delta ->
                if (answer.length + delta.length > 10000) throw IllegalStateException("Summary capacity exceeded")
                answer.append(delta)
            }
            mutex.withLock {
                val d = state.value.data
                if (requestOwner != owner || session !== turnSession || !visible || d.memory.revision != revision || d.memory.contextBoundaryMillis != boundary || d.messages.none { it.id == turnId && it.status == "completed" }) return@withLock
                val summary = ConversationSummary(answer.toString().trim(), batch.sourceIds, boundary)
                if (result.completed && ConversationHistory.validSummary(summary, d.memory, history(d))) commit(d.copy(summary = summary))
                else notify("本轮聊天已保存；摘要未完整生成或超过范围，保留旧摘要，没有自动重试。")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (requestOwner == owner) notify("本轮聊天已保存；摘要整理未完成，保留旧摘要，没有自动重试。")
        }
    }

    fun clearSummary() {
        stop()
        viewModelScope.launch {
            mutex.withLock {
                try { commit(state.value.data.copy(summary = null)); notify("对话摘要已清除，完整聊天仍保留。") }
                catch (_: Exception) { notify("摘要清除未保存，请重试。") }
            }
        }
    }
    private fun speechOutput(settings: AppSettings): SpeechOutput? = when (settings.speechMode) {
        "system" -> SpeechOutput.SystemTts(settings.systemVoice)
        "minimax" -> if (secrets.has("minimax") && settings.voiceId.isNotBlank()) SpeechOutput.MiniMax(secrets.read("minimax"), settings.voiceId) else { notify("MiniMax 尚缺 Key 或音色 ID，本回合仅显示文字。"); null }
        else -> null
    }
    fun startListening() {
        stop()
        if (!speech.recognitionAvailable()) { notify("本机没有可用的语音识别服务，请使用文字输入。识别服务可能需要联网。"); return }
        recognitionHeld = true; speech.startRecognition("zh-CN")
    }
    fun finishListening() { if (recognitionHeld) { recognitionHeld = false; speech.stopRecognition() } }
    override fun onPartial(text: String) { update { it.copy(partial = text) } }
    override fun onFinal(text: String) { update { it.copy(listening = false, partial = "") }; if (visible && text.isNotBlank()) send(text) }
    override fun onPlayingChanged(playing: Boolean) { update { it.copy(speaking = playing) } }
    override fun onListeningChanged(listening: Boolean) { update { it.copy(listening = listening) } }
    override fun onMusicChanged(playing: Boolean, track: BackgroundMusicTrack) { update { it.copy(musicPlaying = playing, musicTrack = track) } }
    override fun onError(message: String) { update { it.copy(notice = message, listening = false) } }
    fun systemVoices() = speech.availableSystemVoices()
    fun touch() {
        if (!state.value.loaded) return
        // The avatar owns transient area-specific feedback. Keep only the existing mood event here;
        // a touch must not replace errors or change an active chat/listening/playback state.
        viewModelScope.launch {
            mutex.withLock { try { val d = state.value.data; commit(d.copy(character = CharacterReducer.reduce(d.character, CharacterEvent(id(), CharacterEventKind.TOUCH, now())))) } catch (_: Exception) { notify("互动保存失败。") } }
        }
    }
    fun saveSettings(settings: AppSettings, deepSeekKey: String?, miniMaxKey: String?, goKey: String? = null) {
        stop()
        viewModelScope.launch {
            try {
                val provider = ChatProvider.fromSetting(settings.provider)
                CharacterProfile(userAddress = settings.userAddress, warmth = settings.warmth, playfulness = settings.playfulness, tsundere = settings.tsundere)
                mutex.withLock {
                    if (deepSeekKey != null) secrets.write("deepseek", deepSeekKey)
                    if (miniMaxKey != null) secrets.write("minimax", miniMaxKey)
                    if (goKey != null) secrets.write("opencode_go", goKey)
                    val current = state.value.data.settings
                    val merged = settings.copy(model = provider.model, musicEnabled = current.musicEnabled, musicVolume = current.musicVolume, musicTrack = current.musicTrack, musicSource = current.musicSource)
                    commit(state.value.data.copy(settings = merged))
                    speech.configureMusic(merged.musicEnabled, merged.musicVolume, merged.musicTrack, musicSource = merged.musicSource)
                    update { it.copy(hasDeepSeekKey = secrets.has("deepseek"), hasMiniMaxKey = secrets.has("minimax"), hasGoKey = secrets.has("opencode_go"), notice = "设置已保存。") }
                }
            } catch (_: Exception) { notify("设置保存失败，请核对输入。") }
        }
    }
    fun saveMusicSettings(enabled: Boolean, volume: Double, track: BackgroundMusicTrack, selectTrack: Boolean = false) {
        if (!state.value.loaded) return
        viewModelScope.launch {
            mutex.withLock {
                try {
                    require(volume.isFinite() && volume in 0.0..1.0)
                    val d = state.value.data
                    commit(d.copy(settings = d.settings.copy(musicEnabled = enabled, musicVolume = volume, musicTrack = track)))
                    speech.configureMusic(enabled, volume, track, selectTrack, musicSource = d.settings.musicSource)
                } catch (_: Exception) { notify("音乐设置保存失败，原设置已保留。") }
            }
        }
    }
    fun nextMusic() {
        val settings = state.value.data.settings
        saveMusicSettings(settings.musicEnabled, settings.musicVolume, speech.currentMusicTrack.next(), selectTrack = true)
    }
    fun editMemory(itemId: String, replacement: String?) {
        stop()
        viewModelScope.launch {
            mutex.withLock {
                val d = state.value.data
                try {
                    val timestamp = now()
                    val change = if (replacement == null) MemoryRules.forget(d.memory, itemId, timestamp) else MemoryRules.correct(d.memory, itemId, replacement, MemorySource(id(), MemorySourceKind.USER_CONFIRMATION, timestamp, replacement), timestamp)
                    if (change.changed) { commit(d.copy(memory = change.state, summary = null, summaryAttemptTurns = 0)); session.onMemoryRevisionChanged(change.state.revision); notify(if (replacement == null) "已退出未来回忆，重置此前上下文；原聊天和旧备份仍保留。" else "记忆已更正，重置此前上下文；原聊天仍保留显示。") }
                    else notify("记忆未改变，请核对内容。")
                } catch (_: Exception) { notify("记忆保存失败，原数据已保留。") }
            }
        }
    }
    suspend fun exportBackup(): String = mutex.withLock { StateCodec.encode(state.value.data) }
    suspend fun restoreBackup(raw: String) {
        val candidate = withContext(Dispatchers.Default) { StateCodec.decode(raw) }
        stop()
        mutex.withLock {
            val recovered = candidate.copy(messages = candidate.messages.map { if (it.status == "pending") it.copy(status = "interrupted") else it }, departedAtMillis = null, summary = null, summaryAttemptTurns = 0, usage = state.value.data.usage)
            commit(recovered)
            localDeparture = null; pendingLocalQuestion = null; dismissLocalQuestion()
            session = CompanionSession()
            session.onMemoryRevisionChanged(recovered.memory.revision)
            val settings = recovered.settings
            speech.configureMusic(settings.musicEnabled, settings.musicVolume, settings.musicTrack, selectTrack = true, musicSource = settings.musicSource)
            speech.setForeground(visible)
        }
        notify("备份已恢复。API Key 未导入，仍使用本机安全设置。")
    }
    override fun onCleared() { session.stop(); request?.cancel(); phone.close(); speech.release(); super.onCleared() }
}
