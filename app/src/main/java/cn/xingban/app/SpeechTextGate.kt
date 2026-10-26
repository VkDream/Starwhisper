package cn.xingban.app

/** Conservative per-turn speech gate. Display/archive keep the original model text. */
class SpeechTextGate {
    var allowed: Boolean = true
        private set
    fun accept(delta: String): Boolean {
        if (delta.any { it in "`{}[]<>【】（）()*_#" }) allowed = false
        return allowed
    }
}
