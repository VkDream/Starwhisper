package cn.xingban.app.integrations

import org.json.JSONArray
import org.json.JSONObject
import cn.xingban.core.ActivityAction
import cn.xingban.core.ActivityType
import cn.xingban.core.ActivityCommand

data class DialogueMemoryCandidate(val key: String, val evidence: String)
data class DialogueResult(val completed: Boolean, val memoryCandidates: List<DialogueMemoryCandidate> = emptyList(), val memoryValid: Boolean = false, val diagnostics: DialogueDiagnostics? = null, val activity: ActivityCommand? = null)

enum class DialogueContentType { MISSING, SSE, JSON, OTHER }
enum class DialogueFinishReason { NONE, STOP, LENGTH, CONTENT_FILTER, TOOL_CALLS, FUNCTION_CALL, OTHER }

/** Counts and fixed categories only: never carry request, reply, candidate or credential text. */
data class DialogueDiagnostics(
    val httpStatus: Int = 0,
    val contentType: DialogueContentType = DialogueContentType.MISSING,
    val sseFrames: Int = 0,
    val contentChars: Int = 0,
    val done: Boolean = false,
    val finishReason: DialogueFinishReason = DialogueFinishReason.NONE,
    val rootClosed: Boolean = false,
    val replySeen: Boolean = false,
    val replyClosed: Boolean = false,
    val replyNonBlank: Boolean = false,
    val decodedReplyChars: Int = 0,
) {
    fun debugSummary(): String = "http=$httpStatus type=${contentType.name} sseFrames=$sseFrames contentChars=$contentChars done=$done finish=${finishReason.name} rootClosed=$rootClosed replySeen=$replySeen replyClosed=$replyClosed replyNonBlank=$replyNonBlank decodedReplyChars=$decodedReplyChars"
}

/** A bounded JSON lexer: only the root reply string is emitted, never metadata or JSON syntax. */
internal class IncrementalReplyJson {
    private data class Frame(val objectValue: Boolean, var phase: Int, var key: String = "", val keys: MutableSet<String> = mutableSetOf())
    private val raw = StringBuilder()
    private val reply = StringBuilder()
    private val stack = mutableListOf<Frame>()
    private var ended = false
    private var stringRole = 0 // 0 outside, 1 key, 2 reply, 3 other string
    private val keyText = StringBuilder()
    private var escape = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var highSurrogate: Char? = null
    private var primitive: StringBuilder? = null
    private var seenReply = false
    private var replyClosed = false

