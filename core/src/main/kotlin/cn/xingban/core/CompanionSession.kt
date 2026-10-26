package cn.xingban.core

import java.io.Serializable

data class TurnToken(val turnId: Long, val generation: Long, val memoryRevision: Long) : Serializable

/** Owns request identity only. The app cancels the actual network, ASR and audio resources. */
class CompanionSession {
    private var generation = 0L
    private var turnId = 0L
    private var memoryRevision = 0L
    private var current: TurnToken? = null

    @Synchronized
    fun beginTurn(memoryRevision: Long): TurnToken {
        require(memoryRevision >= this.memoryRevision) { "Memory revision cannot move backwards" }
        this.memoryRevision = memoryRevision
        generation = Math.incrementExact(generation)
        turnId = Math.incrementExact(turnId)
        return TurnToken(turnId, generation, memoryRevision).also { current = it }
    }

    @Synchronized
    fun isCurrent(token: TurnToken): Boolean = token == current && token.generation == generation

    @Synchronized
    fun stop(): Long {
        generation = Math.incrementExact(generation)
        current = null
        return generation
    }

    @Synchronized
    fun onMemoryRevisionChanged(revision: Long): Boolean {
        require(revision >= 0)
        if (revision <= memoryRevision) return false
        memoryRevision = revision
        val cancelled = current != null
        stop()
        return cancelled
    }
}
