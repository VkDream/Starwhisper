package cn.xingban.app.data

import cn.xingban.core.*
import org.json.JSONArray
import org.json.JSONObject

/** JSON whitelist shared by the Room snapshot and user initiated backup. Never contains credentials. */
object StateCodec {
    const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
    private fun array(values: Iterable<Any>) = JSONArray().apply { values.forEach { put(it) } }
    private fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it).also { s -> require(s.length <= 2000) } }
    private fun source(s: MemorySource) = JSONObject().put("id", s.id).put("kind", s.kind.name).put("time", s.occurredAtMillis).put("text", s.text)
    private fun requireKeys(o: JSONObject, expected: Set<String>) { require(o.keys().asSequence().toSet() == expected) }
    private fun strictLongValue(value: Any): Long {
        require(value is Int || value is Long) { "Expected integer" }
        return (value as Number).toLong()
    }
    private fun strictLong(o: JSONObject, key: String): Long = strictLongValue(o.get(key))
    private fun strictInt(o: JSONObject, key: String, range: IntRange): Int = strictLong(o, key).also { require(it in range.first.toLong()..range.last.toLong()) }.toInt()
    private fun strictString(o: JSONObject, key: String): String = (o.get(key) as? String) ?: error("Invalid $key")
    private fun strictStringArray(a: JSONArray): List<String> = List(a.length()) { (a.get(it) as? String) ?: error("Invalid string array item") }
    private fun strictNonnegativeDouble(o: JSONObject, key: String): Double = ((o.get(key) as? Number) ?: error("Invalid $key")).toDouble().also { require(it.isFinite() && it >= 0.0) }
    private fun validDay(day: String): Boolean {
        if (day.isEmpty()) return true
        if (!day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
        val year = day.substring(0, 4).toInt(); val month = day.substring(5, 7).toInt(); val date = day.substring(8, 10).toInt()
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; in 1..12 -> 31; else -> return false }
        return date in 1..days
    }
    private fun totals(t: UsageTotals) = JSONObject().put("requests", t.requests).put("prompt", t.prompt).put("completion", t.completion).put("cacheHit", t.cacheHit).put("cacheMiss", t.cacheMiss)
        .put("promptUnknown", t.promptUnknown).put("completionUnknown", t.completionUnknown).put("cacheHitUnknown", t.cacheHitUnknown).put("cacheMissUnknown", t.cacheMissUnknown)
        .put("unpriced", t.unpriced).put("feeLow", t.feeLow).put("feeHigh", t.feeHigh)
        .put("goRequests", t.goRequests).put("goReportedCost", t.goReportedCost).put("goCostUnknown", t.goCostUnknown).put("goBillingSources", array(t.goBillingSources.sorted()))
    private fun usage(u: UsageLedger) = JSONObject().put("day", u.day).put("chat", totals(u.chat)).put("summary", totals(u.summary)).put("roundId", u.roundId)
        .put("roundChat", totals(u.roundChat)).put("roundSummary", totals(u.roundSummary)).put("recentRequestIds", array(u.recentRequestIds)).put("searchSuccess", u.searchSuccess).put("searchUncertain", u.searchUncertain)
    private fun decodeTotals(o: JSONObject): UsageTotals {
        val oldKeys = setOf("requests", "prompt", "completion", "cacheHit", "cacheMiss", "promptUnknown", "completionUnknown", "cacheHitUnknown", "cacheMissUnknown", "unpriced", "feeLow", "feeHigh")
        requireKeys(o, if (o.has("goRequests")) oldKeys + setOf("goRequests", "goReportedCost", "goCostUnknown", "goBillingSources") else oldKeys)
        val requests = strictInt(o, "requests", 0..1_000_000)
        val promptUnknown = strictInt(o, "promptUnknown", 0..1_000_000); val completionUnknown = strictInt(o, "completionUnknown", 0..1_000_000)
        val cacheHitUnknown = strictInt(o, "cacheHitUnknown", 0..1_000_000); val cacheMissUnknown = strictInt(o, "cacheMissUnknown", 0..1_000_000)
        val unpriced = strictInt(o, "unpriced", 0..1_000_000)
        require(promptUnknown <= requests && completionUnknown <= requests && cacheHitUnknown <= requests && cacheMissUnknown <= requests && unpriced <= requests)
        val feeLow = strictNonnegativeDouble(o, "feeLow"); val feeHigh = strictNonnegativeDouble(o, "feeHigh"); require(feeHigh >= feeLow)
        fun amount(key: String) = strictLong(o, key).also { require(it in 0..1_000_000_000_000L) }
        val goRequests = if (o.has("goRequests")) strictInt(o, "goRequests", 0..requests) else 0
        val goUnknown = if (o.has("goRequests")) strictInt(o, "goCostUnknown", 0..goRequests) else 0
        val goCost = if (o.has("goRequests")) strictNonnegativeDouble(o, "goReportedCost") else 0.0
        val goSources = if (o.has("goRequests")) strictStringArray(o.getJSONArray("goBillingSources")).also { require(it.size <= 8 && it.distinct().size == it.size && it.all { s -> s.length in 1..80 }) }.toSet() else emptySet()
        return UsageTotals(requests = requests, prompt = amount("prompt"), completion = amount("completion"), cacheHit = amount("cacheHit"), cacheMiss = amount("cacheMiss"),
            promptUnknown = promptUnknown, completionUnknown = completionUnknown, cacheHitUnknown = cacheHitUnknown, cacheMissUnknown = cacheMissUnknown, unpriced = unpriced, feeLow = feeLow, feeHigh = feeHigh,
            goRequests = goRequests, goReportedCost = goCost, goCostUnknown = goUnknown, goBillingSources = goSources)
    }
    private fun decodeUsage(root: JSONObject): UsageLedger {
        if (!root.has("usage")) return UsageLedger()
        val o = root.get("usage") as? JSONObject ?: error("Invalid usage")
        val oldKeys = setOf("day", "chat", "summary", "roundId", "roundChat", "roundSummary", "recentRequestIds")
        requireKeys(o, if (o.has("searchSuccess")) oldKeys + setOf("searchSuccess", "searchUncertain") else oldKeys)
        val day = strictString(o, "day"); require(validDay(day))
        val roundId = strictString(o, "roundId"); require(roundId.length <= 200)
        val idsJson = o.get("recentRequestIds") as? JSONArray ?: error("Invalid recentRequestIds")
        require(idsJson.length() <= 64)
        val ids = strictStringArray(idsJson); require(ids.all { it.length in 1..200 } && ids.distinct().size == ids.size)
        return UsageLedger(day = day, chat = decodeTotals(o.get("chat") as? JSONObject ?: error("Invalid chat totals")), summary = decodeTotals(o.get("summary") as? JSONObject ?: error("Invalid summary totals")), roundId = roundId,
            roundChat = decodeTotals(o.get("roundChat") as? JSONObject ?: error("Invalid round chat totals")), roundSummary = decodeTotals(o.get("roundSummary") as? JSONObject ?: error("Invalid round summary totals")), recentRequestIds = ids,
            searchSuccess = if (o.has("searchSuccess")) strictInt(o, "searchSuccess", 0..1_000_000) else 0, searchUncertain = if (o.has("searchUncertain")) strictInt(o, "searchUncertain", 0..1_000_000) else 0)
    }
    private fun decodeSummary(root: JSONObject, memory: MemoryState, messages: List<SavedMessage>): ConversationSummary? {
        if (!root.has("summary")) return null
        val value = root.get("summary")
        if (value === JSONObject.NULL) return null
        val o = value as? JSONObject ?: error("Invalid summary")
        requireKeys(o, setOf("text", "sourceIds", "contextBoundaryMillis"))
        val text = strictString(o, "text"); require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 1200)
        val sourcesJson = o.get("sourceIds") as? JSONArray ?: error("Invalid summary sourceIds")
        require(sourcesJson.length() in 1..20000)
        val sourceIds = strictStringArray(sourcesJson); require(sourceIds.all { it.length in 1..200 } && sourceIds.distinct().size == sourceIds.size)
        val boundaryValue = o.get("contextBoundaryMillis")
        val boundary = if (boundaryValue === JSONObject.NULL) null else strictLongValue(boundaryValue)
        require(boundary == memory.contextBoundaryMillis)
        val summary = ConversationSummary(text, sourceIds.toSet(), boundary)
        val history = messages.map { ChatMessage(it.id, if (it.role == "user") ChatRole.USER else ChatRole.ASSISTANT, it.text, it.time, it.turnId, it.status) }
        require(ConversationHistory.validSummary(summary, memory, history))
        return summary
    }
    fun encode(data: AppData): String {
        val c = data.character; val s = data.settings
        val raw = JSONObject().put("format", "xingban-backup").put("version", 1)
            .put("messages", array(data.messages.map { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("time", it.time).put("status", it.status).apply { if (it.turnId != null) put("turnId", it.turnId) } }))
            .put("character", JSONObject().put("pleasure", c.pleasure).put("activation", c.activation).put("irritation", c.irritation).put("updated", c.updatedAtMillis).put("interaction", c.lastInteractionAtMillis).put("touch", c.lastTouchAtMillis ?: JSONObject.NULL).put("events", array(c.appliedEventIds)))
            .put("memory", JSONObject().put("revision", data.memory.revision).put("boundary", data.memory.contextBoundaryMillis ?: JSONObject.NULL).put("suppressed", array(data.memory.suppressedSourceIds)).put("items", array(data.memory.items.map {
                JSONObject().put("id", it.id).put("key", it.key).put("text", it.text).put("sources", array(it.sources.map(::source))).put("revision", it.revision).put("created", it.createdAtMillis).put("updated", it.updatedAtMillis).put("status", it.status.name).put("supersedes", it.supersedesId ?: JSONObject.NULL)
            })))
            .put("settings", JSONObject().put("provider", s.provider).put("model", s.model).put("speechMode", s.speechMode).put("voiceId", s.voiceId).put("systemVoice", s.systemVoice).put("address", s.userAddress).put("warmth", s.warmth).put("playfulness", s.playfulness).put("tsundere", s.tsundere).put("musicEnabled", s.musicEnabled).put("musicVolume", s.musicVolume).put("musicTrack", s.musicTrack.name).put("musicSource", s.musicSource))
            .put("reunionDay", data.reunionDay).put("reunionShownAt", data.reunionShownAt)
            .put("activity", JSONObject().put("type", data.activity.type?.name ?: JSONObject.NULL).put("phase", data.activity.phase.name).put("mode", data.activity.mode.name).put("revision", data.activity.revision).put("scene", data.activity.autonomousScene).put("silent", data.activity.silent))
            .put("departedAtMillis", data.departedAtMillis ?: JSONObject.NULL)
            .put("summary", data.summary?.let { JSONObject().put("text", it.text).put("sourceIds", array(it.sourceIds.sorted())).put("contextBoundaryMillis", it.contextBoundaryMillis ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("summaryAttemptTurns", data.summaryAttemptTurns).put("usage", usage(data.usage)).toString()
        decode(raw) // Saving and importing use exactly the same field and capacity checks.
        return raw
    }
    fun decode(raw: String): AppData {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "备份超过 8MB" }
        val root = JSONObject(raw)
        require(root.getString("format") == "xingban-backup" && root.getInt("version") == 1) { "不是受支持的星伴备份" }
        val a = root.getJSONArray("messages"); require(a.length() <= 20000)
        val messages = List(a.length()) {
            val o = a.getJSONObject(it)
            val turnId = if (o.has("turnId")) (o.get("turnId") as? String)?.also { require(it.length in 1..200) } ?: error("Invalid turnId") else null
            SavedMessage(o.getString("id"), o.getString("role"), o.getString("text"), o.getLong("time"), o.getString("status"), turnId).also { m ->
                require(m.id.length in 1..200 && m.role in setOf("user", "assistant") && m.text.length <= 100000 && m.time >= 0 && m.status in setOf("completed", "pending", "interrupted", "failed", "local"))
            }
        }; require(messages.map { it.id }.distinct().size == messages.size)
        val userIds = messages.filter { it.role == "user" }.map { it.id }.toSet()
        require(messages.all { it.turnId == null || (it.role == "assistant" && it.turnId in userIds) })
        val c = root.getJSONObject("character"); require(c.getJSONArray("events").length() <= 100000)
        val character = CharacterState(c.getDouble("pleasure"), c.getDouble("activation"), c.getDouble("irritation"), c.getLong("updated"), c.getLong("interaction"), if (c.isNull("touch")) null else c.getLong("touch"), strings(c.getJSONArray("events")).toSet())
        val m = root.getJSONObject("memory"); val itemsJson = m.getJSONArray("items"); require(itemsJson.length() <= 10000)
        val items = List(itemsJson.length()) {
            val o = itemsJson.getJSONObject(it); val sources = o.getJSONArray("sources"); require(sources.length() in 1..100)
            MemoryItem(o.getString("id"), o.getString("key"), o.getString("text"), List(sources.length()) { n ->
                val p = sources.getJSONObject(n); MemorySource(p.getString("id"), MemorySourceKind.valueOf(p.getString("kind")), p.getLong("time"), p.getString("text"))
            }, o.getLong("revision"), o.getLong("created"), o.getLong("updated"), MemoryStatus.valueOf(o.getString("status")), if (o.isNull("supersedes")) null else o.getString("supersedes"))
        }
        require(items.map { it.id }.distinct().size == items.size)
        val boundary = if (m.isNull("boundary")) null else m.getLong("boundary")
        val memory = MemoryState(m.getLong("revision"), items, strings(m.getJSONArray("suppressed")).toSet(), boundary)
        require(memory.revision >= 0 && items.all { it.revision <= memory.revision })
        val maxTime = 253402300799999L
        require(messages.all { it.time in 0..maxTime } && character.updatedAtMillis in 0..maxTime && character.lastInteractionAtMillis in 0..maxTime && (character.lastTouchAtMillis == null || character.lastTouchAtMillis in 0..maxTime))
        require(boundary == null || boundary in 0..maxTime)
        require(items.all { item -> item.createdAtMillis in 0..maxTime && item.updatedAtMillis in 0..maxTime && item.sources.all { it.occurredAtMillis in 0..maxTime } })
        val summary = decodeSummary(root, memory, messages)
        val summaryAttemptTurns = if (root.has("summaryAttemptTurns")) strictInt(root, "summaryAttemptTurns", 0..20000) else 0
        val usage = decodeUsage(root)
        val s = root.getJSONObject("settings")
        val musicEnabled = if (s.has("musicEnabled")) (s.get("musicEnabled") as? Boolean) ?: error("Invalid musicEnabled") else true
        val musicVolume = if (s.has("musicVolume")) ((s.get("musicVolume") as? Number) ?: error("Invalid musicVolume")).toDouble() else 0.15
        require(musicVolume.isFinite() && musicVolume in 0.0..1.0)
        val musicTrack = if (s.has("musicTrack")) BackgroundMusicTrack.valueOf((s.get("musicTrack") as? String) ?: error("Invalid musicTrack")) else BackgroundMusicTrack.MEDITATION_IMPROMPTU_01
        val musicSource = if (s.has("musicSource")) strictString(s, "musicSource").also { require(it in setOf("local", "qq")) } else "local"
        val settings = AppSettings(s.getString("provider"), s.getString("model"), s.getString("speechMode"), s.getString("voiceId"), s.getString("systemVoice"), s.getString("address"), s.getDouble("warmth"), s.getDouble("playfulness"), s.getDouble("tsundere"), musicEnabled, musicVolume, musicTrack, musicSource)
        require(settings.provider in setOf("DeepSeek", "OpenCode Go") && settings.model.matches(Regex("[A-Za-z0-9._-]{1,100}")) && settings.speechMode in setOf("off", "system", "minimax") && settings.voiceId.length <= 200 && settings.systemVoice.length <= 300)
        CharacterProfile(userAddress = settings.userAddress, warmth = settings.warmth, playfulness = settings.playfulness, tsundere = settings.tsundere)
        val shown = root.getLong("reunionShownAt"); require(shown >= 0)
        val activity = if (!root.has("activity")) CompanionActivity() else {
            val o = root.getJSONObject("activity")
            require(o.keys().asSequence().toSet() == setOf("type", "phase", "mode", "revision", "scene", "silent") && o.get("phase") is String && o.get("mode") is String && o.get("silent") is Boolean)
            val type = if (o.isNull("type")) null else ActivityType.valueOf((o.get("type") as? String) ?: error("Invalid activity type"))
            val scene = strictLong(o, "scene"); require(scene in 0..5)
            CompanionActivity(type, ActivityPhase.valueOf(o.getString("phase")), ActivityMode.valueOf(o.getString("mode")), strictLong(o, "revision"), scene.toInt(), o.getBoolean("silent"))
        }
        val departed = if (!root.has("departedAtMillis") || root.isNull("departedAtMillis")) null else strictLong(root, "departedAtMillis").also { require(it in 0..maxTime) }
        return AppData(messages, character, memory, settings, root.getString("reunionDay").also { require(it.length <= 40) }, shown, activity, departed, summary, summaryAttemptTurns, usage)
    }
}