    fun append(chunk: String): String {
        if (chunk.length > 700000 - raw.length) invalid()
        val output = StringBuilder()
        chunk.forEach { c ->
            // The transport tee retains the original bytes. Canonicalize only a naked root-reply LF.
            if (c == '\n' && stringRole == 2 && !escape && unicodeDigits == 0) {
                if (raw.length > 700000 - 2) invalid()
                raw.append("\\n"); consume('\\', output); consume('n', output)
            } else {
                if (raw.length >= 700000) invalid()
                raw.append(c); consume(c, output)
            }
        }
        return output.toString()
    }
    private fun consume(c: Char, output: StringBuilder) {
        if (stringRole != 0) { stringChar(c, output); return }
        primitive?.let { value ->
            if (space(c) || c in ",]}") {
                val token = value.toString()
                if (token !in setOf("true", "false", "null") && !NUMBER.matches(token)) invalid()
                primitive = null; valueDone(); consume(c, output)
            } else { if (value.length >= 64) invalid(); value.append(c) }
            return
        }
        if (space(c)) return
        if (ended) invalid()
        if (stack.isEmpty()) { if (c != '{') invalid(); stack += Frame(true, KEY_OR_END); return }
        val frame = stack.last()
        when (frame.phase) {
            KEY_OR_END, KEY_REQUIRED -> when {
                c == '}' && frame.phase == KEY_OR_END -> closeContainer()
                c == '"' -> { stringRole = 1; keyText.clear() }
                else -> invalid()
            }
            COLON -> { if (c != ':') invalid(); frame.phase = VALUE }
            VALUE, VALUE_OR_END -> {
                if (!frame.objectValue && c == ']' && frame.phase == VALUE_OR_END) { closeContainer(); return }
                val isReply = stack.size == 1 && frame.objectValue && frame.key == "reply"
                if (isReply && c != '"') invalid()
                when (c) {
                    '"' -> { if (isReply) { if (seenReply) invalid(); seenReply = true }; stringRole = if (isReply) 2 else 3 }
                    '{' -> stack.add(Frame(true, KEY_OR_END))
                    '[' -> stack.add(Frame(false, VALUE_OR_END))
                    't', 'f', 'n', '-', in '0'..'9' -> primitive = StringBuilder().append(c)
                    else -> invalid()
                }
                if (stack.size > 16) invalid()
            }
            COMMA_OR_END -> when {
                c == ',' -> frame.phase = if (frame.objectValue) KEY_REQUIRED else VALUE
                frame.objectValue && c == '}' || !frame.objectValue && c == ']' -> closeContainer()
                else -> invalid()
            }
        }
    }
    private fun stringChar(c: Char, output: StringBuilder) {
        if (unicodeDigits > 0) {
            unicodeValue = (unicodeValue shl 4) + (c.digitToIntOrNull(16) ?: invalid())
            unicodeDigits--
            if (unicodeDigits == 0) decoded(unicodeValue.toChar(), output)
            return
        }
        if (escape) {
            escape = false
            when (c) {
                'u' -> { unicodeDigits = 4; unicodeValue = 0 }
                '"', '\\', '/' -> decoded(c, output)
                'b' -> decoded('\b', output)
                'f' -> decoded('\u000C', output)
                'n' -> decoded('\n', output)
                'r' -> decoded('\r', output)
                't' -> decoded('\t', output)
                else -> invalid()
            }
            return
        }
        when {
            c == '\\' -> escape = true
            c == '"' -> {
                if (highSurrogate != null) invalid()
                if (stringRole == 1) {
                    val frame = stack.last(); frame.key = keyText.toString()
                    if (!frame.keys.add(frame.key)) invalid()
                    frame.phase = COLON
                } else { if (stringRole == 2) replyClosed = true; valueDone() }
                stringRole = 0
            }
            c.code < 32 -> invalid()
            else -> decoded(c, output)
        }
    }
    private fun decoded(c: Char, output: StringBuilder) {
        val high = highSurrogate
        if (high != null) {
            if (!c.isLowSurrogate()) invalid()
            highSurrogate = null; emit(high, output); emit(c, output)
        } else if (c.isHighSurrogate()) highSurrogate = c
        else { if (c.isLowSurrogate()) invalid(); emit(c, output) }
    }
    private fun emit(c: Char, output: StringBuilder) {
        when (stringRole) {
            1 -> { if (keyText.length >= 180) invalid(); keyText.append(c) }
            2 -> { if (reply.length >= 100000) invalid(); reply.append(c); output.append(c) }
        }
    }
    private fun closeContainer() {
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) ended = true else valueDone()
    }
    private fun valueDone() { stack.last().phase = COMMA_OR_END }
    fun finish(done: Boolean, finishReason: String?): DialogueResult {
        val diagnostics = DialogueDiagnostics(
            done = done,
            finishReason = when (finishReason) {
                null -> DialogueFinishReason.NONE
                "stop" -> DialogueFinishReason.STOP
                "length" -> DialogueFinishReason.LENGTH
                "content_filter" -> DialogueFinishReason.CONTENT_FILTER
                "tool_calls" -> DialogueFinishReason.TOOL_CALLS
                "function_call" -> DialogueFinishReason.FUNCTION_CALL
                else -> DialogueFinishReason.OTHER
            },
            rootClosed = ended,
            replySeen = seenReply,
            replyClosed = replyClosed,
            replyNonBlank = reply.isNotBlank(),
            decodedReplyChars = reply.length,
        )
        var activity: ActivityCommand? = null
        fun result(completed: Boolean, candidates: List<DialogueMemoryCandidate> = emptyList(), memoryValid: Boolean = false) = DialogueResult(completed, candidates, memoryValid, diagnostics, activity)
        val complete = done && finishReason == "stop" && ended && stringRole == 0 && primitive == null && replyClosed && reply.isNotBlank()
        if (!complete) return result(false)
        val root = JSONObject(raw.toString())
        // Optional metadata is independent: a bad memory batch must not erase a valid activity.
        val activityJson = root.opt("activity") as? JSONObject
        if (activityJson != null && activityJson.length() == 2 && activityJson.has("action") && activityJson.has("type")) {
            val action = ActivityAction.entries.firstOrNull { it.name == activityJson.opt("action") }
            val type = ActivityType.entries.firstOrNull { it.name == activityJson.opt("type") }
            val nullableType = activityJson.isNull("type")
            if (action != null && (type != null || nullableType) &&
                (action !in setOf(ActivityAction.START, ActivityAction.SWITCH) || type != null) &&
                (action != ActivityAction.NONE || nullableType)) activity = ActivityCommand(action, type)
        }
        val candidates = root.opt("memory_candidates") as? JSONArray ?: return result(true)
        if (candidates.length() > 6) return result(true)
        val parsed = mutableListOf<DialogueMemoryCandidate>()
        for (i in 0 until candidates.length()) {
            val item = candidates.opt(i) as? JSONObject ?: return result(true)
            if (item.length() != 2 || !item.has("key") || !item.has("evidence")) return result(true)
            val key = item.opt("key") as? String ?: return result(true)
            val evidence = item.opt("evidence") as? String ?: return result(true)
            if (key.isBlank() || key.length > 180 || evidence.isBlank() || evidence.length > 500) return result(true)
            parsed += DialogueMemoryCandidate(key, evidence)
        }
        return result(true, parsed, true)
    }
    private fun space(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
    private fun invalid(): Nothing = throw DialogueProtocolException()
    companion object {
        private const val KEY_OR_END = 0
        private const val KEY_REQUIRED = 1
        private const val COLON = 2
        private const val VALUE = 3
        private const val VALUE_OR_END = 4
        private const val COMMA_OR_END = 5
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
