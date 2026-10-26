package cn.xingban.core

enum class DeviceVolumeScope { LOCAL_SHARED, REMOTE_OUTPUT }

/** Values parsed only from the current user's whole message; parsing never performs an action. */
sealed interface DeviceCommand {
    data object PlayQqMusic : DeviceCommand
    data object PauseQqMusic : DeviceCommand
    data object PreviousQqMusic : DeviceCommand
    data object NextQqMusic : DeviceCommand
    data object OpenQqMusic : DeviceCommand
    data class SetQqMusicVolume(val percent: Int, val expectedScope: DeviceVolumeScope? = null) : DeviceCommand {
        init { require(percent in 0..100) }
    }
    data class SetAlarm(val hour: Int, val minute: Int) : DeviceCommand {
        init { require(hour in 0..23 && minute in 0..59) }
    }
}

object DeviceCommands {
    private val envelope = Regex("^(?:(?:麻烦你帮我|麻烦帮我|请帮我|麻烦你|帮我|请) *)?(.+?)(?:[，,]?(?:谢谢你|谢谢))?[。！!]?")
    private val volume = Regex("QQ音乐音量(0|[1-9][0-9]?|100)[%％]")
    private val alarm = Regex("设置([0-9]{1,2})点([0-9]{1,2})分闹钟")

    fun parse(text: String): DeviceCommand? {
        if (text.length > 80 || text.any { it == '\n' || it == '\r' }) return null
        val command = envelope.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        return when (command) {
            "QQ音乐播放", "播放QQ音乐" -> DeviceCommand.PlayQqMusic
            "QQ音乐暂停", "暂停QQ音乐" -> DeviceCommand.PauseQqMusic
            "QQ音乐上一首", "上一首" -> DeviceCommand.PreviousQqMusic
            "QQ音乐下一首", "下一首" -> DeviceCommand.NextQqMusic
            "打开QQ音乐" -> DeviceCommand.OpenQqMusic
            else -> {
                volume.matchEntire(command)?.let { return DeviceCommand.SetQqMusicVolume(it.groupValues[1].toInt()) }
                val time = alarm.matchEntire(command) ?: return null
                val hour = time.groupValues[1].toInt()
                val minute = time.groupValues[2].toInt()
                if (hour in 0..23 && minute in 0..59) DeviceCommand.SetAlarm(hour, minute) else null
            }
        }
    }
}
