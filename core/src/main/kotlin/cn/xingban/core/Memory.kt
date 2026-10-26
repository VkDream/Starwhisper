package cn.xingban.core

import java.io.Serializable
import java.util.Locale

enum class MemorySourceKind { USER_MESSAGE, USER_CONFIRMATION, APP_EVENT }
enum class MemoryStatus { ACTIVE, SUPERSEDED, FORGOTTEN }
enum class MemoryCandidateOrigin { RULE, MODEL }

data class MemorySource(val id: String, val kind: MemorySourceKind, val occurredAtMillis: Long, val text: String) : Serializable {
    init {
        require(id.isNotBlank() && id.length <= 200 && occurredAtMillis >= 0)
        require(text.isNotBlank() && text.length <= 20_000)
    }
}

data class MemoryItem(
    val id: String,
    val key: String,
    val text: String,
    val sources: List<MemorySource>,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val supersedesId: String? = null,
) : Serializable {
    init {
        require(id.isNotBlank() && key.isNotBlank() && key.length <= 180)
        require(text.isNotBlank() && text.length <= 500 && sources.isNotEmpty())
        require(revision > 0 && createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis)
    }
}

data class MemoryState(
    val revision: Long = 0,
    val items: List<MemoryItem> = emptyList(),
    val suppressedSourceIds: Set<String> = emptySet(),
    val contextBoundaryMillis: Long? = null,
) : Serializable {
    init {
        require(revision >= 0 && items.all { it.revision <= revision })
        require(items.map { it.id }.toSet().size == items.size)
        require(contextBoundaryMillis == null || contextBoundaryMillis >= 0)
    }
}

data class MemoryCandidate(
    val key: String,
    val text: String,
    val source: MemorySource,
    val basedOnRevision: Long,
    val origin: MemoryCandidateOrigin = MemoryCandidateOrigin.RULE,
) : Serializable

/** The model selects evidence only. The app binds the actual completed USER message. */
data class SemanticMemoryCandidate(val key: String, val evidence: String) : Serializable

data class SemanticMemoryOutcome(val index: Int, val reason: MemoryChangeReason, val item: MemoryItem? = null)

data class SemanticMemoryBatchChange(
    val state: MemoryState,
    val rejection: MemoryChangeReason? = null,
    val outcomes: List<SemanticMemoryOutcome> = emptyList(),
) {
    val changed: Boolean get() = outcomes.any { it.reason == MemoryChangeReason.APPLIED }
}

enum class MemoryChangeReason {
    APPLIED, STALE_REVISION, SOURCE_SUPPRESSED, INVALID_CANDIDATE, ALREADY_KNOWN,
    CONFLICT_REQUIRES_CONFIRMATION, REQUIRES_CONFIRMATION, NOT_FOUND, INVALID_SOURCE, SOURCE_BEFORE_CONTEXT_BOUNDARY,
}

data class MemoryChange(val state: MemoryState, val reason: MemoryChangeReason, val item: MemoryItem? = null) {
    val changed: Boolean get() = reason == MemoryChangeReason.APPLIED
}

/** Pure transformations; the app commits the returned state and revision in one transaction. */
object MemoryRules {
    const val MAX_SEMANTIC_CANDIDATES = 6
    private val uncertainMarkers = listOf("假如", "如果", "假设", "可能", "也许", "他说", "她说", "转述", "扮演", "例如", "比如", "要是", "我不", "不喜欢", "不是", "没说", "不要记", "别记")
    private val sentenceSeparators = Regex("[。！？!?；;\\r\\n]+")

    fun extract(source: MemorySource, memoryRevision: Long): List<MemoryCandidate> {
        require(memoryRevision >= 0)
        if (source.kind != MemorySourceKind.USER_MESSAGE) return emptyList()
        val original = source.text.trim().trimEnd('。', '！', '!', '.', ' ') 
        if (original.length !in 3..160 || original.any { it == '\n' || it == '？' || it == '?' || it == '。' || it == '；' || it == ';' }) return emptyList()
        if (uncertainMarkers.any { original.contains(it) }) return emptyList()
        val explicitlyRemember = original.startsWith("记住")
        val statement = if (explicitlyRemember) original.removePrefix("记住").trimStart('：', ':', '，', ',', ' ') else original
        val (key, fact) = when {
            statement.startsWith("我叫") -> "user.name" to statement
            statement.startsWith("叫我") -> "user.address" to statement
            statement.startsWith("我喜欢") -> "user.preference:${normalize(statement.removePrefix("我喜欢"))}" to statement
            explicitlyRemember -> "user.note:${normalize(statement)}" to statement
            else -> return emptyList()
        }
        val value = when {
            statement.startsWith("我叫") -> statement.removePrefix("我叫")
            statement.startsWith("叫我") -> statement.removePrefix("叫我")
            statement.startsWith("我喜欢") -> statement.removePrefix("我喜欢")
            else -> statement
        }.trim()
        if (value.isBlank() || key.length > 180 || fact.length > 500) return emptyList()
        return listOf(MemoryCandidate(key, fact, source, memoryRevision))
    }

