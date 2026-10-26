package cn.xingban.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRulesTest {
    private fun source(id: String, text: String, at: Long = 1_000) = MemorySource(id, MemorySourceKind.USER_MESSAGE, at, text)

    @Test fun onlyBoundedExplicitUserStatementsBecomeFacts() {
        assertEquals(1, MemoryRules.extract(source("m1", "我喜欢咖啡"), 0).size)
        for (text in listOf("如果我喜欢咖啡呢", "他说我喜欢咖啡", "我不喜欢咖啡", "扮演我叫小林", "我叫小林？")) {
            assertTrue(text, MemoryRules.extract(source("ambiguous", text), 0).isEmpty())
        }
        val fabricated = MemoryCandidate("user.name", "我叫假的名字", source("m2", "今天怎么样"), 0)
        assertEquals(MemoryChangeReason.INVALID_CANDIDATE, MemoryRules.commit(MemoryState(), fabricated).reason)
        assertTrue(MemoryRules.extract(MemorySource("event", MemorySourceKind.APP_EVENT, 1_000, "我叫某人"), 0).isEmpty())
    }

    @Test fun correctionSupersedesOldFactAndRejectsLateExtraction() {
        val original = MemoryRules.extract(source("name-old", "我叫小林"), 0).single()
        val saved = MemoryRules.commit(MemoryState(), original)
        val correctionSource = MemorySource("name-edit", MemorySourceKind.USER_CONFIRMATION, 2_000, "我的名字更正为小陈")
        val corrected = MemoryRules.correct(saved.state, saved.item!!.id, "我叫小陈", correctionSource, 2_000)
        assertTrue(corrected.changed)
        assertEquals(MemoryStatus.SUPERSEDED, corrected.state.items.first().status)
        assertEquals("我叫小陈", MemoryRules.retrieve(corrected.state, "我的名字").single().text)
        assertEquals(MemoryChangeReason.STALE_REVISION, MemoryRules.commit(corrected.state, original).reason)
        assertEquals(MemoryChangeReason.SOURCE_SUPPRESSED, MemoryRules.commit(corrected.state, original.copy(basedOnRevision = corrected.state.revision)).reason)
    }

    @Test fun forgettingSuppressesOldSourceButAllowsNewExplicitStatement() {
        val original = MemoryRules.extract(source("coffee-old", "我喜欢咖啡"), 0).single()
        val saved = MemoryRules.commit(MemoryState(), original)
        val forgotten = MemoryRules.forget(saved.state, saved.item!!.id, 2_000)
        assertTrue(forgotten.changed)
        assertTrue(MemoryRules.retrieve(forgotten.state, "咖啡").isEmpty())
        assertEquals(MemoryChangeReason.SOURCE_SUPPRESSED, MemoryRules.commit(forgotten.state, original.copy(basedOnRevision = forgotten.state.revision)).reason)
        val newCandidate = MemoryRules.extract(source("coffee-new", "记住：我喜欢咖啡", 3_000), forgotten.state.revision).single()
        assertTrue(MemoryRules.commit(forgotten.state, newCandidate).changed)
    }

    @Test fun conflictsAndModelProposalsNeedConfirmation() {
        val saved = MemoryRules.commit(MemoryState(), MemoryRules.extract(source("m1", "我叫小林"), 0).single())
        val conflicting = MemoryRules.extract(source("m2", "我叫小陈"), saved.state.revision).single()
        assertEquals(MemoryChangeReason.CONFLICT_REQUIRES_CONFIRMATION, MemoryRules.commit(saved.state, conflicting).reason)
        assertEquals(MemoryChangeReason.REQUIRES_CONFIRMATION, MemoryRules.commit(saved.state, conflicting.copy(origin = MemoryCandidateOrigin.MODEL)).reason)
        assertFalse(MemoryRules.commit(saved.state, conflicting).changed)
        assertTrue(MemoryRules.retrieve(saved.state, "完全无关的天气").isEmpty())
    }

    @Test fun reloadedBoundaryRejectsOldParaphraseButKeepsUnrelatedActiveMemory() {
        val coffee = MemoryRules.commit(MemoryState(), MemoryRules.extract(source("coffee", "我喜欢咖啡"), 0).single())
        val name = MemoryRules.commit(coffee.state, MemoryRules.extract(source("name", "我叫小林", 1_100), coffee.state.revision).single())
        val forgotten = MemoryRules.forget(name.state, coffee.item!!.id, 2_000).state
        val reloaded = MemoryState(forgotten.revision, forgotten.items.toList(), forgotten.suppressedSourceIds.toSet(), forgotten.contextBoundaryMillis)
        val oldParaphrase = MemoryRules.extract(source("different-id", "记住：我喜欢咖啡", 1_500), reloaded.revision).single()
        assertEquals(MemoryChangeReason.SOURCE_BEFORE_CONTEXT_BOUNDARY, MemoryRules.commit(reloaded, oldParaphrase).reason)
        assertEquals("我叫小林", MemoryRules.retrieve(reloaded, "名字").single().text)
        assertEquals(2_000L, reloaded.contextBoundaryMillis)
    }

    @Test fun semanticBatchAcceptsExactNaturalUserEvidenceWithoutFixedPrefixes() {
        val evidence = "咖啡我是不喝的，喝了晚上睡不着。"
        val original = source("natural", "今天还有一些工作上的事情想跟你聊聊。".repeat(12) + evidence + "平时出门经常坐地铁。")
        assertTrue(original.text.length > 160)
        assertTrue(MemoryRules.extract(original, 0).isEmpty())
        val batch = MemoryRules.commitSemanticBatch(MemoryState(), original, 0, listOf(SemanticMemoryCandidate("user.preference.coffee", evidence)))
        assertTrue(batch.changed)
        assertEquals(null, batch.rejection)
        assertEquals(evidence, batch.state.items.single().text)
        assertEquals(original, batch.state.items.single().sources.single())
        assertEquals(1L, batch.state.revision)
    }

    @Test fun semanticBatchRejectsNonUserSourcesAndUnsupportedOrTruncatedEvidence() {
        val original = source("negative", "咖啡我不喜欢，喝了晚上睡不着。")
        val valid = SemanticMemoryCandidate("user.preference.coffee", original.text)
        for (kind in listOf(MemorySourceKind.APP_EVENT, MemorySourceKind.USER_CONFIRMATION)) {
            val batch = MemoryRules.commitSemanticBatch(MemoryState(), original.copy(kind = kind), 0, listOf(valid))
            assertEquals(MemoryChangeReason.INVALID_SOURCE, batch.rejection)
            assertFalse(batch.changed)
            assertTrue(batch.outcomes.isEmpty())
        }
        val batch = MemoryRules.commitSemanticBatch(MemoryState(), original, 0, listOf(
            SemanticMemoryCandidate(valid.key, "咖啡我喜欢，喝了晚上睡不着。"),
            SemanticMemoryCandidate(valid.key, "喜欢"),
            SemanticMemoryCandidate(valid.key, "咖啡我不喜欢"),
            SemanticMemoryCandidate(valid.key, "助手说你爱喝咖啡"),
            SemanticMemoryCandidate(valid.key, original.text.removeSuffix("。")),
        ))
        assertEquals(List(4) { MemoryChangeReason.INVALID_CANDIDATE } + MemoryChangeReason.APPLIED, batch.outcomes.map { it.reason })
        assertEquals("咖啡我不喜欢，喝了晚上睡不着", batch.state.items.single().text)
    }

    @Test fun semanticBatchChecksItsCapturedRevisionBeforeAnySubmission() {
        val state = MemoryRules.commit(MemoryState(), MemoryRules.extract(source("name", "我叫小林"), 0).single()).state
        val original = source("commute", "工作日上下班一直坐地铁。", 2_000)
        val batch = MemoryRules.commitSemanticBatch(state, original, 0, listOf(SemanticMemoryCandidate("user.commute", original.text)))
        assertEquals(MemoryChangeReason.STALE_REVISION, batch.rejection)
        assertEquals(state, batch.state)
        assertTrue(batch.outcomes.isEmpty())
    }

    @Test fun semanticBatchHonorsForgottenSourcesAndReloadedContextBoundary() {
        val original = source("old-semantic", "工作日上下班一直坐地铁。")
        val candidates = listOf(SemanticMemoryCandidate("user.commute", original.text))
        val saved = MemoryRules.commitSemanticBatch(MemoryState(), original, 0, candidates)
        val forgotten = MemoryRules.forget(saved.state, saved.state.items.single().id, 2_000).state
        val reloaded = MemoryState(forgotten.revision, forgotten.items.toList(), forgotten.suppressedSourceIds.toSet(), forgotten.contextBoundaryMillis)
        val suppressed = MemoryRules.commitSemanticBatch(reloaded, original, reloaded.revision, candidates)
        assertEquals(MemoryChangeReason.SOURCE_SUPPRESSED, suppressed.rejection)
        val oldDifferentId = MemoryRules.commitSemanticBatch(reloaded, original.copy(id = "old-repeat", occurredAtMillis = 1_500), reloaded.revision, candidates)
        assertEquals(MemoryChangeReason.SOURCE_BEFORE_CONTEXT_BOUNDARY, oldDifferentId.rejection)
        val atBoundary = MemoryRules.commitSemanticBatch(reloaded, original.copy(id = "boundary", occurredAtMillis = 2_000), reloaded.revision, candidates)
        assertEquals(MemoryChangeReason.SOURCE_BEFORE_CONTEXT_BOUNDARY, atBoundary.rejection)
        assertFalse(suppressed.changed || oldDifferentId.changed || atBoundary.changed)
        val fresh = MemoryRules.commitSemanticBatch(reloaded, original.copy(id = "fresh", occurredAtMillis = 2_001), reloaded.revision, candidates)
        assertTrue(fresh.changed)
    }

    @Test fun semanticBatchSharesOneBaselineWhileSkippingDuplicatesAndPreservingConflicts() {
        val original = source("several-facts", "家里养着一只猫。上下班经常坐地铁。")
        val candidates = listOf(
            SemanticMemoryCandidate("user.pet", "家里养着一只猫。"),
            SemanticMemoryCandidate("user.commute", "上下班经常坐地铁。"),
            SemanticMemoryCandidate("user.pet", "家里养着一只猫。"),
            SemanticMemoryCandidate("user.pet", "上下班经常坐地铁。"),
        )
        val batch = MemoryRules.commitSemanticBatch(MemoryState(), original, 0, candidates)
        assertEquals(listOf(MemoryChangeReason.APPLIED, MemoryChangeReason.APPLIED, MemoryChangeReason.ALREADY_KNOWN, MemoryChangeReason.CONFLICT_REQUIRES_CONFIRMATION), batch.outcomes.map { it.reason })
        assertEquals(listOf(0, 1, 2, 3), batch.outcomes.map { it.index })
        assertEquals(2L, batch.state.revision)
        assertEquals(2, batch.state.items.size)
        assertEquals("家里养着一只猫。", batch.state.items.single { it.key == "user.pet" }.text)
        assertEquals(MemoryChangeReason.STALE_REVISION, MemoryRules.commitSemanticBatch(batch.state, original, 0, candidates).rejection)
    }

    @Test fun semanticBatchCapacityRejectsWithoutSilentlyShorteningComplexSentences() {
        val state = MemoryState()
        val original = source("long", "工作安排和生活习惯，".repeat(60) + "整体需要保留才有意义。")
        assertTrue(original.text.length > 500)
        val longEvidence = MemoryRules.commitSemanticBatch(state, original, 0, listOf(SemanticMemoryCandidate("user.routine", original.text)))
        assertEquals(MemoryChangeReason.INVALID_CANDIDATE, longEvidence.outcomes.single().reason)
        assertEquals(state, longEvidence.state)
        val shortSource = source("short", "平时出门经常坐地铁。")
        val candidate = SemanticMemoryCandidate("user.commute", shortSource.text)
        val tooMany = MemoryRules.commitSemanticBatch(state, shortSource, 0, List(MemoryRules.MAX_SEMANTIC_CANDIDATES + 1) { candidate })
        assertEquals(MemoryChangeReason.INVALID_CANDIDATE, tooMany.rejection)
        assertTrue(tooMany.outcomes.isEmpty())
        assertEquals(state, tooMany.state)
        val longKey = MemoryRules.commitSemanticBatch(state, shortSource, 0, listOf(candidate.copy(key = "k".repeat(181))))
        assertEquals(MemoryChangeReason.INVALID_CANDIDATE, longKey.outcomes.single().reason)
        assertFalse(longKey.changed)
    }
}
