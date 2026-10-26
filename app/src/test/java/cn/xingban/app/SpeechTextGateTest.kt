package cn.xingban.app

import org.junit.Assert.*
import org.junit.Test

class SpeechTextGateTest {
    @Test fun ordinaryChineseRemainsSpeakable() { val gate = SpeechTextGate(); assertTrue(gate.accept("累了就歇一会儿。我听着呢！")) }
    @Test fun firstMarkerAcrossDeltasDisablesRestOfTurn() {
        listOf("`", "{", "[", "<", "【", "（", "*", "#").forEach { marker ->
            val gate = SpeechTextGate(); assertTrue(gate.accept("你好")); assertFalse(gate.accept(marker)); assertFalse(gate.accept("剩下的尾段。"))
        }
    }
    @Test fun hundredCharacterBoundaryDoesNotResetGate() {
        val gate = SpeechTextGate(); assertTrue(gate.accept("好".repeat(100))); assertFalse(gate.accept("{")); assertFalse(gate.accept("工具参数")); assertFalse(gate.allowed)
        assertTrue(SpeechTextGate().accept("下一回合正常中文。"))
    }
}
