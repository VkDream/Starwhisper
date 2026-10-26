package cn.xingban.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.xingban.app.R
import cn.xingban.app.CompanionUiState
import cn.xingban.core.Expression
import cn.xingban.core.ActivityType
import cn.xingban.core.ActivityMode
import cn.xingban.core.CompanionActivity
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class ReadingTouchArea(val action: String, val reply: String) {
    HAIR("摸摸头发", "头发都要被你摸乱啦。"),
    CHEEK("轻碰脸颊", "嗯？怎么啦……"),
    BOOK("看看书本", "想和我一起看这一页吗？")
}

internal data class TouchEllipse(val x: Float, val y: Float, val rx: Float, val ry: Float) {
    fun contains(px: Float, py: Float): Boolean {
        val dx = (px - x) / rx; val dy = (py - y) / ry
        return dx * dx + dy * dy <= 1f
    }
}

internal data class CompanionScene(val id: String, val label: String, val drawable: Int, val hair: TouchEllipse, val cheek: TouchEllipse)

// Only these six latest USER_APPROVED originals are copied into the application, in idle order.
internal val CompanionScenes = listOf(
    CompanionScene("03_reading", "低头读书", R.drawable.qian_reading, TouchEllipse(.51f, .145f, .19f, .10f), TouchEllipse(.57f, .343f, .065f, .030f)),
    CompanionScene("01_desk_rest", "桌边托腮", R.drawable.qian_desk_rest, TouchEllipse(.508f, .101f, .186f, .068f), TouchEllipse(.542f, .316f, .083f, .065f)),
    CompanionScene("02_sofa_cushion", "沙发抱枕", R.drawable.qian_sofa_cushion, TouchEllipse(.586f, .065f, .176f, .036f), TouchEllipse(.518f, .260f, .068f, .059f)),
    CompanionScene("04_window_profile", "窗边侧望", R.drawable.qian_window_profile, TouchEllipse(.520f, .120f, .080f, .055f), TouchEllipse(.347f, .309f, .051f, .040f)),
    CompanionScene("06_warm_mug", "捧着热饮", R.drawable.qian_warm_mug, TouchEllipse(.498f, .111f, .085f, .060f), TouchEllipse(.518f, .348f, .055f, .045f)),
    CompanionScene("18_chair_rest", "椅背休息", R.drawable.qian_chair_rest, TouchEllipse(.29f, .27f, .060f, .045f), TouchEllipse(.55f, .34f, .047f, .040f))
)
internal const val COMPANION_IDLE_MILLIS = 180_000L
internal const val COMPANION_QUESTION_MILLIS = 1_200_000L

internal fun companionScene(activity: CompanionActivity): CompanionScene = if (activity.mode == ActivityMode.AUTONOMOUS) CompanionScenes[activity.autonomousScene] else CompanionScenes[when (activity.type) {
    ActivityType.READ -> 0
    ActivityType.WORK -> 1
    ActivityType.WATCH -> 2
    ActivityType.MUSIC -> 3
    ActivityType.REST -> 5
    else -> 4
}]
private fun companionAvailable(state: CompanionUiState, draft: String) = state.loaded && !state.busy && !state.listening && !state.speaking && draft.isEmpty()
internal fun canRotateCompanionScene(state: CompanionUiState, draft: String, manualSceneId: String? = null) = manualSceneId == null && companionAvailable(state, draft) && state.data.activity.mode == ActivityMode.AUTONOMOUS && !state.data.activity.silent
internal fun canAskCompanionQuestion(state: CompanionUiState, draft: String) = companionAvailable(state, draft) && state.notice.isBlank() && !state.data.activity.quiet

@Composable
internal fun rememberCompanionForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

@Composable
internal fun CompanionIdleRotation(eligible: Boolean, interactionRevision: Long, scene: CompanionScene, onRotate: () -> Unit) {
    val currentRotate by rememberUpdatedState(onRotate)
    // Leaving Home disposes this effect. Busy, background and user input cancel rather than accrue time.
    LaunchedEffect(eligible, interactionRevision, scene.id) {
        if (eligible) {
            delay(COMPANION_IDLE_MILLIS)
            currentRotate()
        }
    }
}

