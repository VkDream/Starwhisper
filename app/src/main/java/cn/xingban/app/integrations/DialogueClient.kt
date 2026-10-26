package cn.xingban.app.integrations

import cn.xingban.core.RequestPurpose
import cn.xingban.core.RequestUsage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import java.util.UUID

data class DialogueMessage(val role: String, val content: String, val toolCalls: JSONArray? = null, val reasoningContent: String? = null, val toolCallId: String? = null)

enum class ChatProvider(val setting: String, val usageId: String, val model: String, val secretName: String, val endpoint: String) {
    DEEPSEEK("DeepSeek", "deepseek", "deepseek-flash", "deepseek", "https://api.deepseek.com/chat/completions"),
    OPENCODE_GO("OpenCode Go", "opencode_go", "deepseek-v4.1-flash", "opencode_go", "https://opencode.ai/inference/go/openai/v1/chat/completions");
    companion object { fun fromSetting(value: String) = entries.first { it.setting == value } }
}

/** Captured once per user round, including its summary. Never persisted or logged. */
class DialogueRoute(val provider: ChatProvider, val apiKey: String, val searchKey: String, val sessionId: String, val userAgent: String)
class ReadOnlyToolException(message: String) : IOException(message)

class DialogueProtocolException : IOException("服务返回的 JSON 回复格式不完整或超过限制")

interface DialogueClient {
    /** IO deltas; one final usage report precedes return/throw, including cancellation. */
    suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose = RequestPurpose.CHAT, requestId: String = UUID.randomUUID().toString(), roundId: String = requestId, onUsage: (RequestUsage) -> Unit = {}, onDelta: suspend (String) -> Unit): DialogueResult
    suspend fun reply(route: DialogueRoute, messages: List<DialogueMessage>, purpose: RequestPurpose, roundId: String, onUsage: (RequestUsage) -> Unit, onSearchUsage: (String, Boolean) -> Unit, ownerActive: () -> Boolean, onDelta: suspend (String) -> Unit): DialogueResult =
        stream(route.apiKey, route.provider.model, messages, purpose, UUID.randomUUID().toString(), roundId, onUsage, onDelta)
}

class DeepSeekDialogueClient internal constructor(client: OkHttpClient = streamingClient(), private val endpoint: String = ChatProvider.DEEPSEEK.endpoint, private val goEndpoint: String = ChatProvider.OPENCODE_GO.endpoint, private val searchEndpoint: String = "https://api.deepseek.com/v1/tools/search") : DialogueClient {
    // Also disable redirects on an injected client: no credential may follow an HTTP redirect.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    override suspend fun stream(apiKey: String, model: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
        return finalRequest(apiKey, model, endpoint, messages, purpose, requestId, roundId, "deepseek", emptyMap(), onUsage, onDelta)
    }

    override suspend fun reply(route: DialogueRoute, messages: List<DialogueMessage>, purpose: RequestPurpose, roundId: String, onUsage: (RequestUsage) -> Unit, onSearchUsage: (String, Boolean) -> Unit, ownerActive: () -> Boolean, onDelta: suspend (String) -> Unit): DialogueResult {
        require(route.apiKey.isNotBlank())
        val headers = if (route.provider == ChatProvider.OPENCODE_GO) mapOf("User-Agent" to route.userAgent, "x-opencode-session" to route.sessionId) else emptyMap()
        val target = if (route.provider == ChatProvider.DEEPSEEK) endpoint else goEndpoint
        val prepared = if (purpose == RequestPurpose.CHAT) ReadOnlyTools(client, searchEndpoint).prepare(route, target, headers, messages, roundId, onUsage, onSearchUsage, ownerActive) else messages
        if (!ownerActive()) throw kotlinx.coroutines.CancellationException("Round no longer owns the request")
        val sourceUrls = prepared.filter { it.role == "tool" }.flatMap { message ->
            val sources = JSONObject(message.content).optJSONArray("sources") ?: return@flatMap emptyList<String>()
            List(sources.length()) { sources.getJSONObject(it).getString("url") }
        }.toSet()
        val buffered = StringBuilder()
        val queryTimes = prepared.filter { it.role == "tool" }.mapNotNull { JSONObject(it.content).opt("queried_at") as? String }.distinct()
        val result = finalRequest(route.apiKey, route.provider.model, target, prepared, purpose, UUID.randomUUID().toString(), roundId, route.provider.usageId, headers, onUsage) { delta ->
            if (sourceUrls.isEmpty()) onDelta(delta) else { if (buffered.length + delta.length > 100000) throw DialogueProtocolException(); buffered.append(delta) }
        }
        if (sourceUrls.isNotEmpty()) {
            if (!result.completed) throw ReadOnlyToolException("搜索后的最终回复未完整结束，本轮未记忆。")
            validateSourceLinks(buffered.toString(), sourceUrls)
            if (!ownerActive()) throw kotlinx.coroutines.CancellationException()
            onDelta(buffered.toString() + "\n\n查询时间：" + queryTimes.joinToString() + "\n来源：\n" + sourceUrls.joinToString("\n"))
        }
        return result
    }

    private suspend fun finalRequest(key: String, model: String, target: String, messages: List<DialogueMessage>, purpose: RequestPurpose, requestId: String, roundId: String, provider: String, headers: Map<String, String>, onUsage: (RequestUsage) -> Unit, onDelta: suspend (String) -> Unit): DialogueResult {
        require(key.isNotBlank() && model.isNotBlank() && messages.isNotEmpty())
        val base = RequestUsage(requestId, roundId, purpose, model, System.currentTimeMillis(), provider = provider)
        val usage = RequestUsageSnapshot(base)
        val request = Request.Builder().url(target).header("Authorization", "Bearer $key").apply { headers.forEach { (name, value) -> header(name, value) } }.post(encodeDeepSeekRequest(model, messages, purpose).toRequestBody(JSON_MEDIA_TYPE)).build()
        try {
            return executeDialogueHttp(client, request) { response ->
                requireSuccessfulResponse(response, usage::observe)
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (type != "text/event-stream") throw DialogueProtocolException()
                readDeepSeekStream(response.body?.source() ?: throw IOException("Empty response"), onDelta, response.code, DialogueContentType.SSE, usage::observe)
            }
        } finally { runCatching { onUsage(usage.close()) } }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun streamingClient(): OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    }
}