    fun commit(state: MemoryState, candidate: MemoryCandidate): MemoryChange {
        if (candidate.basedOnRevision != state.revision) return MemoryChange(state, MemoryChangeReason.STALE_REVISION)
        if (candidate.source.id in state.suppressedSourceIds) return MemoryChange(state, MemoryChangeReason.SOURCE_SUPPRESSED)
        if (state.contextBoundaryMillis != null && candidate.source.occurredAtMillis <= state.contextBoundaryMillis) return MemoryChange(state, MemoryChangeReason.SOURCE_BEFORE_CONTEXT_BOUNDARY)
        if (candidate.origin == MemoryCandidateOrigin.MODEL) return MemoryChange(state, MemoryChangeReason.REQUIRES_CONFIRMATION)
        // A RULE label is not authority: reproduce the bounded extraction from its actual source.
        if (candidate !in extract(candidate.source, state.revision)) return MemoryChange(state, MemoryChangeReason.INVALID_CANDIDATE)
        return appendSourcedFact(state, candidate.key, candidate.text, candidate.source)
    }

    fun commitSemanticBatch(state: MemoryState, source: MemorySource, basedOnRevision: Long, candidates: List<SemanticMemoryCandidate>): SemanticMemoryBatchChange {
        // One revision check covers this whole response. Individual candidates have no revision
        // to rewrite as earlier accepted candidates advance the local state.
        val rejection = when {
            basedOnRevision != state.revision -> MemoryChangeReason.STALE_REVISION
            source.kind != MemorySourceKind.USER_MESSAGE -> MemoryChangeReason.INVALID_SOURCE
            source.id in state.suppressedSourceIds -> MemoryChangeReason.SOURCE_SUPPRESSED
            state.contextBoundaryMillis != null && source.occurredAtMillis <= state.contextBoundaryMillis -> MemoryChangeReason.SOURCE_BEFORE_CONTEXT_BOUNDARY
            candidates.size > MAX_SEMANTIC_CANDIDATES -> MemoryChangeReason.INVALID_CANDIDATE
            else -> null
        }
        if (rejection != null) return SemanticMemoryBatchChange(state, rejection)
        var updated = state
        val outcomes = candidates.mapIndexed { index, candidate ->
            if (candidate.key.isBlank() || candidate.key.length > 180 || candidate.key.any { it.isISOControl() } ||
                candidate.evidence.isBlank() || candidate.evidence.length > 500 || !supportedEvidence(source.text, candidate.evidence)) {
                SemanticMemoryOutcome(index, MemoryChangeReason.INVALID_CANDIDATE)
            } else {
                val change = appendSourcedFact(updated, candidate.key, candidate.evidence, source)
                updated = change.state
                SemanticMemoryOutcome(index, change.reason, change.item)
            }
        }
        return SemanticMemoryBatchChange(updated, outcomes = outcomes)
    }

    fun correct(state: MemoryState, itemId: String, replacementText: String, source: MemorySource, nowMillis: Long): MemoryChange {
        require(nowMillis >= 0 && nowMillis >= (state.contextBoundaryMillis ?: 0) && nowMillis >= source.occurredAtMillis)
        val old = state.items.find { it.id == itemId && it.status == MemoryStatus.ACTIVE } ?: return MemoryChange(state, MemoryChangeReason.NOT_FOUND)
        if (source.kind == MemorySourceKind.APP_EVENT || source.id in state.suppressedSourceIds || old.sources.any { it.id == source.id }) return MemoryChange(state, MemoryChangeReason.INVALID_SOURCE)
        val replacement = replacementText.trim()
        if (replacement.isBlank() || replacement.length > 500) return MemoryChange(state, MemoryChangeReason.INVALID_CANDIDATE)
        val revision = Math.incrementExact(state.revision)
        val suppressed = old.sources.map { it.id }.toSet()
        val updatedAt = maxOf(old.updatedAtMillis, nowMillis)
        val item = MemoryItem("memory:$revision", old.key, replacement, listOf(source), revision, old.createdAtMillis, updatedAt, supersedesId = old.id)
        val updated = state.items.map {
            if (it.status == MemoryStatus.ACTIVE && it.sources.any { evidence -> evidence.id in suppressed }) it.copy(status = MemoryStatus.SUPERSEDED, revision = revision, updatedAtMillis = maxOf(it.updatedAtMillis, updatedAt)) else it
        }
        return MemoryChange(state.copy(revision = revision, items = updated + item, suppressedSourceIds = state.suppressedSourceIds + suppressed, contextBoundaryMillis = nowMillis), MemoryChangeReason.APPLIED, item)
    }

