package cn.xingban.core

import org.junit.Assert.*
import org.junit.Test

class UsageLedgerTest {
    @Test fun goSameNamedModelNeverUsesOfficialRatesAndSearchIsSeparateDeduplicated() {
        val go = report().copy(provider = "opencode_go", reportedCost = 0.0, billingSource = "go")
        val totals = UsageTotals().add(go).add(go.copy(requestId = "other", reportedCost = null))
        assertEquals(0.0, totals.feeLow, 0.0); assertEquals(0.0, totals.feeHigh, 0.0)
        assertEquals(2, totals.goRequests); assertEquals(1, totals.goCostUnknown); assertEquals(setOf("go"), totals.goBillingSources)
        val ledger = UsageLedger().recordSearch("search", true, "2026-10-26").recordSearch("cancelled", false, "2026-10-26")
        assertEquals(1, ledger.searchSuccess); assertEquals(1, ledger.searchUncertain); assertEquals(0, ledger.chat.requests)
        assertEquals(ledger, ledger.recordSearch("search", true, "2026-10-26"))
        val nextDay = ledger.record(report(), "2026-10-27")
        assertEquals(0, nextDay.searchSuccess); assertEquals(0, nextDay.searchUncertain)
    }
    private fun report(id: String = "one", purpose: RequestPurpose = RequestPurpose.CHAT, model: String = "deepseek-flash") = RequestUsage(id, "round", purpose, model, 100, 1000, 100, 400, 600)
    @Test fun actualCacheSplitProducesBothPriceBandsAndDuplicateReportsCountOnce() {
        val once = UsageLedger().beginRound("round").record(report(), "2026-10-25")
        assertEquals(1000L, once.chat.prompt); assertEquals(100L, once.roundChat.completion)
        assertEquals(0.001008, once.chat.feeLow, 0.000000001)
        assertEquals(0.002016, once.chat.feeHigh, 0.000000001)
        assertEquals(once, once.record(report(), "2026-10-25"))
        val both = once.record(report("two", RequestPurpose.SUMMARY), "2026-10-25")
        assertEquals(1, both.summary.requests); assertEquals(2, (both.chat + both.summary).requests)
    }
    @Test fun missingUsageAndUnknownModelNeverClaimACompleteZeroBill() {
        val missing = report().copy(prompt = null, cacheHit = null, cacheMiss = null, completion = null)
        val totals = UsageTotals().add(missing).add(report(model = "unknown-model"))
        assertEquals(1, totals.promptUnknown); assertEquals(2, totals.unpriced)
        assertEquals(0.0, totals.feeLow, 0.0)
    }
    @Test fun lateCancelledRequestUpdatesTodayWithoutReplacingNewRoundAndDayIsBounded() {
        val base = UsageLedger().beginRound("round").record(report(), "2026-10-25").beginRound("new")
        val late = base.record(report("late", RequestPurpose.SUMMARY), "2026-10-25")
        assertEquals("new", late.roundId); assertEquals(0, late.roundSummary.requests); assertEquals(1, late.summary.requests)
        var next = late.record(report("next"), "2026-10-26")
        assertEquals(1, next.chat.requests); assertEquals(0, next.summary.requests)
        repeat(100) { next = next.record(report("bounded-$it"), "2026-10-26") }
        assertEquals(64, next.recentRequestIds.size)
    }
}