internal fun encodeDeepSeekRequest(model: String, messages: List<DialogueMessage>, purpose: RequestPurpose = RequestPurpose.CHAT): String = JSONObject().apply {
    put("model", model)
    put("stream", true)
    put("thinking", JSONObject().put("type", "enabled"))
    put("reasoning_effort", "max")
    put("response_format", JSONObject().put("type", "json_object"))
    put("max_tokens", if (purpose == RequestPurpose.SUMMARY) 8192 else 32768)
    val withProtocol = if (purpose == RequestPurpose.SUMMARY) messages else messages.takeWhile { it.role == "system" } + DialogueMessage("system", JSON_OUTPUT_PROMPT) + messages.dropWhile { it.role == "system" }
    put("messages", JSONArray().apply { withProtocol.forEach {
        put(encodeDialogueMessage(it, wrapAssistant = true))
    } })
}.toString()

private val JSON_OUTPUT_PROMPT = """
    输出一个json对象，固定格式：{"reply":"对用户说的自然台词","memory_candidates":[{"key":"user.preference.drink","evidence":"我平常只喝绿茶。"}]}。
    每轮都必须包含reply字符串和memory_candidates数组；没有可提取稳定事实时也必须输出"memory_candidates":[]，不得省略这两个键。
    整次响应只能有这一个JSON对象。所有段落都放在这个对象唯一的reply字符串内；禁止每段另起对象，禁止在对象结束后继续输出第二个对象或其他内容。
    历史assistant消息由App从已经显示的台词重建为reply加memory_candidates空数组。此空数组只是历史格式占位，表示本轮不重复处理历史，不声明过去没有提取记忆；当前回合仍须按最后一条USER独立提取。
    reply字段放完整回应，保持浅的性格；不要把记忆字段、推理、动作脚本或JSON结构当成台词。尽早输出reply字段，以便流式显示。
    按情境自然给1–3个短段落，以空行（JSON字符串内\n\n）分隔，App会分条呈现同一回合。简单回应一条就好，想补充可自然多说两句，不强凑条数，不把每句话机械切碎；需要解释时保留完整内容。
    JSON引号内不能出现实际换行，必须用反斜杠加n转义。完整有效示例：{"reply":"先陪你歇会儿。\n\n想说话就叫我。","memory_candidates":[]}。
    memory_candidates由你判断本轮最后一条USER消息中值得长期记住的稳定用户事实、偏好、称呼或约定，不要求固定前缀。
    仅从本轮USER真实陈述提取，不从历史、检索资料、助手台词、虚构/角色扮演、假设、转述、否定误截、临时情绪或推断中造事实；不确定则[]。
    每批最多6条，key为稳定的事实类别键且<=180字符，evidence必须逐字复制当前用户原文的一整条消息或完整句段，<=500字符。
    句界仅。！？!?；;或换行；不能只截任意子串，不能从“我不喜欢咖啡”截成“喜欢咖啡”。超过500字符的原句拒绝提取，不截断。
    例：用户“我每天午饭后都喝一杯绿茶。”可提取原句；“今天好累”“假如我喜欢绿茶”“扮演一个爱茶的人”均[]。
    可选activity字段与reply、memory_candidates在同一对象中，严格为{"action":"START","type":"READ"}。action只允许NONE/START/CONTINUE/PAUSE/FINISH/SWITCH；type只允许WATCH/WORK/READ/MUSIC/REST/CHAT或null。NONE时type必须null；START/SWITCH必须指定type。
    依据本轮最后一条USER真实意图和App给出的当前活动：明确邀请开始用START，已有活动换成另一活动用SWITCH，继续/暂停/结束用CONTINUE/PAUSE/FINISH。未要求改变则NONE或省略。明确开始直接回应，不反复确认；真歧义自然问清，不擅自启动。
    “陪我看剧/我现在去工作你陪我/一起看书/陪我听歌/一起休息/我们聊聊”可对应WATCH/WORK/READ/MUSIC/REST/CHAT；“不想看书”“昨天看书了”“明天再看”“假如陪我看”“他说一起看书”及引用、历史、未来、假设均不能作为现在开始的依据。
    这些活动仅改变本地陪伴姿态和安静模式，不能操作外部设备、播放音乐或声称实际看见/听见外部内容。处于安静活动时台词简短，PAUSE保留活动上下文，FINISH回到自主陪伴，AWAITING_CONTINUE须用户同意后才继续。
    没有稳定事实的示例：{"reply":"那先歇一会儿，我听着呢。","memory_candidates":[],"activity":{"action":"NONE","type":null}}。只输出此json对象。
""".trimIndent()