    fun forget(state: MemoryState, itemId: String, nowMillis: Long): MemoryChange {
        require(nowMillis >= 0 && nowMillis >= (state.contextBoundaryMillis ?: 0))
        val item = state.items.find { it.id == itemId && it.status == MemoryStatus.ACTIVE } ?: return MemoryChange(state, MemoryChangeReason.NOT_FOUND)
        val revision = Math.incrementExact(state.revision)
        val suppressed = item.sources.map { it.id }.toSet()
        val updated = state.items.map {
            if (it.status == MemoryStatus.ACTIVE && it.sources.any { source -> source.id in suppressed }) it.copy(status = MemoryStatus.FORGOTTEN, revision = revision, updatedAtMillis = maxOf(it.updatedAtMillis, nowMillis)) else it
        }
        return MemoryChange(state.copy(revision = revision, items = updated, suppressedSourceIds = state.suppressedSourceIds + suppressed, contextBoundaryMillis = nowMillis), MemoryChangeReason.APPLIED)
    }

    fun retrieve(state: MemoryState, query: String, limit: Int = 6): List<MemoryItem> {
        require(limit in 0..12)
        val terms = searchTerms(query)
        return state.items.asSequence()
            .filter { it.status == MemoryStatus.ACTIVE && it.sources.none { source -> source.id in state.suppressedSourceIds } }
            .map { item ->
                val text = normalize(item.text)
                val score = terms.count { text.contains(it) } + if ((query.contains("名字") || query.contains("叫什么") || query.contains("我是谁")) && item.key == "user.name") 8 else 0
                item to score
            }
            .filter { terms.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenByDescending { it.first.updatedAtMillis }.thenBy { it.first.id })
            .take(limit).map { it.first }.toList()
    }

    private fun appendSourcedFact(state: MemoryState, key: String, text: String, source: MemorySource): MemoryChange {
        val active = state.items.filter { it.status == MemoryStatus.ACTIVE && it.key == key && it.sources.none { evidence -> evidence.id in state.suppressedSourceIds } }
        if (active.any { normalize(it.text) == normalize(text) }) return MemoryChange(state, MemoryChangeReason.ALREADY_KNOWN)
        if (active.isNotEmpty()) return MemoryChange(state, MemoryChangeReason.CONFLICT_REQUIRES_CONFIRMATION)
        val revision = Math.incrementExact(state.revision)
        val item = MemoryItem("memory:$revision", key, text, listOf(source), revision, source.occurredAtMillis, source.occurredAtMillis)
        return MemoryChange(state.copy(revision = revision, items = state.items + item), MemoryChangeReason.APPLIED, item)
    }

    private fun supportedEvidence(text: String, evidence: String): Boolean {
        if (!text.contains(evidence) || evidence != evidence.trim()) return false
        if (text.trim() == evidence) return true
        // Syntactic completeness prevents dropping a negation from inside a sentence. Whether
        // a whole sentence is hypothetical, quoted or worth remembering remains a model decision.
        val sentence = evidence.trimEnd('。', '！', '？', '!', '?', '；', ';')
        return sentence.isNotBlank() && text.splitToSequence(sentenceSeparators).any { it.trim() == sentence }
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in "，。！？,.!?:：；;\"“”" }

    private fun searchTerms(query: String): Set<String> {
        val words = Regex("[a-zA-Z0-9_]+|[\\p{IsHan}]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        return words.flatMap { word -> if (word.any { it.code > 127 } && word.length > 2) word.windowed(2) else listOf(word) }.filter { it.length >= 2 && it !in setOf("记得", "记住", "什么", "喜欢", "你还", "我的") }.toSet()
    }
}
