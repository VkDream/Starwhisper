package cn.xingban.app.integrations

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import cn.xingban.core.DeviceCommand
import cn.xingban.core.DeviceVolumeScope
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class QqPlayback { UNKNOWN, PLAYING, PAUSED, STOPPED, BUFFERING, ERROR }

data class QqQueueItem(val id: Long, val title: String, val artist: String?)

data class QqMusicState(
    val installed: Boolean = false,
    val accessGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val sessionAvailable: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val playback: QqPlayback = QqPlayback.UNKNOWN,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canSetVolume: Boolean = false,
    val volumePercent: Int? = null,
    val volumeScope: DeviceVolumeScope? = null,
    val queue: List<QqQueueItem>? = null,
    val error: String? = null
)

data class PhoneCommandResult(val success: Boolean, val message: String, val handedOff: Boolean = false)

/** One Android implementation. Open members let the isolated Activity tests replace side effects. */
open class PhoneRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val component = ComponentName(appContext, QqMusicNotificationListenerService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(QqMusicState())
    open val state: StateFlow<QqMusicState> = mutableState.asStateFlow()
    private var foreground = false
    private var closed = false
    private var observing = false
    private var subscribed = false
    private var connectionJob: Job? = null
    private var controller: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions() }
    private val accessChanged = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refreshSessions() }
    }

    open fun setForeground(value: Boolean) {
        checkMainThread()
        if (closed || foreground == value) return
        foreground = value
        if (!value) {
            connectionJob?.cancel()
            connectionJob = null
            if (observing) appContext.contentResolver.unregisterContentObserver(accessChanged)
            observing = false
            stopSessions()
            return
        }
        appContext.contentResolver.registerContentObserver(Settings.Secure.getUriFor(ACCESS_SETTING), false, accessChanged)
        observing = true
        connectionJob = scope.launch { QqMusicNotificationListenerService.connected.collect { refreshSessions() } }
        refreshSessions()
    }

    /** Caller owns the current foreground turn and persists a handoff reply before calling this. */
    open fun execute(command: DeviceCommand): PhoneCommandResult {
        checkMainThread()
        if (closed || !foreground) return PhoneCommandResult(false, "星伴当前不在前台，未执行手机操作。")
        if (command == DeviceCommand.OpenQqMusic) return launchQqMusic()
        if (command is DeviceCommand.SetAlarm) return launchAlarm(command)
        refreshSessions()
        val current = controller ?: return PhoneCommandResult(false, unavailableReason())
        return try {
            val actions = current.playbackState?.actions ?: 0L
            when (command) {
                DeviceCommand.PlayQqMusic -> control(current, actions, PlaybackState.ACTION_PLAY, "播放") { play() }
                DeviceCommand.PauseQqMusic -> control(current, actions, PlaybackState.ACTION_PAUSE, "暂停") { pause() }
                DeviceCommand.PreviousQqMusic -> control(current, actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS, "上一首") { skipToPrevious() }
                DeviceCommand.NextQqMusic -> control(current, actions, PlaybackState.ACTION_SKIP_TO_NEXT, "下一首") { skipToNext() }
                is DeviceCommand.SetQqMusicVolume -> {
                    val info = current.playbackInfo
                    val actualScope = info?.let { controllableVolumeScope(it.playbackType, it.volumeControl, it.maxVolume) }
                    if (actualScope == null) {
                        refreshSessions()
                        PhoneCommandResult(false, "当前播放会话未提供可设置且范围明确的绝对音量，请刷新后确认。")
                    } else if (!matchesVolumeScope(command.expectedScope, actualScope)) {
                        refreshSessions()
                        PhoneCommandResult(false, "播放音量的作用范围未确认或已变化，未提交设置请求，请确认当前音量范围后重新操作。")
                    } else {
                        val target = ((info!!.maxVolume.toLong() * command.percent + 50) / 100).toInt()
                        current.setVolumeTo(target, 0)
                        val label = when (actualScope) {
                            DeviceVolumeScope.LOCAL_SHARED -> "手机播放音量（系统共享）"
                            DeviceVolumeScope.REMOTE_OUTPUT -> "播放设备音量"
                        }
                        PhoneCommandResult(true, "已提交${label}${command.percent}%的设置请求。")
                    }
                }
                else -> PhoneCommandResult(false, "不支持此手机操作。")
            }
        } catch (_: SecurityException) {
            stopSessions()
            PhoneCommandResult(false, "QQ音乐会话访问已失效，请检查通知访问权限。")
        } catch (_: RuntimeException) {
            PhoneCommandResult(false, "QQ音乐未接受该控制请求。")
        }
    }

    open fun openNotificationAccessSettings(): PhoneCommandResult {
        checkMainThread()
        if (closed || !foreground) return PhoneCommandResult(false, "请在星伴前台打开通知访问设置。")
        return launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "已打开系统通知访问设置，请由你选择是否授予星伴权限。")
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        setForeground(false)
        closed = true
        scope.cancel()
    }

    private fun refreshSessions() {
        if (!foreground || closed) return
        val installed = try { appContext.packageManager.getApplicationInfo(QQ_PACKAGE, 0).enabled } catch (_: PackageManager.NameNotFoundException) { false }
        val granted = hasAccess()
        val connected = QqMusicNotificationListenerService.connected.value
        mutableState.value = mutableState.value.copy(installed = installed, accessGranted = granted, listenerConnected = connected, error = null)
        if (!installed || !granted || !connected) {
            stopSessions()
            return
        }
        try {
            if (!subscribed) {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
                subscribed = true
            }
            // Read only package identity until the fixed QQ Music package has been selected.
            val selected = manager.getActiveSessions(component).firstOrNull { it.packageName == QQ_PACKAGE }
            if (selected?.sessionToken != controller?.sessionToken) bindController(selected)
            publishController()
        } catch (_: SecurityException) {
            stopSessions()
            mutableState.value = mutableState.value.copy(error = "QQ音乐会话访问已失效，请检查通知访问权限。")
        } catch (_: RuntimeException) {
            stopSessions()
            mutableState.value = mutableState.value.copy(error = "暂时无法读取QQ音乐会话。")
        }
    }

    private fun bindController(selected: MediaController?) {
        clearController()
        if (selected == null) return
        controller = selected
        val callback = object : MediaController.Callback() {
            private fun changed() {
                if (foreground && controller === selected) refreshSessions()
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) = changed()
            override fun onPlaybackStateChanged(state: PlaybackState?) = changed()
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = changed()
            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = changed()
            override fun onSessionDestroyed() {
                if (controller === selected) clearController()
            }
        }
        controllerCallback = callback
        selected.registerCallback(callback, handler)
    }

    private fun publishController() {
        val current = controller ?: return
        val metadata = current.metadata
        val playback = current.playbackState
        val actions = playback?.actions ?: 0L
        val info = current.playbackInfo
        val volumeScope = info?.let { controllableVolumeScope(it.playbackType, it.volumeControl, it.maxVolume) }
        mutableState.value = mutableState.value.copy(
            sessionAvailable = true,
            title = bounded(metadata?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)) ?: bounded(metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)),
            artist = bounded(metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)),
            playback = when (playback?.state) {
                PlaybackState.STATE_PLAYING -> QqPlayback.PLAYING
                PlaybackState.STATE_PAUSED -> QqPlayback.PAUSED
                PlaybackState.STATE_STOPPED -> QqPlayback.STOPPED
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> QqPlayback.BUFFERING
                PlaybackState.STATE_ERROR -> QqPlayback.ERROR
                else -> QqPlayback.UNKNOWN
            },
            canPlay = actions and PlaybackState.ACTION_PLAY != 0L,
            canPause = actions and PlaybackState.ACTION_PAUSE != 0L,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSetVolume = volumeScope != null,
            volumePercent = if (volumeScope != null) ((info!!.currentVolume.toLong() * 100) / info.maxVolume).toInt().coerceIn(0, 100) else null,
            volumeScope = volumeScope,
            queue = current.queue?.take(MAX_QUEUE_ITEMS)?.map { QqQueueItem(it.queueId, bounded(it.description.title).orEmpty(), bounded(it.description.subtitle)) }
        )
    }

    private fun stopSessions() {
        if (subscribed) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        subscribed = false
        clearController()
    }

    private fun clearController() {
        val old = controller
        val callback = controllerCallback
        controller = null
        controllerCallback = null
        if (old != null && callback != null) runCatching { old.unregisterCallback(callback) }
        mutableState.value = QqMusicState(
            installed = mutableState.value.installed,
            accessGranted = mutableState.value.accessGranted,
            listenerConnected = QqMusicNotificationListenerService.connected.value,
            error = mutableState.value.error
        )
    }

    private fun hasAccess(): Boolean = Settings.Secure.getString(appContext.contentResolver, ACCESS_SETTING)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    private fun unavailableReason(): String = when {
        !mutableState.value.installed -> "未安装QQ音乐，请先安装官方QQ音乐。"
        !mutableState.value.accessGranted -> "请先在系统通知访问设置中授予星伴权限。"
        !mutableState.value.listenerConnected -> "通知访问服务尚未连接，请检查系统通知访问设置。"
        else -> mutableState.value.error ?: "QQ音乐没有活跃会话，请先打开QQ音乐并播放歌曲。"
    }

    private fun control(current: MediaController, actions: Long, required: Long, label: String, action: MediaController.TransportControls.() -> Unit): PhoneCommandResult {
        if (actions and required == 0L) return PhoneCommandResult(false, "QQ音乐当前会话不支持${label}。")
        current.transportControls.action()
        return PhoneCommandResult(true, "已向QQ音乐提交${label}请求。")
    }

    private fun launchQqMusic(): PhoneCommandResult {
        val intent = appContext.packageManager.getLaunchIntentForPackage(QQ_PACKAGE)
            ?: return PhoneCommandResult(false, "未找到可打开的QQ音乐，请先安装或启用官方QQ音乐。")
        return launch(intent.setPackage(QQ_PACKAGE), "已向系统提交打开QQ音乐的请求。")
    }

    private fun launchAlarm(command: DeviceCommand.SetAlarm): PhoneCommandResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        if (intent.resolveActivity(appContext.packageManager) == null) return PhoneCommandResult(false, "当前设备没有可处理闹钟设置的时钟应用。")
        // SKIP_UI=false does not guarantee a confirmation screen or that an alarm was created.
        return launch(intent, "已向系统提交${command.hour}点${command.minute}分的闹钟设置请求，请在时钟中确认。")
    }

    private fun launch(intent: Intent, message: String): PhoneCommandResult = try {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PhoneCommandResult(true, message, handedOff = true)
    } catch (_: RuntimeException) {
        PhoneCommandResult(false, "系统未能打开目标应用，此次请求未提交。")
    }

    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "PhoneRuntime must be called on the main thread." } }

    companion object {
        const val QQ_PACKAGE = "com.tencent.qqmusic"
        private const val ACCESS_SETTING = "enabled_notification_listeners"
        private const val MAX_QUEUE_ITEMS = 50
        private fun bounded(value: CharSequence?): String? = value?.toString()?.take(200)?.takeIf { it.isNotBlank() }
    }
}

internal fun controllableVolumeScope(playbackType: Int, volumeControl: Int, maxVolume: Int): DeviceVolumeScope? {
    if (volumeControl != VolumeProvider.VOLUME_CONTROL_ABSOLUTE || maxVolume <= 0) return null
    return when (playbackType) {
        MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> DeviceVolumeScope.LOCAL_SHARED
        MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> DeviceVolumeScope.REMOTE_OUTPUT
        else -> null
    }
}

internal fun matchesVolumeScope(expected: DeviceVolumeScope?, actual: DeviceVolumeScope?): Boolean = expected != null && expected == actual

/** Supplies MediaSession access only; no notification content is read or retained. */
class QqMusicNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() { connection.value = true }
    override fun onListenerDisconnected() { connection.value = false }
    override fun onDestroy() { connection.value = false; super.onDestroy() }

    companion object {
        private val connection = MutableStateFlow(false)
        internal val connected: StateFlow<Boolean> = connection.asStateFlow()
    }
}