internal fun parseDeepSeekDelta(data: String): String? {
    if (data == "[DONE]") return null
    val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
    return (delta.opt("content") as? String)?.takeIf { it.isNotEmpty() }
}

internal fun parseDeepSeekUsage(json: JSONObject, base: RequestUsage): RequestUsage? {
    val usage = json.optJSONObject("usage") ?: return null
    fun count(key: String): Long? = (usage.opt(key)?.takeIf { it is Int || it is Long } as? Number)?.toLong()?.takeIf { it in 0..1_000_000_000_000L }
    val prompt = count("prompt_tokens")
    val completion = count("completion_tokens")
    var hit = count("prompt_cache_hit_tokens"); var miss = count("prompt_cache_miss_tokens")
    if (prompt != null) {
        if (hit != null && hit > prompt) hit = null
        if (miss != null && miss > prompt) miss = null
    }
    if (prompt != null && hit != null && miss != null && hit + miss != prompt) { hit = null; miss = null }
    val cost = (usage.opt("cost") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
    val billing = (usage.opt("billing_source") as? String)?.takeIf { it.length in 1..80 }
    return base.copy(prompt = prompt, completion = completion, cacheHit = hit, cacheMiss = miss, reportedCost = cost, billingSource = billing)
}

/** Bounded request-local observation, sealed before the sole final callback. */
internal class RequestUsageSnapshot(private var latest: RequestUsage) {
    private var closed = false
    @Synchronized fun observe(json: JSONObject) {
        if (closed) return
        val next = parseDeepSeekUsage(json, latest) ?: return
        val prompt = next.prompt ?: latest.prompt
        var hit = next.cacheHit ?: latest.cacheHit
        var miss = next.cacheMiss ?: latest.cacheMiss
        if (prompt != null) {
            if (hit != null && hit > prompt) hit = null
            if (miss != null && miss > prompt) miss = null
            if (hit != null && miss != null && hit + miss != prompt) { hit = null; miss = null }
        }
        latest = latest.copy(prompt = prompt, completion = next.completion ?: latest.completion, cacheHit = hit, cacheMiss = miss, reportedCost = next.reportedCost ?: latest.reportedCost, billingSource = next.billingSource ?: latest.billingSource)
    }
    @Synchronized fun close(): RequestUsage { closed = true; return latest }
}

private suspend fun readDeepSeekStream(source: BufferedSource, onDelta: suspend (String) -> Unit, httpStatus: Int, contentType: DialogueContentType, observeUsage: (JSONObject) -> Unit): DialogueResult {
    var done = false
    var finishReason: String? = null
    var sseFrames = 0
    var contentChars = 0
    val decoder = IncrementalReplyJson()
    while (currentCoroutineContext().isActive && !source.exhausted()) {
        val line = source.readUtf8LineStrict(2000000)
        if (line.isEmpty() || line.startsWith(":")) continue
        if (!line.startsWith("data:")) continue
        sseFrames++
        val data = line.substring(5).trimStart()
        if (data == "[DONE]") {
            done = true
            break
        }
        val json = JSONObject(data)
        observeUsage(json)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val tools = choice?.optJSONObject("delta")?.opt("tool_calls")
        if (tools != null && tools !== JSONObject.NULL && (tools !is JSONArray || tools.length() > 0)) throw DialogueProtocolException()
        (choice?.opt("finish_reason") as? String)?.let { finishReason = it }
        parseDeepSeekDelta(data)?.let { content ->
            contentChars += content.length
            decoder.append(content).takeIf { it.isNotEmpty() }?.let { onDelta(it) }
        }
    }
    val result = decoder.finish(done, finishReason)
    return result.copy(diagnostics = result.diagnostics?.copy(httpStatus = httpStatus, contentType = contentType, sseFrames = sseFrames, contentChars = contentChars))
}

/** One gate per HTTP wait, shared by success, failure and cancellation. */
internal class CompletionGate {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    fun close() { closed.set(true) }
    fun finish(action: () -> Unit): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        action()
        return true
    }
}

/** Registers cleanup before the lazy reader is published or started. */
internal fun closingResponseReader(response: Response, context: CoroutineContext, block: suspend () -> Unit): Job {
    val reader = CoroutineScope(context).launch(start = CoroutineStart.LAZY) { block() }
    reader.invokeOnCompletion { runCatching { response.close() } }
    return reader
}
