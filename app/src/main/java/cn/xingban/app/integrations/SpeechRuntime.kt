package cn.xingban.app.integrations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cn.xingban.app.R
import cn.xingban.app.data.BackgroundMusicTrack
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException
import java.util.ArrayDeque

sealed class SpeechOutput {
    data class SystemTts(val voiceName: String = "") : SpeechOutput()
    data class MiniMax(val apiKey: String, val voiceId: String) : SpeechOutput()
}

class SpeechRuntime(context: Context, private val listener: Listener, private val httpClient: OkHttpClient = speechHttpClient()) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onPlayingChanged(playing: Boolean)
        fun onError(message: String)
        fun onListeningChanged(listening: Boolean)
        fun onMusicChanged(playing: Boolean, track: BackgroundMusicTrack) = Unit
    }

    private data class QueuedSentence(val text: String, val output: SpeechOutput)

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ttsReady = CompletableDeferred<Boolean>()
    private val queue = ArrayDeque<QueuedSentence>()
    private var textToSpeech: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null
    private var activeUtteranceId: String? = null
    private var activeTtsContinuation: CancellableContinuation<Unit>? = null
    private var playbackJob: Job? = null
    private var recognitionGeneration = 0L
    private var generation = 0L
    private var isListening = false
    private var recognitionStopping = false
    private var ttsInitialized = false
    private var released = false
    private var speechSuppressed = true
    private var focusRequest: AudioFocusRequest? = null
    private var focusOwner: Long? = null
    private val musicAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var musicPlayer: MediaPlayer? = null
    private var musicPrepared = false
    private var musicFocusRequest: AudioFocusRequest? = null
    private var musicForeground = false
    private var musicEnabled = false
    private var musicSource = "local"
    private var musicVolume = 0.15
    private var musicPreference = BackgroundMusicTrack.MEDITATION_IMPROMPTU_01
    private var musicFocusBlocked = false
    var currentMusicTrack: BackgroundMusicTrack = BackgroundMusicTrack.MEDITATION_IMPROMPTU_01
        private set
    var isMusicPlaying: Boolean = false
        private set
    var isPlaying: Boolean = false
        private set

    init {
        checkMainThread()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (released) return@post
                val activeEngine = engine ?: textToSpeech
                val initialized = status == TextToSpeech.SUCCESS && activeEngine != null
                ttsInitialized = initialized
                if (initialized) {
                    activeEngine?.setAudioAttributes(audioAttributes)
                    activeEngine?.setOnUtteranceProgressListener(createTtsListener())
                }
                if (!ttsReady.isCompleted) ttsReady.complete(initialized)
                drainQueue()
            }
        }
        textToSpeech = engine
    }

    fun recognitionAvailable(): Boolean {
        checkMainThread()
        return !released && SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    fun startRecognition(languageTag: String = "zh-CN") {
        checkMainThread()
        if (released) return reportError("Speech runtime has been released.")
        if (isListening) return
        if (isPlaying || queue.isNotEmpty()) return reportError("Stop speech playback before listening.")
        if (!recognitionAvailable()) return reportError("Speech recognition is unavailable on this device.")
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return reportError("Microphone permission is required.")
        pauseMusic()
        try {
            val activeRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
            val session = ++recognitionGeneration
            activeRecognizer.setRecognitionListener(createRecognitionListener(session))
            activeRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            })
            recognitionStopping = false
            setListening(true)
        } catch (error: RuntimeException) {
            setListening(false)
            reportError("Could not start speech recognition: ${error.message ?: error.javaClass.simpleName}")
            resumeMusicIfAllowed()
        }
    }

    fun stopRecognition() {
        checkMainThread()
        if (released || !isListening || recognitionStopping) return
        try {
            recognitionStopping = true
            recognizer?.stopListening()
        } catch (error: RuntimeException) {
            recognitionStopping = false
            finishRecognition(recognitionGeneration)
            reportError("Could not stop speech recognition: ${error.message ?: error.javaClass.simpleName}")
            drainQueue()
        }
    }

    fun enqueueSentence(text: String, output: SpeechOutput) {
        checkMainThread()
        if (released) return reportError("Speech runtime has been released.")
        if (speechSuppressed) return
        if (text.isBlank()) return
        if (text.length > MAX_SENTENCE_CHARS) return reportError("Speech sentence exceeds $MAX_SENTENCE_CHARS characters.")
        val total = queue.size + if (isPlaying) 1 else 0
        if (total >= MAX_QUEUED_SENTENCES) return reportError("Speech queue is full.")
        if (output is SpeechOutput.MiniMax && (output.apiKey.isBlank() || output.voiceId.isBlank())) return reportError("MiniMax API key and voice ID are required.")
        pauseMusic()
        queue.addLast(QueuedSentence(text, output))
        if (isListening) stopRecognition() else drainQueue()
    }

    fun invalidateGeneration() {
        checkMainThread()
        val oldGeneration = generation
        generation++
        speechSuppressed = true
        recognitionGeneration++
        recognitionStopping = false
        setListening(false)
        val oldRecognizer = recognizer
        recognizer = null
        oldRecognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        queue.clear()
        playbackJob?.cancel()
        playbackJob = null
        textToSpeech?.stop()
        activeTtsContinuation?.cancel()
        activeTtsContinuation = null
        activeUtteranceId = null
        mediaPlayer?.let { player -> mediaPlayer = null; runCatching { player.release() } }
        currentAudioFile?.let { file -> currentAudioFile = null; file.delete() }
        abandonFocus(oldGeneration)
        setPlaying(false)
        resumeMusicIfAllowed()
    }

    /** Only an explicit new chat turn may re-enable speech after stop or loss of audio focus. */
    fun beginTurn() {
        checkMainThread()
        invalidateGeneration()
        speechSuppressed = false
    }

    private fun obtainFocus(owner: Long): Boolean {
        if (released || speechSuppressed || owner != generation) return false
        if (focusOwner == owner && focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change ->
                if (focusOwner == owner && generation == owner && change != AudioManager.AUDIOFOCUS_GAIN) {
                    musicFocusBlocked = true
                    releaseMusic()
                    invalidateGeneration()
                }
            }, mainHandler).build()
        focusOwner = owner
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            speechSuppressed = true
            musicFocusBlocked = true
            releaseMusic()
            abandonFocus(owner)
        }
        return granted
    }

    /** Selection is durable; completion advances only this runtime's current track. */
    fun configureMusic(enabled: Boolean, volume: Double, track: BackgroundMusicTrack, selectTrack: Boolean = false, musicSource: String = "local") {
        checkMainThread()
        require(volume.isFinite() && volume in 0.0..1.0)
        if (released) return
        if (enabled && !musicEnabled) musicFocusBlocked = false
        musicEnabled = enabled
        this.musicSource = musicSource
        musicVolume = volume
        val selectionChanged = track != musicPreference || selectTrack
        musicPreference = track
        if (selectionChanged) {
            releaseMusic()
            currentMusicTrack = track
            listener.onMusicChanged(false, currentMusicTrack)
        }
        if (!enabled || musicSource != "local") releaseMusic() else {
            try { if (musicPrepared) musicPlayer?.setVolume(volume.toFloat(), volume.toFloat()) }
            catch (_: RuntimeException) { musicFailed() }
            resumeMusicIfAllowed()
        }
    }

    /** Actual Activity visibility only; menus, drafts and page changes do not own this gate. */
    fun setForeground(foreground: Boolean) {
        checkMainThread()
        if (released) return
        if (foreground && !musicForeground) musicFocusBlocked = false
        musicForeground = foreground
        if (foreground) resumeMusicIfAllowed() else releaseMusic()
    }

    private fun resumeMusicIfAllowed() {
        if (released || !musicForeground || !musicEnabled || musicSource != "local" || musicFocusBlocked || isListening || isPlaying || playbackJob?.isActive == true || queue.isNotEmpty()) return
        val player = musicPlayer
        if (player == null) {
            prepareMusic()
            return
        }
        if (!musicPrepared || isMusicPlaying) return
        if (!obtainMusicFocus()) {
            musicFocusBlocked = true
            releaseMusic()
            return
        }
        try {
            player.setVolume(musicVolume.toFloat(), musicVolume.toFloat())
            player.start()
            setMusicPlaying(true)
        } catch (_: RuntimeException) { musicFailed() }
    }

    private fun prepareMusic() {
        val resource = when (currentMusicTrack) {
            BackgroundMusicTrack.MEDITATION_IMPROMPTU_01 -> R.raw.bgm_meditation_impromptu_01
            BackgroundMusicTrack.AVEC_SOIN -> R.raw.bgm_avec_soin
            BackgroundMusicTrack.MOONSTONE -> R.raw.bgm_moonstone
        }
        val player = MediaPlayer()
        musicPlayer = player
        musicPrepared = false
        try {
            player.setAudioAttributes(musicAttributes)
            appContext.resources.openRawResourceFd(resource).use { asset -> player.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length) }
            player.setOnPreparedListener {
                if (musicPlayer === it && !released) {
                    musicPrepared = true
                    resumeMusicIfAllowed()
                }
            }
            player.setOnCompletionListener {
                if (musicPlayer === it && !released) {
                    releaseMusic()
                    currentMusicTrack = currentMusicTrack.next()
                    listener.onMusicChanged(false, currentMusicTrack)
                    resumeMusicIfAllowed()
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                if (musicPlayer === failed) musicFailed()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) { musicFailed() }
    }

    private fun obtainMusicFocus(): Boolean {
        if (musicFocusRequest != null) return true
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(musicAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (musicFocusRequest === request && change != AudioManager.AUDIOFOCUS_GAIN) {
                    // Focus gain callbacks must never restart music over another app.
                    musicFocusBlocked = true
                    releaseMusic()
                }
            }, mainHandler).build()
        musicFocusRequest = request
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        abandonMusicFocus()
        return false
    }

    private fun pauseMusic() {
        try { if (isMusicPlaying) musicPlayer?.pause() }
        catch (_: RuntimeException) { musicFailed() }
        setMusicPlaying(false)
        abandonMusicFocus()
    }

    private fun releaseMusic() {
        val player = musicPlayer
        musicPlayer = null
        musicPrepared = false
        player?.let { runCatching { it.release() } }
        setMusicPlaying(false)
        abandonMusicFocus()
    }

    private fun abandonMusicFocus() {
        val request = musicFocusRequest
        musicFocusRequest = null
        if (request != null) audioManager.abandonAudioFocusRequest(request)
    }

    private fun setMusicPlaying(playing: Boolean) {
        if (isMusicPlaying == playing) return
        isMusicPlaying = playing
        listener.onMusicChanged(playing, currentMusicTrack)
    }

    private fun musicFailed() {
        musicFocusBlocked = true
        releaseMusic()
        reportError("背景音乐播放失败，请重新开启后再试。")
    }

    private fun abandonFocus(owner: Long) {
        if (focusOwner != owner) return
        val oldRequest = focusRequest
        focusOwner = null
        focusRequest = null
        if (oldRequest != null) audioManager.abandonAudioFocusRequest(oldRequest)
    }

    fun availableSystemVoices(): List<Pair<String, String>> {
        checkMainThread()
        if (released || !ttsInitialized) return emptyList()
        return textToSpeech?.voices.orEmpty().map { it.name to "${it.locale.displayName} (${it.name})" }.sortedBy { it.second }
    }

    suspend fun awaitSystemTtsReady(): Boolean = withContext(Dispatchers.Main.immediate) { ttsReady.await() }

    fun release() {
        checkMainThread()
        if (released) return
        musicForeground = false
        releaseMusic()
        invalidateGeneration()
        released = true
        textToSpeech?.shutdown()
        textToSpeech = null
        if (!ttsReady.isCompleted) ttsReady.complete(false)
        scope.cancel()
    }

    private fun drainQueue() {
        if (released || isListening || playbackJob?.isActive == true || queue.isEmpty()) return
        val playbackGeneration = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]
            try {
                if (!obtainFocus(playbackGeneration)) {
                    queue.clear()
                    reportError("语音焦点不可用，本回合保留文字。")
                    return@launch
                }
                setPlaying(true)
                while (!released && playbackGeneration == generation && queue.isNotEmpty()) {
                    val item = queue.removeFirst()
                    try {
                        when (val output = item.output) {
                            is SpeechOutput.SystemTts -> speakWithSystemTts(item.text, output.voiceName)
                            is SpeechOutput.MiniMax -> speakWithMiniMax(item.text, output, playbackGeneration)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportError(error.message ?: "Speech playback failed.")
                    }
                }
            } finally {
                abandonFocus(playbackGeneration)
                if (playbackJob === ownJob) {
                    setPlaying(false)
                    playbackJob = null
                    if (!released && playbackGeneration == generation) mainHandler.post { drainQueue(); resumeMusicIfAllowed() }
                }
            }
        }
        playbackJob = job
        job.start()
    }

    private suspend fun speakWithSystemTts(text: String, voiceName: String) {
        if (!awaitSystemTtsReady()) throw IOException("System text-to-speech failed to initialize.")
        suspendCancellableCoroutine<Unit> { continuation ->
            val engine = textToSpeech
            if (engine == null) {
                continuation.fail(IOException("System text-to-speech is unavailable."))
                return@suspendCancellableCoroutine
            }
            val voice = if (voiceName.isBlank()) {
                val availability = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (availability < 0) {
                    continuation.fail(IOException(if (availability == TextToSpeech.LANG_MISSING_DATA) "本机系统语音缺少中文数据。" else "本机系统语音不支持中文。"))
                    return@suspendCancellableCoroutine
                }
                val chineseVoice = engine.voice
                if (chineseVoice == null || chineseVoice.locale.language != "zh") {
                    continuation.fail(IOException("本机系统语音未能选择中文音色。"))
                    return@suspendCancellableCoroutine
                }
                chineseVoice
            } else engine.voices?.firstOrNull { it.name == voiceName }
            if (voice == null) {
                continuation.fail(IOException(if (voiceName.isBlank()) "System default voice is unavailable." else "Selected system voice is unavailable."))
                return@suspendCancellableCoroutine
            }
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                continuation.fail(IOException("Could not select the requested system voice."))
                return@suspendCancellableCoroutine
            }
            val utteranceId = UUID.randomUUID().toString()
            activeUtteranceId = utteranceId
            activeTtsContinuation = continuation
            continuation.invokeOnCancellation {
                mainHandler.post {
                    if (activeUtteranceId == utteranceId) {
                        textToSpeech?.stop()
                        activeUtteranceId = null
                        activeTtsContinuation = null
                    }
                }
            }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) != TextToSpeech.SUCCESS) {
                activeUtteranceId = null
                activeTtsContinuation = null
                continuation.fail(IOException("System text-to-speech rejected the sentence."))
            }
        }
    }

    private suspend fun speakWithMiniMax(text: String, output: SpeechOutput.MiniMax, playbackGeneration: Long) {
        val audio = synthesizeMiniMax(text, output)
        var createdFile: File? = null
        val file = try {
            withContext(Dispatchers.IO) {
                val created = File.createTempFile("xingban-speech-", ".mp3", appContext.cacheDir)
                createdFile = created
                created.writeBytes(audio)
                created
            }
        } catch (error: Throwable) {
            createdFile?.delete()
            throw error
        }
        if (released || playbackGeneration != generation) {
            file.delete()
            return
        }
        currentAudioFile = file
        playFile(file)
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val player = MediaPlayer()
        player.setAudioAttributes(audioAttributes)
        mediaPlayer = player
        var cleaned = false
        fun cleanup(): Boolean {
            if (cleaned) return false
            cleaned = true
            if (mediaPlayer === player) {
                mediaPlayer = null
                runCatching { player.release() }
            }
            if (currentAudioFile === file) currentAudioFile = null
            file.delete()
            return true
        }
        continuation.invokeOnCancellation { mainHandler.post { cleanup() } }
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                if (cleanup()) continuation.succeed(Unit)
            }
            player.setOnErrorListener { _, what, extra ->
                if (cleanup()) continuation.fail(IOException("MiniMax audio playback failed ($what/$extra)."))
                true
            }
            player.setOnPreparedListener { if (mediaPlayer === it && continuation.isActive) it.start() }
            player.prepareAsync()
        } catch (error: Exception) {
            if (cleanup()) continuation.fail(error)
        }
    }

    private suspend fun synthesizeMiniMax(text: String, output: SpeechOutput.MiniMax): ByteArray {
        val requestBody = encodeMiniMaxRequest(text, output.voiceId).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(MINIMAX_ENDPOINT).header("Authorization", "Bearer ${output.apiKey}").post(requestBody).build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            val completion = CompletionGate()
            continuation.invokeOnCancellation { completion.close(); call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    completion.finish { continuation.fail(error) }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val audio = response.use {
                            if (!it.isSuccessful) throw IOException("MiniMax speech request failed (HTTP ${it.code}).")
                            val body = it.body?.string() ?: throw IOException("MiniMax returned an empty response.")
                            decodeMiniMaxAudio(body)
                        }
                        completion.finish { continuation.succeedBytes(audio) }
                    } catch (error: Throwable) {
                        call.cancel()
                        completion.finish { continuation.fail(error) }
                    }
                }
            })
        }
    }

    private fun createRecognitionListener(session: Long): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(results: Bundle?) {
            if (session != recognitionGeneration || !isListening || released) return
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (session != recognitionGeneration || !isListening || released) return
            finishRecognition(session)
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onFinal)
            drainQueue()
        }

        override fun onError(error: Int) {
            if (session != recognitionGeneration || !isListening || released) return
            finishRecognition(session)
            reportError("Speech recognition failed ($error).")
            drainQueue()
        }
    }

    private fun createTtsListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            mainHandler.post { finishUtterance(utteranceId, null) }
        }

        @Deprecated("Deprecated by the Android API; retained for engines targeting older callbacks.")
        override fun onError(utteranceId: String?) {
            mainHandler.post { finishUtterance(utteranceId, IOException("System text-to-speech failed.")) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            mainHandler.post { finishUtterance(utteranceId, IOException("System text-to-speech failed ($errorCode).")) }
        }
    }

    private fun finishUtterance(utteranceId: String?, error: Throwable?) {
        if (utteranceId == null || utteranceId != activeUtteranceId) return
        activeUtteranceId = null
        val continuation = activeTtsContinuation
        activeTtsContinuation = null
        if (error == null) continuation?.succeed(Unit) else continuation?.fail(error)
    }

    private fun finishRecognition(session: Long) {
        if (session != recognitionGeneration) return
        recognitionStopping = false
        setListening(false)
        resumeMusicIfAllowed()
    }

    private fun setPlaying(value: Boolean) {
        if (isPlaying == value) return
        isPlaying = value
        listener.onPlayingChanged(value)
    }

    private fun setListening(value: Boolean) {
        if (isListening == value) return
        isListening = value
        listener.onListeningChanged(value)
    }

    private fun reportError(message: String) {
        listener.onError(message)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRuntime must be called on the main thread." }
    }

    companion object {
        private const val MINIMAX_ENDPOINT = "https://api.minimax.io/v1/t2a_v2"
        private const val MAX_QUEUED_SENTENCES = 32
        private const val MAX_SENTENCE_CHARS = 4_000

        private fun speechHttpClient(): OkHttpClient = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
}

