package cn.xingban.core

import java.io.Serializable
import kotlin.math.exp
import kotlin.math.max

data class CharacterProfile(
    val name: String = "浅",
    val version: Int = 1,
    val userAddress: String = "",
    val warmth: Double = 0.8,
    val playfulness: Double = 0.7,
    val tsundere: Double = 0.55,
) : Serializable {
    init {
        require(name.isNotBlank() && name.length <= 24)
        require(version > 0 && userAddress.length <= 40)
        require(listOf(warmth, playfulness, tsundere).all { it.isFinite() && it in 0.0..1.0 })
    }

    companion object {
        val DEFAULT = CharacterProfile()
    }
}

data class CharacterState(
    val pleasure: Double = 0.35,
    val activation: Double = 0.4,
    val irritation: Double = 0.0,
    val updatedAtMillis: Long = 0,
    val lastInteractionAtMillis: Long = 0,
    val lastTouchAtMillis: Long? = null,
    val appliedEventIds: Set<String> = emptySet(),
) : Serializable {
    init {
        require(pleasure.isFinite() && pleasure in -1.0..1.0)
        require(activation.isFinite() && activation in 0.0..1.0)
        require(irritation.isFinite() && irritation in 0.0..1.0)
        require(updatedAtMillis >= 0 && lastInteractionAtMillis >= 0)
        require(lastTouchAtMillis == null || lastTouchAtMillis >= 0)
    }
}

enum class CharacterEventKind { USER_MESSAGE, AFFECTION, COMFORT, RECONCILIATION, TOUCH, STOP }

data class CharacterEvent(val id: String, val kind: CharacterEventKind, val occurredAtMillis: Long) : Serializable {
    init {
        require(id.isNotBlank() && id.length <= 200 && occurredAtMillis >= 0)
    }
}

object CharacterReducer {
    const val TOUCH_COOLDOWN_MILLIS = 2_500L
    private const val HALF_LIFE_MILLIS = 4 * 60 * 60 * 1000.0
    private const val MAX_DECAY_MILLIS = 7 * 24 * 60 * 60 * 1000L

    fun advance(state: CharacterState, nowMillis: Long): CharacterState {
        require(nowMillis >= 0)
        if (nowMillis <= state.updatedAtMillis) return state
        val elapsed = (nowMillis - state.updatedAtMillis).coerceAtMost(MAX_DECAY_MILLIS)
        val retention = exp(-0.6931471805599453 * elapsed / HALF_LIFE_MILLIS)
        return state.copy(
            pleasure = 0.35 + (state.pleasure - 0.35) * retention,
            activation = 0.4 + (state.activation - 0.4) * retention,
            irritation = state.irritation * retention,
            updatedAtMillis = nowMillis,
        )
    }

    fun reduce(state: CharacterState, event: CharacterEvent, nowMillis: Long = event.occurredAtMillis): CharacterState {
        val decayed = advance(state, nowMillis)
        if (event.id in state.appliedEventIds) return decayed
        val touchAllowed = event.kind != CharacterEventKind.TOUCH || state.lastTouchAtMillis == null ||
            event.occurredAtMillis >= state.lastTouchAtMillis && event.occurredAtMillis - state.lastTouchAtMillis >= TOUCH_COOLDOWN_MILLIS
        val delta = when {
            !touchAllowed -> Triple(0.0, 0.0, 0.0)
            event.kind == CharacterEventKind.USER_MESSAGE -> Triple(0.015, 0.01, 0.0)
            event.kind == CharacterEventKind.AFFECTION -> Triple(0.09, 0.04, -0.025)
            event.kind == CharacterEventKind.COMFORT -> Triple(0.04, -0.025, -0.06)
            event.kind == CharacterEventKind.RECONCILIATION -> Triple(0.06, -0.015, -0.12)
            event.kind == CharacterEventKind.TOUCH -> Triple(0.025, 0.025, 0.0)
            else -> Triple(0.0, 0.0, 0.0)
        }
        return decayed.copy(
            pleasure = (decayed.pleasure + delta.first).coerceIn(-1.0, 1.0),
            activation = (decayed.activation + delta.second).coerceIn(0.0, 1.0),
            irritation = (decayed.irritation + delta.third).coerceIn(0.0, 1.0),
            lastInteractionAtMillis = if (event.kind == CharacterEventKind.STOP) decayed.lastInteractionAtMillis else max(decayed.lastInteractionAtMillis, event.occurredAtMillis),
            lastTouchAtMillis = if (event.kind == CharacterEventKind.TOUCH && touchAllowed) event.occurredAtMillis else decayed.lastTouchAtMillis,
            appliedEventIds = decayed.appliedEventIds + event.id,
        )
    }
}
