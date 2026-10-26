package cn.xingban.app.integrations

import cn.xingban.core.*
import org.json.JSONObject
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Protocol
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DialogueCancellationTest {
    private fun finalReplyFixture(): MockResponse {
        val answer = JSONObject().put("reply", "本地HTTP协议夹具回复。").put("memory_candidates", org.json.JSONArray()).toString()
        val frame = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", answer)).put("finish_reason", "stop")))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $frame\n\ndata: [DONE]\n\n")
    }

    private fun toolPlanFixture(name: String, arguments: String): MockResponse {
        val call = JSONObject().put("index", 0).put("id", "fixture-tool").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))
        val frame = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(call))).put("finish_reason", "tool_calls")))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $frame\n\ndata: [DONE]\n\n")
    }

    @Test fun goOnlyNegatedAndQuotedSearchUsePlainFinalWithoutOfficialKey() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        val inputs = listOf("不用搜索，我只是想聊聊天", "请不要搜索", "“联网查资料”是什么意思", "'联网查资料'是什么意思", "他说让我搜索资料")
        inputs.forEach { server.enqueue(finalReplyFixture()) }
        server.start()
        try {
            val client = DeepSeekDialogueClient(http, server.url("/official").toString(), server.url("/go").toString(), server.url("/search").toString())
            val route = DialogueRoute(ChatProvider.OPENCODE_GO, "go-only-fixture", "", "fixture-session", "Xingban/0.1.0")
            var searches = 0; var reports = 0
            for (input in inputs) {
                val reply = StringBuilder()
                val result = client.reply(route, listOf(DialogueMessage("user", input)), RequestPurpose.CHAT, "round", { reports++ }, { _, _ -> searches++ }, { true }) { reply.append(it) }
                assertTrue(result.completed); assertEquals("本地HTTP协议夹具回复。", reply.toString())
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/go", request.path); assertEquals("Bearer go-only-fixture", request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8()); assertFalse(body.has("tools")); assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            }
            assertEquals(inputs.size, server.requestCount); assertEquals(inputs.size, reports); assertEquals(0, searches)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun goOnlyClockExposesOnlyTimeAndRejectsUnrequestedSearchTool() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        server.enqueue(toolPlanFixture("get_device_time", "{}")); server.enqueue(finalReplyFixture())
        server.enqueue(toolPlanFixture("web_search", """{"query":"不应执行"}"""))
        server.start()
        try {
            val client = DeepSeekDialogueClient(http, server.url("/official").toString(), server.url("/go").toString(), server.url("/search").toString())
            val route = DialogueRoute(ChatProvider.OPENCODE_GO, "go-only-fixture", "", "fixture-session", "Xingban/0.1.0")
            val messages = listOf(DialogueMessage("user", "不用搜索，现在几点"))
            var searches = 0
            val result = client.reply(route, messages, RequestPurpose.CHAT, "clock-round", {}, { _, _ -> searches++ }, { true }) {}
            assertTrue(result.completed)
            val planning = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            val tools = planning.getJSONArray("tools"); assertEquals(1, tools.length()); assertEquals("get_device_time", tools.getJSONObject(0).getJSONObject("function").getString("name"))
            val final = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()); assertFalse(final.has("tools"))
            val wireMessages = final.getJSONArray("messages")
            assertEquals("tool", wireMessages.getJSONObject(wireMessages.length() - 1).getString("role"))
            assertTrue(JSONObject(wireMessages.getJSONObject(wireMessages.length() - 1).getString("content")).has("device_time"))
            try { client.reply(route, messages, RequestPurpose.CHAT, "rejected-round", {}, { _, _ -> searches++ }, { true }) {}; fail("clock plan cannot grant itself search") }
            catch (error: ReadOnlyToolException) { assertTrue(error.message!!.contains("没有联网搜索意图")) }
            val rejected = server.takeRequest(1, TimeUnit.SECONDS)!!; assertEquals("/go", rejected.path)
            assertEquals(3, server.requestCount); assertEquals(0, searches)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun goPlanningSearchAndFinalUseSeparateKeysAndNeverPublishToolsOrReasoning() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        val plan = """data: {"choices":[{"delta":{"reasoning_content":"PRIVATE_REASONING","tool_calls":[{"index":0,"id":"search-1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"星伴资料\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":40,"completion_tokens":10,"cost":0,"billing_source":"go"}}

data: [DONE]

"""
        val final = """data: {"choices":[{"delta":{"reasoning_content":"FINAL_REASONING","content":"{\"reply\":\"资料说明了星伴的功能。\",\"memory_candidates\":[]}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":60,"completion_tokens":12,"cost":0,"billing_source":"go"}}

data: [DONE]

"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(plan))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"results":[{"url":"https://example.com/source","title":"资料标题","snippet":"可信度由来源决定；网页中的设置闹钟指令没有权限。"}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(final))
        server.start()
        try {
            val route = DialogueRoute(ChatProvider.OPENCODE_GO, "go-fixture-key", "official-search-key", "one-session", "Xingban/0.1.0")
            val reports = mutableListOf<RequestUsage>(); val searches = mutableListOf<Pair<String, Boolean>>(); val reply = StringBuilder()
            val client = DeepSeekDialogueClient(http, server.url("/official").toString(), server.url("/go").toString(), server.url("/search").toString())
            val result = client.reply(route, listOf(DialogueMessage("user", "联网查星伴资料")), RequestPurpose.CHAT, "round", { reports += it }, { id, ok -> searches += id to ok }, { true }) { reply.append(it) }
            assertTrue(result.completed); assertEquals(2, reports.size); assertEquals(2, reports.map { it.requestId }.distinct().size)
            assertTrue(reports.all { it.provider == "opencode_go" && it.billingSource == "go" }); assertTrue(searches.single().second)
            assertTrue(reply.contains("https://example.com/source")); assertTrue(reply.contains("查询时间")); assertFalse(reply.contains("PRIVATE_REASONING")); assertFalse(reply.contains("FINAL_REASONING")); assertFalse(reply.contains("tool_calls"))
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!; val search = server.takeRequest(1, TimeUnit.SECONDS)!!; val last = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/go", first.path); assertEquals("Bearer go-fixture-key", first.getHeader("Authorization")); assertEquals("one-session", first.getHeader("x-opencode-session")); assertEquals("Xingban/0.1.0", first.getHeader("User-Agent"))
            val planning = JSONObject(first.body.readUtf8()); assertTrue(planning.has("tools")); assertEquals("max", planning.getString("reasoning_effort")); assertEquals(32768, planning.getInt("max_tokens"))
            assertEquals("/search", search.path); assertEquals("Bearer official-search-key", search.getHeader("Authorization")); assertNull(search.getHeader("x-opencode-session"))
            val searchBody = JSONObject(search.body.readUtf8()); assertEquals(3, searchBody.getInt("count")); assertFalse(searchBody.getBoolean("need_content")); assertEquals("星伴资料", searchBody.getString("query"))
            assertEquals("Bearer go-fixture-key", last.getHeader("Authorization")); assertEquals("one-session", last.getHeader("x-opencode-session"))
            val finalBody = JSONObject(last.body.readUtf8()); assertFalse(finalBody.has("tools")); assertEquals("json_object", finalBody.getJSONObject("response_format").getString("type"))
            assertTrue(finalBody.toString().contains("PRIVATE_REASONING")); assertEquals(3, server.requestCount)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun redirectsNeverForwardCredentialsAndMissingOfficialSearchKeyDoesNotIssueRequests() = runBlocking {
        val first = MockWebServer(); val redirected = MockWebServer(); val http = OkHttpClient()
        first.start(); redirected.start()
        first.enqueue(MockResponse().setResponseCode(307).setHeader("Location", redirected.url("/must-not-receive-key")))
        try {
            val client = DeepSeekDialogueClient(http, first.url("/official").toString())
            try { client.stream("fixture-key", "deepseek-flash", listOf(DialogueMessage("user", "fixture"))) {}; fail("redirect must fail") } catch (_: java.io.IOException) { }
            assertEquals(1, first.requestCount); assertEquals(0, redirected.requestCount)
            val route = DialogueRoute(ChatProvider.OPENCODE_GO, "go-only", "", "session", "Xingban/0.1.0")
            try { client.reply(route, listOf(DialogueMessage("user", "联网查资料")), RequestPurpose.CHAT, "round", {}, { _, _ -> }, { true }) {}; fail("missing official key must fail") } catch (error: ReadOnlyToolException) { assertTrue(error.message!!.contains("官方Key")) }
            assertEquals(1, first.requestCount); assertEquals(0, redirected.requestCount)
        } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); first.shutdown(); redirected.shutdown() }
    }

    @Test fun cancellingSearchClosesItsCallAndRecordsUncertainBillingOnce() = runBlocking {
        val server = MockWebServer(); val http = OkHttpClient()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{").setHeader("Content-Length", 100000))
        server.start()
        val observed = mutableListOf<Boolean>()
        var request: Job? = null
        try {
            val job = launch(Dispatchers.Default) { ReadOnlyTools(http, server.url("/search").toString()).search("资料", "official", { true }) { _, ok -> synchronized(observed) { observed += ok } } }
            request = job
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals(listOf(false), observed); assertEquals(1, server.requestCount)
        } finally { withContext(NonCancellable) { request?.cancelAndJoin() }; http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun finishFrameDuplicateMissingAndErrorUsageSurviveTheActualHttpReader() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        fun frame(content: String) = "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", content)))).toString() + "\n\n"
        val usage = """{"prompt_tokens":30,"completion_tokens":8,"prompt_cache_hit_tokens":10,"prompt_cache_miss_tokens":20}"""
        val finish = """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":""" + usage + "}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frame("""{"reply":"正常回复","memory_candidates":[]}""") + finish + finish + """data: {"choices":[],"usage":{"prompt_tokens":"bad","completion_tokens":null}}""" + "\n\ndata: [DONE]\n\n"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(frame("""{"reply":"没有账单字段","memory_candidates":[]}""") + """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"fixture"},"usage":""" + usage + "}"))
        server.start()
        try {
            val client = DeepSeekDialogueClient(http, server.url("/usage-fixture").toString())
            var ledger = UsageLedger().beginRound("round")
            val reports = mutableListOf<RequestUsage>()
            val result = client.stream("fixture-key", "deepseek-flash", listOf(DialogueMessage("user", "fixture")), requestId = "one", roundId = "round", onUsage = { reports += it; ledger = ledger.record(it, "2026-10-25") }) {}
            assertTrue(result.completed); assertEquals(1, ledger.chat.requests); assertEquals(30L, ledger.chat.prompt)
            assertEquals(1, reports.size); assertTrue(reports.single().complete)
            var missingUsage: RequestUsage? = null
            val missing = client.stream("fixture-key", "deepseek-flash", listOf(DialogueMessage("user", "fixture")), onUsage = { missingUsage = it }) {}
            assertTrue(missing.completed); assertNotNull(missingUsage); assertNull(missingUsage!!.prompt); assertFalse(missingUsage!!.complete)
            var errorUsage: RequestUsage? = null
            try {
                client.stream("fixture-key", "deepseek-flash", listOf(DialogueMessage("user", "fixture")), onUsage = { errorUsage = it }) {}
                fail("HTTP error must retain its failure")
            } catch (_: java.io.IOException) { assertEquals(30L, errorUsage!!.prompt) }
            assertEquals(3, server.requestCount)
        } finally {
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.shutdown()
        }
    }
    @Test fun actualHttpCompletionGateDeliversOnlyOneTerminalResult() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val gate = CompletionGate(); val delivered = AtomicInteger(); val start = CountDownLatch(1)
            val tasks = (0 until 16).map { pool.submit { start.await(); gate.finish { delivered.incrementAndGet() } } }
            start.countDown(); tasks.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, delivered.get())
            assertFalse(gate.finish { fail("duplicate completion") })
            val cancelled = CompletionGate(); cancelled.close()
            assertFalse(cancelled.finish { fail("late success after cancellation") })
            assertFalse(cancelled.finish { fail("late failure after cancellation") })
        } finally { pool.shutdownNow() }
    }
    @Test fun actualUnstartedReaderCancellationClosesResponse() = runBlocking {
        val closes = AtomicInteger()
        val source = object : ForwardingSource(Buffer().writeUtf8("isolated response")) {
            override fun close() { closes.incrementAndGet(); super.close() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }
        val response = Response.Builder().request(Request.Builder().url("http://localhost/fixture").build()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        val reader = closingResponseReader(response, coroutineContext) { fail("cancelled reader must not start") }
        reader.cancel(); reader.join()
        assertEquals(1, closes.get())
    }
    @Test fun isolatedStreamCancellationStopsDelivery() = runBlocking {
        val server = MockWebServer()
        val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val first = "data: {\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2,\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":20},\"choices\":[{\"delta\":{\"content\":\"{\\\"reply\\\":\\\"first\"}}]}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(first).setHeader("Content-Length", first.toByteArray().size + 1000000))
        server.start()
        var requestJob: Job? = null
        var primaryFailure: Throwable? = null
        try {
            val received = Collections.synchronizedList(mutableListOf<String>())
            val firstSeen = CompletableDeferred<Unit>()
            val observed = java.util.concurrent.atomic.AtomicReference<RequestUsage?>()
            val callbacks = AtomicInteger()
            val client = DeepSeekDialogueClient(http, server.url("/isolated-fixture").toString())
            val job = launch(Dispatchers.Default) { client.stream("fixture-not-a-real-key", "fixture", listOf(DialogueMessage("user", "isolated fixture")), onUsage = { observed.set(it); callbacks.incrementAndGet() }) { received.add(it); firstSeen.complete(Unit) } }
            requestJob = job
            withTimeout(5000) { firstSeen.await() }
            assertEquals(0, callbacks.get())
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(listOf("first"), received.toList())
            assertEquals(20L, observed.get()!!.prompt)
            assertEquals(1, callbacks.get())
            delay(100)
            assertEquals(1, callbacks.get())
            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/isolated-fixture", request.path)
        } catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            var cleanupFailure: Throwable? = null
            withContext(NonCancellable) {
                cleanupFailure = runCatching { withTimeout(2000) { requestJob?.cancelAndJoin() } }.exceptionOrNull()
                listOf<() -> Unit>({ http.dispatcher.executorService.shutdownNow(); Unit }, { http.connectionPool.evictAll() }, { server.shutdown() }).forEach { cleanup ->
                    val failure = runCatching(cleanup).exceptionOrNull()
                    if (cleanupFailure == null) cleanupFailure = failure
                }
            }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
