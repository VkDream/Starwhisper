package cn.xingban.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterReducerTest {
    @Test fun eventIsAppliedOnceAndTouchHasCooldown() {
        val event = CharacterEvent("touch-1", CharacterEventKind.TOUCH, 1_000)
        val once = CharacterReducer.reduce(CharacterState(updatedAtMillis = 1_000), event)
        assertEquals(once, CharacterReducer.reduce(once, event))
        val rapid = CharacterReducer.reduce(once, CharacterEvent("touch-2", CharacterEventKind.TOUCH, 1_500))
        val decayed = CharacterReducer.advance(once, 1_500)
        assertEquals(decayed.pleasure, rapid.pleasure, 0.0)
        assertTrue("touch-2" in rapid.appliedEventIds)
    }

    @Test fun timeDecaysTowardBaselineAndNegativeClockDoesNothing() {
        val state = CharacterState(pleasure = -0.5, activation = 0.9, irritation = 0.8, updatedAtMillis = 10_000)
        assertEquals(state, CharacterReducer.advance(state, 9_000))
        val later = CharacterReducer.advance(state, 10_000 + 4 * 60 * 60 * 1000)
        assertEquals(-0.075, later.pleasure, 0.000001)
        assertEquals(0.65, later.activation, 0.000001)
        assertEquals(0.4, later.irritation, 0.000001)
    }

    @Test fun stoppingKeepsPersistentMood() {
        val state = CharacterState(pleasure = -0.2, activation = 0.6, irritation = 0.7, updatedAtMillis = 1_000)
        val stopped = CharacterReducer.reduce(state, CharacterEvent("stop-1", CharacterEventKind.STOP, 1_000))
        assertEquals(state.pleasure, stopped.pleasure, 0.0)
        assertEquals(state.activation, stopped.activation, 0.0)
        assertEquals(state.irritation, stopped.irritation, 0.0)
        assertEquals(state.lastInteractionAtMillis, stopped.lastInteractionAtMillis)
    }
}
