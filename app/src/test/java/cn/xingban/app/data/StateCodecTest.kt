package cn.xingban.app.data

import cn.xingban.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StateCodecTest {
    @Test fun providerMusicSourceAndSeparateSupplierCostsRoundTripWithoutCredentials() {
        val data = sample().copy(settings = AppSettings(provider = "OpenCode Go", model = "deepseek-v4.1-flash", musicSource = "qq"),
            usage = UsageLedger(day = "2026-10-26", chat = UsageTotals(requests = 2, goRequests = 2, goReportedCost = 0.5, goCostUnknown = 1, goBillingSources = setOf("go")), searchSuccess = 3, searchUncertain = 1))
        val raw = StateCodec.encode(data)
        assertEquals(data, StateCodec.decode(raw)); assertFalse(raw.contains("apiKey"))
        val old = JSONObject(StateCodec.encode(sample())).apply {
            getJSONObject("settings").remove("musicSource")
            getJSONObject("usage").apply {
                remove("searchSuccess"); remove("searchUncertain")
                listOf("chat", "summary", "roundChat", "roundSummary").forEach { key -> getJSONObject(key).apply { remove("goRequests"); remove("goReportedCost"); remove("goCostUnknown"); remove("goBillingSources") } }
            }
        }
        assertEquals(sample(), StateCodec.decode(old.toString()))
        for ((key, value) in listOf("provider" to "arbitrary", "musicSource" to "arbitrary")) {
            val invalid = JSONObject(raw); invalid.getJSONObject("settings").put(key, value)
            assertThrows(Exception::class.java) { StateCodec.decode(invalid.toString()) }
        }
    }
    @Test fun optionalSummaryCursorAndUsageRoundTripAndDefaultForLegacySnapshots() {
        val data = sample().let { base ->
            val messages = base.messages + SavedMessage("assistant-1", "assistant", "知道了。", 1001, turnId = "user-1")
            base.copy(messages = messages, summary = ConversationSummary("用户喜欢喝茶。", setOf("user-1", "assistant-1")), summaryAttemptTurns = 3,
                usage = UsageLedger(day = "2026-10-25", chat = UsageTotals(requests = 2, prompt = 20, completion = 10, cacheHit = 4, cacheMiss = 16, feeLow = 0.01, feeHigh = 0.02),
                    summary = UsageTotals(requests = 1, prompt = 8, completion = 3, promptUnknown = 1, unpriced = 1, feeLow = 0.0, feeHigh = 0.03), roundId = "user-1",
                    roundChat = UsageTotals(requests = 1, prompt = 10, completion = 5, feeLow = 0.01, feeHigh = 0.01), roundSummary = UsageTotals(requests = 1, prompt = 8, completion = 3, feeLow = 0.0, feeHigh = 0.03),
                    recentRequestIds = listOf("req-1", "req-2")))
        }
        val originalMessages = data.messages.toList()
        assertEquals(data, StateCodec.decode(StateCodec.encode(data)))
        assertEquals(originalMessages, data.messages)

        val old = JSONObject(StateCodec.encode(data)).apply { remove("summary"); remove("summaryAttemptTurns"); remove("usage") }
        val legacy = StateCodec.decode(old.toString())
        assertNull(legacy.summary); assertEquals(0, legacy.summaryAttemptTurns); assertEquals(UsageLedger(), legacy.usage)
        assertEquals(originalMessages, legacy.messages)
    }

    @Test fun invalidSummaryCursorAndUsageFieldsAreRejected() {
        val data = sample().let { base ->
            val messages = base.messages + SavedMessage("assistant-1", "assistant", "知道了。", 1001, turnId = "user-1")
            base.copy(messages = messages, summary = ConversationSummary("用户喜欢喝茶。", setOf("user-1", "assistant-1")))
        }
        val encoded = StateCodec.encode(data)
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("messages").getJSONObject(0).put("role", "tool") },
            { it.put("summaryAttemptTurns", "1") },
            { it.put("summaryAttemptTurns", 20001) },
            { it.getJSONObject("summary").put("text", 1) },
            { it.getJSONObject("summary").put("sourceIds", JSONArray().put("user-1").put("user-1")) },
            { it.getJSONObject("summary").put("sourceIds", JSONArray().put("missing")) },
            { it.getJSONObject("summary").put("contextBoundaryMillis", 1000) },
            { it.getJSONObject("usage").getJSONObject("chat").put("prompt", 1.5) },
            { it.getJSONObject("usage").getJSONObject("chat").put("promptUnknown", 3) },
            { it.getJSONObject("usage").getJSONObject("summary").remove("prompt") },
            { it.getJSONObject("usage").getJSONObject("roundChat").put("feeHigh", 0.0).put("feeLow", 0.1) },
            { it.getJSONObject("usage").put("day", "2026-02-30") },
            { it.getJSONObject("usage").put("recentRequestIds", JSONArray().put("req-1").put("req-1")) }
        )
        corruptions.forEach { corrupt ->
            val root = JSONObject(encoded); corrupt(root)
            assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        }
    }

    @Test fun musicSettingsDefaultForOldSnapshotsAndRoundTripEverySelection() {
        val old = JSONObject(StateCodec.encode(sample()))
        old.getJSONObject("settings").apply { remove("musicEnabled"); remove("musicVolume"); remove("musicTrack") }
        assertEquals(sample(), StateCodec.decode(old.toString()))
        for (track in BackgroundMusicTrack.entries) {
            val data = sample().copy(settings = AppSettings(musicEnabled = false, musicVolume = 0.0, musicTrack = track))
            assertEquals(data, StateCodec.decode(StateCodec.encode(data)))
            val loud = data.copy(settings = data.settings.copy(musicEnabled = true, musicVolume = 1.0))
            assertEquals(loud, StateCodec.decode(StateCodec.encode(loud)))
        }
        assertEquals(BackgroundMusicTrack.MEDITATION_IMPROMPTU_01, BackgroundMusicTrack.MOONSTONE.next())
    }

    @Test fun invalidMusicFieldsAreRejectedBeforeReplacingData() {
        for ((key, value) in listOf("musicEnabled" to "true", "musicEnabled" to 1, "musicEnabled" to JSONObject.NULL, "musicVolume" to "0.15", "musicVolume" to true, "musicVolume" to JSONObject.NULL, "musicVolume" to -0.01, "musicVolume" to 1.01, "musicTrack" to "unknown", "musicTrack" to 1, "musicTrack" to JSONObject.NULL)) {
            val root = JSONObject(StateCodec.encode(sample()))
            root.getJSONObject("settings").put(key, value)
            assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            assertThrows(Exception::class.java) { StateCodec.encode(sample().copy(settings = AppSettings(musicVolume = value))) }
        }
    }

    @Test fun optionalTurnIdKeepsOneUserSourceAndRejectsInvalidLinks() {
        val data = sample().let { it.copy(messages = it.messages + SavedMessage("a1", "assistant", "第一条", 1001, turnId = "user-1") + SavedMessage("a2", "assistant", "第二条", 1002, turnId = "user-1")) }
        assertEquals(data, StateCodec.decode(StateCodec.encode(data)))
        for (bad in listOf<Any>(JSONObject.NULL, "", 1, "missing-user")) {
            val root = JSONObject(StateCodec.encode(data)); root.getJSONArray("messages").getJSONObject(1).put("turnId", bad)
            assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        }
    }
    @Test fun optionalActivityAndLocalDepartureRoundTripWithoutChangingOldBackup() {
        val old = JSONObject(StateCodec.encode(sample())).apply { remove("activity"); remove("departedAtMillis") }
        assertEquals(sample(), StateCodec.decode(old.toString()))
        val current = sample().copy(activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.WATCH)).localStop(true), departedAtMillis = 5000)
        assertEquals(current, StateCodec.decode(StateCodec.encode(current)))
        for (value in listOf<Any>("5000", 1.5, -1L, Long.MAX_VALUE, true)) {
            val root = JSONObject(StateCodec.encode(current)).put("departedAtMillis", value)
            assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        }
        for ((key, value) in listOf("type" to "EXECUTE", "phase" to "RUN", "mode" to "remote", "revision" to -1, "revision" to "1", "scene" to 6, "silent" to "true")) {
            val root = JSONObject(StateCodec.encode(current)); root.getJSONObject("activity").put(key, value)
            assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        }
    }
    private fun sample(): AppData {
        val source = MemorySource("user-1", MemorySourceKind.USER_MESSAGE, 1000, "我喜欢喝茶")
        val memory = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single()).state
        return AppData(listOf(SavedMessage("user-1", "user", source.text, 1000)), memory = memory)
    }
    @Test fun roundTripPreservesSourcesAndRevisions() {
        val data = sample(); assertEquals(data, StateCodec.decode(StateCodec.encode(data)))
        assertFalse(StateCodec.encode(data).contains("apiKey", ignoreCase = true))
    }
    @Test fun roundTripPreservesForgetBoundaryAndSuppression() {
        val data = sample(); val forgotten = data.copy(memory = MemoryRules.forget(data.memory, data.memory.items.single().id, 2000).state)
        assertEquals(forgotten, StateCodec.decode(StateCodec.encode(forgotten)))
        assertTrue(forgotten.memory.suppressedSourceIds.contains("user-1"))
    }
    @Test fun unknownFormatRejectedBeforeReplacement() {
        val original = sample(); val raw = JSONObject(StateCodec.encode(original)).put("version", 999).toString()
        assertThrows(Exception::class.java) { StateCodec.decode(raw) }; assertEquals(1, original.memory.items.size)
    }
    @Test fun invalidSettingsAndUnsafeClockRejected() {
        val root = JSONObject(StateCodec.encode(sample()))
        root.getJSONObject("settings").put("provider", "arbitrary-url")
        assertThrows(Exception::class.java) { StateCodec.decode(root.toString()) }
        val clock = JSONObject(StateCodec.encode(sample())); clock.getJSONArray("messages").getJSONObject(0).put("time", Long.MAX_VALUE)
        assertThrows(Exception::class.java) { StateCodec.decode(clock.toString()) }
    }
    @Test fun unknownCredentialsAreNotCarriedIntoReExport() {
        val root = JSONObject(StateCodec.encode(sample())).put("apiKey", "fixture-not-a-real-secret")
        val exported = StateCodec.encode(StateCodec.decode(root.toString()))
        assertFalse(exported.contains("fixture-not-a-real-secret"))
    }
    @Test fun encodeEnforcesTheSameMessageAndEventLimitsAsImport() {
        val messages = List(20000) { SavedMessage("m$it", "user", "fixture", 1000L + it) }
        val data = AppData(messages = messages)
        assertEquals(20000, StateCodec.decode(StateCodec.encode(data)).messages.size)
        assertThrows(Exception::class.java) { StateCodec.encode(data.copy(messages = messages + SavedMessage("overflow", "user", "fixture", 30000))) }
        val events = (0 until 100000).map { "e$it" }.toSet()
        val eventData = AppData(character = CharacterState(appliedEventIds = events))
        assertEquals(100000, StateCodec.decode(StateCodec.encode(eventData)).character.appliedEventIds.size)
        assertThrows(Exception::class.java) { StateCodec.encode(eventData.copy(character = eventData.character.copy(appliedEventIds = events + "overflow"))) }
    }
    @Test fun encodeEnforcesTextAndUtf8ByteLimits() {
        val data = AppData(messages = listOf(SavedMessage("m1", "assistant", "好".repeat(100000), 1000)))
        assertEquals(data, StateCodec.decode(StateCodec.encode(data)))
        assertThrows(Exception::class.java) { StateCodec.encode(data.copy(messages = listOf(data.messages.single().copy(text = "好".repeat(100001))))) }
        val within = AppData(messages = List(25) { SavedMessage("m$it", "assistant", "好".repeat(100000), 1000L + it) })
        assertEquals(25, StateCodec.decode(StateCodec.encode(within)).messages.size)
        assertThrows(Exception::class.java) { StateCodec.encode(within.copy(messages = List(29) { SavedMessage("m$it", "assistant", "好".repeat(100000), 1000L + it) })) }
    }
}