internal fun encodeMiniMaxRequest(text: String, voiceId: String): String = JSONObject().apply {
    put("model", "speech-2.8-hd")
    put("text", text)
    put("stream", false)
    put("output_format", "hex")
    put("voice_setting", JSONObject().put("voice_id", voiceId).put("speed", 1).put("vol", 1).put("pitch", 0))
    put("audio_setting", JSONObject().put("sample_rate", 32_000).put("bitrate", 128_000).put("format", "mp3").put("channel", 1))
}.toString()

internal fun decodeMiniMaxAudio(responseBody: String): ByteArray {
    val response = JSONObject(responseBody)
    val base = response.optJSONObject("base_resp") ?: throw IOException("MiniMax response has no base_resp.")
    val code = base.optInt("status_code", Int.MIN_VALUE)
    if (code != 0) throw IOException("MiniMax speech request was rejected ($code).")
    val data = response.optJSONObject("data") ?: throw IOException("MiniMax response has no audio data.")
    if (data.optInt("status", Int.MIN_VALUE) != 2) throw IOException("MiniMax speech synthesis did not complete.")
    val format = response.optJSONObject("extra_info")?.optString("audio_format").orEmpty()
    if (format.isNotEmpty() && !format.equals("mp3", ignoreCase = true)) throw IOException("MiniMax returned an unsupported audio format.")
    val hex = data.optString("audio")
    if (hex.isEmpty() || hex.length % 2 != 0) throw IOException("MiniMax returned invalid hex audio.")
    val bytes = ByteArray(hex.length / 2)
    for (index in bytes.indices) {
        val high = hex[index * 2].digitToIntOrNull(16) ?: throw IOException("MiniMax returned invalid hex audio.")
        val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: throw IOException("MiniMax returned invalid hex audio.")
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes
}

private fun CancellableContinuation<Unit>.succeed(value: Unit) {
    resumeWith(Result.success(value))
}

private fun CancellableContinuation<ByteArray>.succeedBytes(value: ByteArray) {
    resumeWith(Result.success(value))
}

private fun <T> CancellableContinuation<T>.fail(error: Throwable) {
    resumeWith(Result.failure(error))
}