@Composable
internal fun CompanionIdleQuestion(eligible: Boolean, interactionRevision: Long, onAsk: () -> Unit, idleMillis: Long = COMPANION_QUESTION_MILLIS) {
    val currentAsk by rememberUpdatedState(onAsk)
    // A scene change is deliberately not a key: automatic rotation is not user interaction.
    LaunchedEffect(eligible, interactionRevision) {
        if (eligible) { delay(idleMillis); currentAsk() }
    }
}

/** Invert the same whole-layer breath transform, then the centered 1024×1536 Fit image. */
internal fun companionTouchAt(scene: CompanionScene, x: Float, y: Float, width: Float, height: Float, breath: Float): ReadingTouchArea? {
    if (width <= 0f || height <= 0f || breath <= 0f) return null
    val fit = min(width / 1024f, height / 1536f)
    val imageWidth = 1024f * fit
    val imageHeight = 1536f * fit
    val px = ((x - width * 0.5f) / breath + width * 0.5f - (width - imageWidth) / 2f) / imageWidth
    val py = ((y - height * 0.82f) / breath + height * 0.82f - (height - imageHeight) / 2f) / imageHeight
    if (scene.hair.contains(px, py)) return ReadingTouchArea.HAIR
    if (scene.cheek.contains(px, py)) return ReadingTouchArea.CHEEK
    if (scene.id != "03_reading") return null
    // Conservative page/upper-cover polygon; hands, clothing and the surrounding dark background stay inert.
    val book = listOf(0.76f to 0.667f, 0.984f to 0.601f, 0.972f to 0.694f, 0.754f to 0.684f, 0.649f to 0.752f, 0.632f to 0.752f)
    var inside = false
    var previous = book.last()
    for (point in book) {
        if ((point.second > py) != (previous.second > py) && px < (previous.first - point.first) * (py - point.second) / (previous.second - point.second) + point.first) inside = !inside
        previous = point
    }
    return if (inside) ReadingTouchArea.BOOK else null
}

/** Accessibility uses the same scene hotspot and forward Fit/breath transform as physical taps. */
internal fun companionTouchAnchor(scene: CompanionScene, area: ReadingTouchArea, width: Float, height: Float, breath: Float): Offset {
    val anchor = when (area) {
        ReadingTouchArea.HAIR -> Offset(scene.hair.x, scene.hair.y)
        ReadingTouchArea.CHEEK -> Offset(scene.cheek.x, scene.cheek.y)
        ReadingTouchArea.BOOK -> Offset(.90f, .65f)
    }
    val fit = min(width / 1024f, height / 1536f)
    val x = (width - 1024f * fit) / 2f + anchor.x * 1024f * fit
    val y = (height - 1536f * fit) / 2f + anchor.y * 1536f * fit
    return Offset((x - width * .5f) * breath + width * .5f, (y - height * .82f) * breath + height * .82f)
}

private data class CompanionTouchFeedback(val area: ReadingTouchArea, val point: Offset)

