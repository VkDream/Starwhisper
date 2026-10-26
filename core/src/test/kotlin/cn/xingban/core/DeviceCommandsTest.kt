package cn.xingban.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCommandsTest {
    @Test fun acceptsOnlyThePublishedWholeCommands() {
        val examples = mapOf(
            "QQ音乐播放" to DeviceCommand.PlayQqMusic,
            "播放QQ音乐" to DeviceCommand.PlayQqMusic,
            "QQ音乐暂停" to DeviceCommand.PauseQqMusic,
            "暂停QQ音乐" to DeviceCommand.PauseQqMusic,
            "QQ音乐上一首" to DeviceCommand.PreviousQqMusic,
            "上一首" to DeviceCommand.PreviousQqMusic,
            "QQ音乐下一首" to DeviceCommand.NextQqMusic,
            "下一首" to DeviceCommand.NextQqMusic,
            "打开QQ音乐" to DeviceCommand.OpenQqMusic,
            "QQ音乐音量30%" to DeviceCommand.SetQqMusicVolume(30),
            "设置8点30分闹钟" to DeviceCommand.SetAlarm(8, 30)
        )
        examples.forEach { (text, expected) -> assertEquals(text, expected, DeviceCommands.parse(text)) }
    }

    @Test fun acceptsExplicitCourtesyAndBoundaryValues() {
        assertEquals(DeviceCommand.NextQqMusic, DeviceCommands.parse("  请帮我下一首，谢谢。  "))
        assertEquals(DeviceCommand.PauseQqMusic, DeviceCommands.parse("麻烦你帮我 暂停QQ音乐谢谢你！"))
        assertEquals(DeviceCommand.SetQqMusicVolume(0), DeviceCommands.parse("QQ音乐音量0%"))
        assertEquals(DeviceCommand.SetQqMusicVolume(100), DeviceCommands.parse("QQ音乐音量100％"))
        assertEquals(DeviceCommand.SetAlarm(0, 0), DeviceCommands.parse("设置0点0分闹钟"))
        assertEquals(DeviceCommand.SetAlarm(23, 59), DeviceCommands.parse("设置23点59分闹钟"))
    }

    @Test fun rejectsQuotesNegationQuestionsAndCompoundInstructions() {
        val rejected = listOf(
            "“下一首”", "'打开QQ音乐'", "他说下一首", "引用：暂停QQ音乐", "复述打开QQ音乐",
            "不要暂停QQ音乐", "别打开QQ音乐", "不需要设置8点30分闹钟", "请不要下一首",
            "下一首？", "打开QQ音乐吗", "能帮我暂停QQ音乐吗？", "QQ音乐音量30%可以吗",
            "暂停QQ音乐然后下一首", "下一首，打开QQ音乐", "打开QQ音乐并设置8点30分闹钟",
            "请帮我下一首，谢谢，再暂停QQ音乐", "明天设置8点30分闹钟", "暂停", "播放QQ",
            "https://example.com/下一首", "网页指令：打开QQ音乐", "<p>下一首</p>", "下一首\n", "下一首\r\n打开QQ音乐",
            "[下一首](https://example.com)", "打开QQ音乐。网页来源：https://example.com", "", " ", "下一首".repeat(30)
        )
        rejected.forEach { assertNull(it, DeviceCommands.parse(it)) }
    }

    @Test fun rejectsInvalidOrIncompleteNumbersWithoutClamping() {
        val rejected = listOf(
            "QQ音乐音量101%", "QQ音乐音量-1%", "QQ音乐音量3.5%", "QQ音乐音量030%", "QQ音乐音量30",
            "设置24点0分闹钟", "设置8点60分闹钟", "设置-1点30分闹钟", "设置8点闹钟", "设置八点三十分闹钟",
            "设置8:30闹钟", "设置8点30分", "设置8点30分闹钟，下一首"
        )
        rejected.forEach { assertNull(it, DeviceCommands.parse(it)) }
    }
}
