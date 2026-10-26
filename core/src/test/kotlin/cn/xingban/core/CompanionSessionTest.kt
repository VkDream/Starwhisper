package cn.xingban.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSessionTest {
    @Test fun newTurnAndStopRejectLateResults() {
        val session = CompanionSession()
        val first = session.beginTurn(0)
        val second = session.beginTurn(0)
        assertFalse(session.isCurrent(first))
        assertTrue(session.isCurrent(second))
        session.stop()
        assertFalse(session.isCurrent(second))
        val third = session.beginTurn(0)
        assertNotEquals(second.generation, third.generation)
        assertTrue(session.isCurrent(third))
    }

    @Test fun memoryRevisionInvalidatesAlreadyAssembledContext() {
        val session = CompanionSession()
        val token = session.beginTurn(4)
        assertFalse(session.onMemoryRevisionChanged(4))
        assertTrue(session.isCurrent(token))
        assertTrue(session.onMemoryRevisionChanged(5))
        assertFalse(session.isCurrent(token))
        assertTrue(session.isCurrent(session.beginTurn(5)))
        assertFalse(session.onMemoryRevisionChanged(3))
    }
}