/** Approved still images; local text and breathing do not imply facial or prop animation. */
@Composable
internal fun Companion2DAvatar(expression: Expression, speaking: Boolean, onTouch: () -> Unit, modifier: Modifier = Modifier, scene: CompanionScene = CompanionScenes.first()) {
    val foreground = rememberCompanionForeground()
    val stateLabel = if (speaking) "说话" else when (expression) {
        Expression.NEUTRAL -> "日常"
        Expression.HAPPY -> "开心"
        Expression.SHY -> "害羞"
        Expression.CONCERNED -> "关心"
        Expression.PLAYFUL -> "傲娇"
    }
    val breath = if (foreground) {
        val transition = rememberInfiniteTransition(label = "浅的呼吸")
        val scale by transition.animateFloat(1f, 1.012f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "轻微呼吸")
        scale
    } else 1f
    val currentBreath by rememberUpdatedState(breath)
    val currentOnTouch by rememberUpdatedState(onTouch)
    var size by remember { mutableStateOf(IntSize.Zero) }
    // These are display-only events. A new scene, layout or foreground lifetime discards old pixels.
    var feedback by remember(scene.id, size, foreground) { mutableStateOf<CompanionTouchFeedback?>(null) }
    var ripplePoint by remember(scene.id, size, foreground) { mutableStateOf<Offset?>(null) }
    var rippleRevision by remember { mutableLongStateOf(0L) }
    val rippleProgress = remember { Animatable(0f) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val acceptTouch: (ReadingTouchArea, Offset) -> Boolean = { area, point ->
        if (foreground && size != IntSize.Zero) {
            ripplePoint = point
            rippleRevision++
            val now = SystemClock.elapsedRealtime()
            if (now - lastTouch >= 650L) {
                lastTouch = now
                feedback = CompanionTouchFeedback(area, point)
                currentOnTouch()
            }
            true
        } else false
    }
    val currentAcceptTouch by rememberUpdatedState(acceptTouch)
    LaunchedEffect(rippleRevision, foreground, scene.id, size) {
        if (foreground && ripplePoint != null) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(1f, tween(520))
        }
        ripplePoint = null
    }
    LaunchedEffect(lastTouch, foreground, scene.id, size) {
        if (foreground && feedback != null) delay(1800)
        feedback = null
    }
    val density = LocalDensity.current
    val rippleRadius = with(density) { 36.dp.toPx() }
    val bubbleGap = with(density) { 18.dp.toPx() }
    val edge = with(density) { 12.dp.toPx() }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFFE0EFFC), Color(0xFFF4F9FF)))).onSizeChanged { size = it }) {
        Image(painterResource(scene.drawable), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().pointerInput(foreground, scene.id) {
            if (foreground) detectTapGestures { point ->
                companionTouchAt(scene, point.x, point.y, size.width.toFloat(), size.height.toFloat(), currentBreath)?.let { currentAcceptTouch(it, point) }
            }
        }.semantics {
            contentDescription = "浅，${scene.label}（单张），当前状态：$stateLabel"
            customActions = ReadingTouchArea.entries.filter { it != ReadingTouchArea.BOOK || scene.id == "03_reading" }.map { area -> CustomAccessibilityAction(area.action) { currentAcceptTouch(area, companionTouchAnchor(scene, area, size.width.toFloat(), size.height.toFloat(), currentBreath)) } }
        }.graphicsLayer {
            scaleX = breath
            scaleY = breath
            transformOrigin = TransformOrigin(0.5f, 0.82f)
        })
        ripplePoint?.let { point ->
            Canvas(Modifier.absoluteOffset { IntOffset((point.x - rippleRadius).roundToInt(), (point.y - rippleRadius).roundToInt()) }.size(72.dp).semantics { contentDescription = "触点光圈" }) {
                val radius = (14f + 16f * rippleProgress.value).dp.toPx()
                drawCircle(Color.White.copy(alpha = .42f * (1f - rippleProgress.value)), radius)
                drawCircle(Color.White.copy(alpha = .95f * (1f - rippleProgress.value)), radius, style = Stroke(5.dp.toPx()))
                drawCircle(Color(0xFF315C85).copy(alpha = 1f - rippleProgress.value), radius, style = Stroke(2.dp.toPx()))
            }
        }
        feedback?.let { event ->
            Surface(color = Color(0xFFF4F9FF).copy(alpha = 0.98f), shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp, modifier = Modifier
                .widthIn(max = 280.dp)
                .absoluteOffset {
                    val maxX = (size.width - bubbleSize.width - edge).coerceAtLeast(0f)
                    val maxY = (size.height - bubbleSize.height - edge).coerceAtLeast(0f)
                    val above = event.point.y - bubbleGap - bubbleSize.height
                    IntOffset((event.point.x - bubbleSize.width / 2f).coerceIn(edge.coerceAtMost(maxX), maxX).roundToInt(), (if (above >= edge) above else event.point.y + bubbleGap).coerceIn(edge.coerceAtMost(maxY), maxY).roundToInt())
                }.onSizeChanged { bubbleSize = it }.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "本地互动：${event.area.reply}" }) {
                Text(event.area.reply, color = Color(0xFF315C85), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}
