package cn.xingban.app.integrations

import cn.xingban.app.SpeechTextGate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IncrementalReplyJsonTest {
    private val recordedTwoRootReply = """
        {"reply":"行啊，被你逗到了，看我一会儿怎么回敬你。","memory_candidates":[]}

        {"reply":"不过你肯说“累了”，我就还是记着。缓过来是好事。","memory_candidates":[]}
    """.trimIndent()

    @Test fun oneObjectKeepsBothParagraphsAndRecordedSecondRootIsAlwaysRejected() {
        val reply = "行啊，被你逗到了，看我一会儿怎么回敬你。\n\n不过你肯说“累了”，我就还是记着。缓过来是好事。"
        val legal = json(reply)
        for (split in 0..legal.length) {
            val decoder = IncrementalReplyJson()
            assertEquals(reply, decoder.append(legal.take(split)) + decoder.append(legal.drop(split)))
            val result = decoder.finish(true, "stop")
            assertTrue(result.completed && result.memoryValid)
            assertTrue(result.memoryCandidates.isEmpty())
        }
        for (split in 0..recordedTwoRootReply.length) {
            assertThrows(DialogueProtocolException::class.java) {
                val decoder = IncrementalReplyJson()
                decoder.append(recordedTwoRootReply.take(split)); decoder.append(recordedTwoRootReply.drop(split))
            }
        }
    }

    @Test fun streamingClientPreservesProtocolExceptionAndNeverRetries() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient()
        val body = recordedTwoRootReply.chunked(3).joinToString("") { content ->
            "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content)))).toString() + "\n\n"
        } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)); server.start()
        try {
            val displayed = StringBuilder()
            try {
                DeepSeekDialogueClient(http, server.url("/isolated-two-root-fixture").toString()).stream("fixture-not-real-key", "fixture", listOf(DialogueMessage("user", "我不难过，刚才是逗你的哈哈"))) { displayed.append(it) }
                fail("A second root must fail the request")
            } catch (_: DialogueProtocolException) { }
            assertEquals("行啊，被你逗到了，看我一会儿怎么回敬你。", displayed.toString())
            assertEquals(1, server.requestCount)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun recordedReplyLineFeedsMatchEscapedJsonAcrossFramesAndBoundaries() {
        val raw = "{\"reply\":\"你刚才说最想去青岛看海，我可记着呢。就算只是听着，也像能吹到点海风似的。\n\n比赛看完就收工吧，陪看结束。你想多说几句，我就在这儿，慢慢聊也行。\",\"memory_candidates\":[],\"activity\":{\"action\":\"FINISH\",\"type\":\"WATCH\"}}"
        val expectedReply = "你刚才说最想去青岛看海，我可记着呢。就算只是听着，也像能吹到点海风似的。\n\n比赛看完就收工吧，陪看结束。你想多说几句，我就在这儿，慢慢聊也行。"
        val recordedChunks = listOf("", "{", "\"", "reply", "\":\"", "你", "刚才", "说", "最", "想去", "青岛", "看", "海", "，", "我可", "记", "着呢", "。", "就算", "只是", "听着", "，", "也", "像", "能", "吹", "到", "点", "海", "风", "似的", "。\n\n", "比赛", "看完", "就", "收", "工", "吧", "，", "陪", "看", "结束", "。", "你想", "多说", "几句", "，", "我", "就在", "这儿", "，", "慢慢", "聊", "也行", "。", "\",\"", "memory", "_c", "andidates", "\":", "[],", "\"", "activity", "\":{\"", "action", "\":\"", "FIN", "ISH", "\",\"", "type", "\":\"", "W", "ATCH", "\"", "}}", "")
        assertEquals(raw, recordedChunks.joinToString(""))
        val reference = IncrementalReplyJson()
        assertEquals(expectedReply, reference.append(raw.replace("\n", "\\n")))
        val expected = reference.finish(true, "stop")
        for (chunks in listOf(recordedChunks) + (0..raw.length).map { listOf(raw.take(it), raw.drop(it)) }) {
            val decoder = IncrementalReplyJson()
            assertEquals(expectedReply, chunks.joinToString("") { decoder.append(it) })
            val result = decoder.finish(true, "stop")
            assertTrue(result.completed && result.memoryValid)
            assertEquals(expected.memoryCandidates, result.memoryCandidates)
            assertEquals(expected.activity, result.activity)
            assertEquals(cn.xingban.core.ActivityAction.FINISH, result.activity!!.action)
            assertEquals(cn.xingban.core.ActivityType.WATCH, result.activity!!.type)
            assertFalse(decoder.finish(false, "stop").completed)
            assertFalse(decoder.finish(true, "length").completed)
            assertNull(decoder.finish(false, "stop").activity)
        }
        val truncated = IncrementalReplyJson(); truncated.append(raw.dropLast(1))
        assertFalse(truncated.finish(true, "stop").completed)
        assertTrue(truncated.finish(true, "stop").memoryCandidates.isEmpty())
        assertNull(truncated.finish(true, "stop").activity)
    }

    @Test fun nakedReplyLfDoesNotRelaxOtherControlsLocationsOrCanonicalLimit() {
        val invalid = listOf(
            "{\"re\nply\":\"ok\"}",
            "{\"reply\":\"ok\",\"memory_candidates\":[{\"key\":\"a\nb\",\"evidence\":\"source\"}]}",
            "{\"reply\":\"ok\",\"memory_candidates\":[{\"key\":\"x\",\"evidence\":\"a\nb\"}]}",
            "{\"reply\":\"ok\",\"other\":{\"reply\":\"a\nb\"}}",
            "{\"reply\":\"a\\\nb\"}",
            "{\"reply\":\"\\u12\n4\"}",
            "{\"reply\":\"a\rb\"}",
            "{\"reply\":\"a\tb\"}"
        )
        for (raw in invalid) for (split in 0..raw.length) {
            assertThrows(DialogueProtocolException::class.java) {
                val decoder = IncrementalReplyJson(); decoder.append(raw.take(split)); decoder.append(raw.drop(split))
            }
        }
        val overCanonicalLimit = "{\"reply\":\"" + "a\n".repeat(50000) + "\",\"other\":\"" + "x".repeat(550000) + "\",\"memory_candidates\":[]}"
        assertTrue(overCanonicalLimit.length < 700000)
        assertThrows(DialogueProtocolException::class.java) { IncrementalReplyJson().append(overCanonicalLimit) }
    }
    @Test fun paragraphMessagesStayNaturalAndNeverDropTailText() {
        assertEquals(listOf("只有一条。还有一句。"), cn.xingban.app.companionReplyParts("只有一条。还有一句。"))
        assertEquals(listOf("第一段。", "第二段。", "第三段。\n\n第四段。"), cn.xingban.app.companionReplyParts("\n第一段。\n\n\n第二段。\n \n第三段。\n\n第四段。\n"))
        assertEquals(listOf("甲", "乙"), cn.xingban.app.companionReplyParts("甲\r\n\r\n乙"))
    }
    @Test fun optionalActivityIsStrictCompleteAndIndependentOfMemory() {
        fun parse(activity: Any?, memories: Any = JSONArray(), done: Boolean = true): DialogueResult {
            val root = JSONObject().put("reply", "好，我陪你。").put("memory_candidates", memories)
            if (activity != null) root.put("activity", activity)
            val decoder = IncrementalReplyJson(); assertEquals("好，我陪你。", decoder.append(root.toString()))
            return decoder.finish(done, "stop")
        }
        val good = JSONObject().put("action", "START").put("type", "READ")
        val valid = parse(good, "invalid-memory")
        assertFalse(valid.memoryValid); assertEquals(cn.xingban.core.ActivityType.READ, valid.activity!!.type)
        assertNull(parse(good, done = false).activity)
        assertNull(parse(null).activity)
        listOf<Any>("START", JSONObject().put("action", "start").put("type", "READ"), JSONObject().put("action", "START").put("type", "EXECUTE"), JSONObject().put("action", "START").put("type", JSONObject.NULL), JSONObject().put("action", "NONE").put("type", "READ"), JSONObject().put("action", "PAUSE"), JSONObject(good.toString()).put("extra", true)).forEach {
            val result = parse(it); assertTrue(result.completed && result.memoryValid); assertNull(result.activity)
        }
        assertEquals(cn.xingban.core.ActivityAction.PAUSE, parse(JSONObject().put("action", "PAUSE").put("type", JSONObject.NULL)).activity!!.action)
        val truncated = IncrementalReplyJson(); truncated.append("{\"reply\":\"有效\",\"activity\":{\"action\":\"START\",\"type\":\"READ\"}")
        assertNull(truncated.finish(true, "stop").activity)
    }
    private fun json(reply: String, candidates: JSONArray = JSONArray()): String = JSONObject().put("reply", reply).put("memory_candidates", candidates).toString()
    private fun candidate(evidence: String = "我每天午饭后都喝绿茶。", key: String = "user.preference.drink") = JSONObject().put("key", key).put("evidence", evidence)

    @Test fun everySplitDecodesEscapesAndSurrogatePairsWithoutMetadata() {
        val reply = "浅说：\"好呀\"\n路径\\茶\t😊"
        val raw = "{\"memory_candidates\":[{\"key\":\"user.preference.drink\",\"evidence\":\"只在metadata的原话。\"}],\"reply\":\"浅说：\\\"好呀\\\"\\n路径\\\\茶\\t\\uD83D\\uDE0A\"}"
        for (split in 0..raw.length) {
            val decoder = IncrementalReplyJson()
            assertEquals(reply, decoder.append(raw.take(split)) + decoder.append(raw.drop(split)))
            val result = decoder.finish(true, "stop")
            assertTrue(result.completed && result.memoryValid)
            assertEquals("只在metadata的原话。", result.memoryCandidates.single().evidence)
        }
    }
    @Test fun replyIsDeliveredBeforeWholeObjectAndMetadataNeverReachesSpeechGate() {
        val decoder = IncrementalReplyJson(); val gate = SpeechTextGate()
        assertEquals("你好。", decoder.append("{\"reply\":\"你好。"))
        assertFalse(decoder.finish(false, null).completed)
        val tail = decoder.append("\",\"memory_candidates\":[{\"key\":\"x\",\"evidence\":\"metadata含{}与反引号`。\"}]}")
        assertEquals("", tail); assertTrue(gate.accept("你好。" + tail)); assertTrue(decoder.finish(true, "stop").memoryValid)
    }
    @Test fun emptyTruncatedAndNonStopCompletionNeverReturnCandidates() {
        assertFalse(IncrementalReplyJson().finish(true, "stop").completed)
        val empty = IncrementalReplyJson(); empty.append(json("")); assertFalse(empty.finish(true, "stop").completed)
        val truncated = IncrementalReplyJson(); assertEquals("已有台词", truncated.append("{\"reply\":\"已有台词")); assertFalse(truncated.finish(true, "stop").completed)
        val decoder = IncrementalReplyJson(); decoder.append(json("有效回复", JSONArray().put(candidate())))
        listOf(null, "length", "content_filter").forEach { reason -> assertTrue(decoder.finish(true, reason).memoryCandidates.isEmpty()); assertFalse(decoder.finish(true, reason).completed) }
        assertFalse(decoder.finish(false, "stop").completed)
    }
    @Test fun invalidCandidatesKeepReplyButRejectTheBatch() {
        listOf(JSONArray().put(candidate("好".repeat(501))), JSONArray().put(candidate(key = "k".repeat(181))), JSONArray().apply { repeat(7) { put(candidate()) } }, JSONArray().put("not-object"), JSONArray().put(JSONObject().put("key", "missing-evidence")), JSONArray().put(candidate().put("extra", "not-allowed")), JSONArray().put(candidate().put("evidence", JSONObject.NULL))).forEach { candidates ->
            val decoder = IncrementalReplyJson(); assertEquals("有效回复", decoder.append(json("有效回复", candidates)))
            val result = decoder.finish(true, "stop"); assertTrue(result.completed); assertFalse(result.memoryValid); assertTrue(result.memoryCandidates.isEmpty())
        }
    }
    @Test fun strictSyntaxBoundsAndNestedReplyCannotLeak() {
        val decoder = IncrementalReplyJson()
        assertEquals("只显示这句", decoder.append("{\"other\":{\"reply\":\"不能显示\"},\"reply\":\"只显示这句\",\"memory_candidates\":[]}"))
        assertTrue(decoder.finish(true, "stop").completed)
        listOf("{\"reply\":\"\\x\"}", "{\"reply\":\"a\",\"reply\":\"b\"}", "{\"reply\":\"a\",\"memory_candidates\":[,]}", json("a") + "junk").forEach { raw -> assertThrows(DialogueProtocolException::class.java) { IncrementalReplyJson().append(raw) } }
        assertThrows(DialogueProtocolException::class.java) { IncrementalReplyJson().append(json("好".repeat(100001))) }
    }
    @Test fun oneLocalHttpRequestStreamsDecodedReplyAndReturnsCandidates() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient()
        fun frame(content: String): String = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content)))).toString() + "\n\n"
        val raw = json("你好。今天想聊什么？", JSONArray().put(candidate()))
        val body = raw.chunked(3).joinToString("") { frame(it) } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)); server.start()
        try {
            val chunks = mutableListOf<String>()
            val result = DeepSeekDialogueClient(http, server.url("/isolated-json-fixture").toString()).stream("fixture-not-real-key", "fixture", listOf(DialogueMessage("user", "我每天午饭后都喝绿茶。"))) { chunks += it }
            assertEquals("你好。今天想聊什么？", chunks.joinToString("")); assertTrue(chunks.size > 1)
            assertTrue(result.completed && result.memoryValid); assertEquals(1, result.memoryCandidates.size); assertEquals(1, server.requestCount)
            val diagnostics = requireNotNull(result.diagnostics)
            assertEquals(200, diagnostics.httpStatus); assertEquals(DialogueContentType.SSE, diagnostics.contentType)
            assertEquals(raw.length, diagnostics.contentChars); assertEquals(raw.chunked(3).size + 2, diagnostics.sseFrames)
            assertTrue(diagnostics.done && diagnostics.rootClosed && diagnostics.replySeen && diagnostics.replyClosed && diagnostics.replyNonBlank)
            assertEquals(DialogueFinishReason.STOP, diagnostics.finishReason); assertEquals("你好。今天想聊什么？".length, diagnostics.decodedReplyChars)
            assertFalse(diagnostics.debugSummary().contains("你好")); assertFalse(diagnostics.debugSummary().contains("我每天")); assertFalse(diagnostics.debugSummary().contains("fixture-not-real-key"))
            assertEquals("json_object", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("response_format").getString("type"))
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun noContentNormalSseEndHasOnlyWhitelistedEmptyDiagnostics() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient()
        val body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body)); server.start()
        try {
            val chunks = mutableListOf<String>()
            val result = DeepSeekDialogueClient(http, server.url("/isolated-empty-fixture").toString()).stream("fixture-not-real-key", "fixture", listOf(DialogueMessage("user", "isolated request"))) { chunks += it }
            assertFalse(result.completed); assertFalse(result.memoryValid); assertTrue(chunks.isEmpty()); assertTrue(result.memoryCandidates.isEmpty())
            val diagnostics = requireNotNull(result.diagnostics)
            assertEquals(200, diagnostics.httpStatus); assertEquals(DialogueContentType.SSE, diagnostics.contentType)
            assertEquals(2, diagnostics.sseFrames); assertEquals(0, diagnostics.contentChars); assertEquals(0, diagnostics.decodedReplyChars)
            assertTrue(diagnostics.done); assertEquals(DialogueFinishReason.STOP, diagnostics.finishReason)
            assertFalse(diagnostics.rootClosed || diagnostics.replySeen || diagnostics.replyClosed || diagnostics.replyNonBlank)
            assertEquals(1, server.requestCount)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun completeReplyWithoutDoneStaysIncompleteAndReportsStructuralEvidence() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient()
        val raw = json("隔离回复。")
        val frame = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", raw)).put("finish_reason", "stop")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $frame\n\n")); server.start()
        try {
            val chunks = mutableListOf<String>()
            val result = DeepSeekDialogueClient(http, server.url("/isolated-incomplete-fixture").toString()).stream("fixture-not-real-key", "fixture", listOf(DialogueMessage("user", "isolated request"))) { chunks += it }
            assertEquals("隔离回复。", chunks.joinToString("")); assertFalse(result.completed); assertFalse(result.memoryValid); assertTrue(result.memoryCandidates.isEmpty())
            val diagnostics = requireNotNull(result.diagnostics)
            assertEquals(DialogueContentType.SSE, diagnostics.contentType); assertEquals(1, diagnostics.sseFrames)
            assertEquals(raw.length, diagnostics.contentChars); assertEquals("隔离回复。".length, diagnostics.decodedReplyChars)
            assertFalse(diagnostics.done); assertTrue(diagnostics.rootClosed && diagnostics.replySeen && diagnostics.replyClosed && diagnostics.replyNonBlank)
            assertEquals(DialogueFinishReason.STOP, diagnostics.finishReason); assertEquals(1, server.requestCount)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }
}
