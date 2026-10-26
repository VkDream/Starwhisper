package cn.xingban.core

import java.io.Serializable

enum class TouchArea { HEAD, CHEEK, OUTFIT }
enum class Expression { NEUTRAL, HAPPY, SHY, CONCERNED, PLAYFUL }
enum class BehaviorKind { NONE, STOP, CHAT, COMFORT, BANTER, TOUCH, REUNION }

enum class ActivityType { WATCH, WORK, READ, MUSIC, REST, CHAT }
enum class ActivityAction { NONE, START, CONTINUE, PAUSE, FINISH, SWITCH }
enum class ActivityPhase { IDLE, ACTIVE, PAUSED, AWAITING_CONTINUE }
enum class ActivityMode { AUTONOMOUS, EXPLICIT }
data class ActivityCommand(val action: ActivityAction, val type: ActivityType? = null) : Serializable

/** One semantic owner; the image is a projection, never a second activity state. */
data class CompanionActivity(
    val type: ActivityType? = null, val phase: ActivityPhase = ActivityPhase.IDLE,
    val mode: ActivityMode = ActivityMode.AUTONOMOUS, val revision: Long = 0,
    val autonomousScene: Int = 0, val silent: Boolean = false,
) : Serializable {
    init {
        require(revision in 0 until Long.MAX_VALUE && autonomousScene in 0..5)
        require(if (mode == ActivityMode.AUTONOMOUS) type == null && phase == ActivityPhase.IDLE else type != null && phase != ActivityPhase.IDLE)
    }
    val quiet: Boolean get() = silent || (mode == ActivityMode.EXPLICIT && phase == ActivityPhase.ACTIVE && type != ActivityType.CHAT)
    fun apply(command: ActivityCommand): CompanionActivity {
        val next = when (command.action) {
            ActivityAction.NONE -> this
            ActivityAction.START -> if (mode == ActivityMode.AUTONOMOUS && command.type != null) copy(type = command.type, phase = ActivityPhase.ACTIVE, mode = ActivityMode.EXPLICIT, silent = false) else this
            ActivityAction.SWITCH -> if (command.type != null) copy(type = command.type, phase = ActivityPhase.ACTIVE, mode = ActivityMode.EXPLICIT, silent = false) else this
            ActivityAction.CONTINUE -> if (mode == ActivityMode.EXPLICIT && (command.type == null || command.type == type)) copy(phase = ActivityPhase.ACTIVE, silent = false) else this
            ActivityAction.PAUSE -> if (mode == ActivityMode.EXPLICIT && (command.type == null || command.type == type)) copy(phase = ActivityPhase.PAUSED) else this
            ActivityAction.FINISH -> if (command.type == null || command.type == type) CompanionActivity(revision = revision, autonomousScene = autonomousScene) else this
        }
        return if (next == this) this else next.copy(revision = Math.incrementExact(revision))
    }
    fun localStop(silence: Boolean): CompanionActivity {
        val next = copy(phase = if (mode == ActivityMode.EXPLICIT) ActivityPhase.PAUSED else phase, silent = silent || silence)
        return if (next == this) this else next.copy(revision = Math.incrementExact(revision))
    }
    fun returnedAfter(awayMillis: Long?): CompanionActivity = if (awayMillis != null && awayMillis >= 86_400_000L && mode == ActivityMode.EXPLICIT && phase != ActivityPhase.AWAITING_CONTINUE) copy(phase = ActivityPhase.AWAITING_CONTINUE, revision = Math.incrementExact(revision)) else this
    fun rotate(): CompanionActivity = if (mode == ActivityMode.AUTONOMOUS && !silent) copy(autonomousScene = (autonomousScene + 1) % 6, revision = Math.incrementExact(revision)) else this
}

object CompanionReunion {
    fun elapsed(departedAtMillis: Long?, returnedAtMillis: Long): Long? = departedAtMillis?.takeIf { it >= 0 && returnedAtMillis >= it }?.let { returnedAtMillis - it }
    fun reply(awayMillis: Long?, activity: CompanionActivity): String? {
        if (awayMillis == null || awayMillis < 21_600_000L) return null
        val greeting = when {
            awayMillis < 86_400_000L -> "忙完啦？我才没有一直等你呢。过来陪我说两句。"
            awayMillis < 259_200_000L -> "终于来啦……这么久没见，有一点想你。今天陪我待会儿吧。"
            else -> "哼，还知道来找我呀。……好啦，见到你还是很开心，过来陪我一会儿。"
        }
        return greeting + if (activity.phase == ActivityPhase.AWAITING_CONTINUE) "上次的${when (activity.type) { ActivityType.WATCH -> "陪看"; ActivityType.WORK -> "一起工作"; ActivityType.READ -> "一起阅读"; ActivityType.MUSIC -> "听歌"; ActivityType.REST -> "休息"; else -> "聊天" }}先放着，要继续吗？" else ""
    }
}

data class BehaviorInput(
    val userText: String = "",
    val stopRequested: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val touch: TouchArea? = null,
    val reunionDue: Boolean = false,
    val hasPendingUserInput: Boolean = false,
    val triggerAlreadyHandled: Boolean = false,
)

data class BehaviorDecision(
    val kind: BehaviorKind,
    val expression: Expression,
    val maySpeak: Boolean,
    val guidance: String,
    val localReply: String? = null,
) : Serializable

object BehaviorSelector {
    private val stopPhrases = setOf("停", "停止", "停止一下", "停一下", "别说了", "安静", "暂停", "先别说话", "不要说了")

    fun select(input: BehaviorInput): BehaviorDecision {
        val text = input.userText.trim().trimEnd('。', '！', '!', '，', ',', '.', ' ')
        if (input.stopRequested || text in stopPhrases) return BehaviorDecision(BehaviorKind.STOP, Expression.NEUTRAL, false, "用户要求停止。立即服从，不再播报。")
        if (text.isNotBlank()) {
            return BehaviorDecision(BehaviorKind.CHAT, Expression.NEUTRAL, !input.isListening, "按整句和上下文自然回应，接受用户更正；不按单个词推定情绪。")
        }
        if (input.touch != null) {
            val expression = if (input.touch == TouchArea.CHEEK) Expression.SHY else Expression.HAPPY
            val available = !input.isListening && !input.isSpeaking && !input.hasPendingUserInput
            return BehaviorDecision(BehaviorKind.TOUCH, expression, available, "仅本地轻微触摸反馈，不另开模型回合。", if (available) "嗯？怎么啦。" else null)
        }
        if (input.isListening || input.isSpeaking || input.hasPendingUserInput) return idle()
        if (input.reunionDue && !input.triggerAlreadyHandled) return BehaviorDecision(BehaviorKind.REUNION, Expression.HAPPY, true, "自然打招呼，最多提一件有来源近况，不编共同经历，不追责离线。", "来啦。今天想先跟我聊什么？")
        return idle()
    }

    private fun idle() = BehaviorDecision(BehaviorKind.NONE, Expression.NEUTRAL, false, "保持安静，等待用户。")
}

data class ExpressionCue(val expression: Expression, val intensity: Double) : Serializable

object ModelOutputRules {
    /** A visual suggestion is data only; unknown names and non-finite values are rejected. */
    fun expression(name: String, intensity: Double): ExpressionCue? {
        if (!intensity.isFinite() || intensity !in 0.0..1.0) return null
        val expression = Expression.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ExpressionCue(expression, intensity)
    }
}
