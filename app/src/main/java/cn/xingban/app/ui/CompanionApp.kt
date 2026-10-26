package cn.xingban.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.xingban.app.ChatViewModel
import cn.xingban.app.CompanionUiState
import cn.xingban.app.data.AppSettings
import cn.xingban.app.data.BackgroundMusicTrack
import cn.xingban.core.MemoryStatus
import cn.xingban.core.UsageTotals
import cn.xingban.core.DeviceCommands
import cn.xingban.core.DeviceVolumeScope
import cn.xingban.app.integrations.ChatProvider
import cn.xingban.app.integrations.QqPlayback
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first

private val Ink = Color(0xFF315C85)
private val Linen = Color(0xFFF0F7FF)
private val Rose = Color(0xFF8FBCE2)

@Composable
fun CompanionApp(model: ChatViewModel, onMicrophone: () -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("home") }
    var manualSceneId by rememberSaveable { mutableStateOf<String?>(null) }
    var readingExpanded by rememberSaveable { mutableStateOf(true) }
    var interactionRevision by remember { mutableLongStateOf(0L) }
    val foreground = rememberCompanionForeground()
    val onInteraction: () -> Unit = { interactionRevision++; model.userInteraction() }
    DisposableEffect(page) {
        if (page != "home") model.setHomeAvailable(false)
        onDispose { model.setHomeAvailable(false) }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Rose, background = Linen, surface = Linen, surfaceVariant = Color(0xFFE9EEF3))) {
        Scaffold(modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed || it.previousPressed }) onInteraction()
                }
            }
        }, containerColor = Linen, bottomBar = {
            if (page != "home") NavigationBar(containerColor = Color(0xFFEAF3FC)) {
                listOf("home" to "陪伴", "memory" to "记忆", "settings" to "设置").forEach { (key, label) ->
                    NavigationBarItem(selected = page == key, onClick = { onInteraction(); if (page != key) model.stop(); page = key }, icon = { Text(when (key) { "home" -> "✦"; "memory" -> "♡"; else -> "⚙" }, fontSize = 23.sp) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (page != "home" && state.notice.isNotBlank()) Surface(color = Color(0xFFE8EEF4), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.notice, Modifier.weight(1f), fontSize = 12.sp, color = Ink)
                        TextButton(onClick = model::dismissNotice) { Text("知道了", fontSize = 12.sp) }
                    }
                }
                if (!state.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else when (page) {
                    "home" -> Home(state, model, onMicrophone, onConfigure = { onInteraction(); model.stop(); page = "settings" }, onMemory = { onInteraction(); model.stop(); page = "memory" }, scene = CompanionScenes.firstOrNull { it.id == manualSceneId } ?: companionScene(state.data.activity), manualSceneId = manualSceneId, onSceneSelected = { manualSceneId = it; onInteraction() }, readingExpanded = readingExpanded, onReadingExpandedChange = { readingExpanded = it }, foreground = foreground, interactionRevision = interactionRevision, onInteraction = onInteraction, onRotate = model::rotateCompanionScene)
                    "memory" -> Memories(state, model)
                    else -> Settings(state, model, onExport, onRestore)
                }
            }
        }
    }
}

@Composable
private fun Home(state: CompanionUiState, model: ChatViewModel, onMicrophone: () -> Unit, onConfigure: () -> Unit, onMemory: () -> Unit, scene: CompanionScene, manualSceneId: String?, onSceneSelected: (String?) -> Unit, readingExpanded: Boolean, onReadingExpandedChange: (Boolean) -> Unit, foreground: Boolean, interactionRevision: Long, onInteraction: () -> Unit, onRotate: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var sceneMenuExpanded by remember { mutableStateOf(false) }
    val available = foreground && !menuExpanded && !sceneMenuExpanded && input.isEmpty()
    DisposableEffect(available) {
        model.setHomeAvailable(available)
        onDispose { model.setHomeAvailable(false) }
    }
    CompanionIdleRotation(foreground && !menuExpanded && !sceneMenuExpanded && canRotateCompanionScene(state, input, manualSceneId), interactionRevision, scene, onRotate)
    CompanionIdleQuestion(foreground && !menuExpanded && !sceneMenuExpanded && canAskCompanionQuestion(state, input), interactionRevision, model::showIdleQuestion)
    LaunchedEffect(state.localQuestion) { if (state.localQuestion.isNotBlank()) { kotlinx.coroutines.delay(12_000); model.dismissLocalQuestion() } }
    val list = rememberLazyListState()
    var followingLatest by remember { mutableStateOf(true) }
    var pendingUserScroll by remember { mutableStateOf(false) }
    val latestAssistantId = state.data.messages.lastOrNull { it.role == "assistant" }?.id
    var seenAssistantId by remember { mutableStateOf(latestAssistantId) }
    var seenLiveReply by remember { mutableStateOf(state.liveReply) }
    var hasNewReply by remember { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(followingLatest, latestAssistantId, state.liveReply) {
        if (followingLatest) { seenAssistantId = latestAssistantId; seenLiveReply = state.liveReply; hasNewReply = false }
        else if (latestAssistantId != seenAssistantId || (state.liveReply.isNotBlank() && state.liveReply != seenLiveReply)) hasNewReply = true
    }
    LaunchedEffect(dragging, list.isScrollInProgress, list.canScrollForward) {
        if (dragging) { followingLatest = false; pendingUserScroll = true }
        else if (pendingUserScroll && !list.isScrollInProgress) { followingLatest = !list.canScrollForward; pendingUserScroll = false }
    }
    LaunchedEffect(state.data.messages.size, state.liveReply, state.notice, state.localQuestion, followingLatest, dragging, list.canScrollForward) {
        val size = state.data.messages.size + (if (state.liveReply.isNotBlank()) 1 else 0) + (if (state.notice.isNotBlank()) 1 else 0) + (if (state.localQuestion.isNotBlank()) 1 else 0)
        if (followingLatest && !dragging && size > 0 && list.canScrollForward) {
            list.scrollToItem(size - 1)
            val last = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == size - 1 }
            if (last != null && list.canScrollForward) list.scrollToItem(size - 1, last.size)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val messageListHeight = (if (readingExpanded) maxHeight * 0.70f else (maxHeight * 0.25f).coerceAtMost(210.dp))
            .coerceAtMost((maxHeight - 120.dp).coerceAtLeast(0.dp))
        Companion2DAvatar(
            expression = state.expression,
            speaking = state.speaking,
            onTouch = { onInteraction(); model.touch() },
            modifier = Modifier.fillMaxSize(),
            scene = scene,
        )
        // The whole chat overlay owns its taps, including transparent padding between controls.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Linen.copy(alpha = 0.36f), Linen.copy(alpha = 0.96f)))).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { onInteraction(); sceneMenuExpanded = true }, modifier = Modifier.semantics { contentDescription = "画面：${if (manualSceneId == null) "自动" else scene.label}" }, colors = ButtonDefaults.textButtonColors(containerColor = Linen.copy(alpha = 0.96f), contentColor = Ink)) { Text("画面 · ${if (manualSceneId == null) "自动" else scene.label}", fontSize = 12.sp) }
                DropdownMenu(sceneMenuExpanded, onDismissRequest = { sceneMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("自动") }, onClick = { sceneMenuExpanded = false; onSceneSelected(null) }, trailingIcon = { if (manualSceneId == null) Text("✓") })
                    CompanionScenes.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { sceneMenuExpanded = false; onSceneSelected(option.id) }, trailingIcon = { if (manualSceneId == option.id) Text("✓") })
                    }
                }
            }
            if (!followingLatest) TextButton(onClick = { onInteraction(); pendingUserScroll = false; followingLatest = true }, colors = ButtonDefaults.textButtonColors(containerColor = Linen.copy(alpha = 0.96f), contentColor = Ink)) { Text(if (hasNewReply) "有新回复 · 回到最新" else "回到最新", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onInteraction(); pendingUserScroll = false; onReadingExpandedChange(!readingExpanded) }, colors = ButtonDefaults.textButtonColors(containerColor = Linen.copy(alpha = 0.96f), contentColor = Ink)) { Text(if (readingExpanded) "收起" else "展开阅读", fontSize = 12.sp) }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(messageListHeight).padding(horizontal = 12.dp).semantics { contentDescription = "聊天消息" }, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.data.messages, key = { it.id }) { message ->
                val fade = followingLatest && !dragging && message.id != state.data.messages.lastOrNull()?.id
                val fading = if (fade) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                    drawContent()
                    val layout = list.layoutInfo
                    val item = layout.visibleItemsInfo.firstOrNull { it.key == message.id }
                    if (item != null) {
                        val start = (layout.viewportStartOffset - item.offset).toFloat()
                        val span = ((layout.viewportEndOffset - layout.viewportStartOffset) * 0.75f).coerceAtLeast(1f)
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = start, endY = start + span), blendMode = BlendMode.DstIn)
                    }
                } else Modifier
                Row(Modifier.fillMaxWidth().then(fading), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.role == "user") Color(0xFFDCE8F2).copy(alpha = 0.80f) else Color.White.copy(alpha = 0.78f), shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 315.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (message.role == "user") "你" else "浅", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            SelectionContainerCompat(message.text)
                            if (message.status !in setOf("completed", "local")) Text(when (message.status) { "pending" -> "等待回复"; "failed" -> "未完成"; else -> "已中断" }, fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }
            if (state.liveReply.isNotBlank()) item { Surface(color = Color.White.copy(alpha = 0.78f), shape = RoundedCornerShape(16.dp)) { Text(state.liveReply, Modifier.padding(12.dp), color = Ink) } }
            if (state.localQuestion.isNotBlank()) item { Surface(color = Color.White.copy(alpha = 0.84f), shape = RoundedCornerShape(16.dp)) { Text(state.localQuestion, Modifier.padding(12.dp), color = Ink) } }
            if (state.notice.isNotBlank()) item { Surface(color = Color(0xFFE8EEF4), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) { Text(state.notice, Modifier.weight(1f), fontSize = 12.sp, color = Ink); TextButton(onClick = model::dismissNotice) { Text("×") } } } }
        }
        if (state.partial.isNotBlank()) Text("识别中：${state.partial}", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = Ink)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                TextButton(onClick = { onInteraction(); menuExpanded = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "更多" }, contentPadding = PaddingValues(0.dp)) { Text("⋯", fontSize = 26.sp) }
                DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("记忆") }, onClick = { menuExpanded = false; onMemory() })
                    DropdownMenuItem(text = { Text("设置") }, onClick = { menuExpanded = false; onConfigure() })
                }
            }
            OutlinedTextField(input, { input = it; onInteraction(); model.draftChanged(it) }, placeholder = { Text("和浅说点什么…", fontSize = 14.sp) }, modifier = Modifier.weight(1f).semantics { contentDescription = "聊天输入" }, maxLines = 3, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.94f), unfocusedContainerColor = Color.White.copy(alpha = 0.90f)))
            Surface(color = if (state.listening) Rose else Color(0xFFE5EBF0), shape = RoundedCornerShape(18.dp), modifier = Modifier.size(48.dp).semantics { contentDescription = "按住说话" }.pointerInput(Unit) {
                detectTapGestures(onPress = { onMicrophone(); tryAwaitRelease(); model.finishListening() })
            }) { Box(contentAlignment = Alignment.Center) { Text(if (state.listening) "松开" else "按住", fontSize = 12.sp, color = Ink) } }
            Button(onClick = { pendingUserScroll = false; followingLatest = true; onInteraction(); model.send(input); if (state.hasSelectedChatKey || DeviceCommands.parse(input) != null) input = "" }, enabled = input.isNotBlank(), modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("发送", fontSize = 12.sp) }
            if (state.busy || state.listening || state.speaking) OutlinedButton(onClick = { onInteraction(); model.stop() }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("停止", fontSize = 12.sp) }
        }
        }
    }
}

@Composable private fun SelectionContainerCompat(text: String) { androidx.compose.foundation.text.selection.SelectionContainer { Text(text, color = Color(0xFF273747), fontSize = 15.sp) } }

@Composable private fun Memories(state: CompanionUiState, model: ChatViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }; var replacement by remember { mutableStateOf("") }; var forgetting by remember { mutableStateOf<String?>(null) }
    var viewingSummary by remember { mutableStateOf(false) }
    val active = state.data.memory.items.filter { it.status == MemoryStatus.ACTIVE }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("一起记得的事", fontSize = 26.sp, color = Ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Text("只保留有来源的用户事实。更正或忘记会停止旧回合，重置此前上下文；原聊天继续保留显示。", fontSize = 13.sp, color = Color.Gray)
        Text("记忆修订 ${state.data.memory.revision} · ${active.size} 条生效", fontSize = 12.sp, color = Ink, modifier = Modifier.padding(vertical = 12.dp))
        Text("对话摘要由机器整理，可能有误；与用户事实分开。原聊天完整保留。", fontSize = 12.sp, color = Color.Gray)
        if (state.data.summary == null) Text("尚无对话摘要", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        else TextButton(onClick = { viewingSummary = true }) { Text("查看对话摘要") }
        if (active.isEmpty()) Text("还没有记忆。浅会在正常聊天中自动判断值得长期记住的稳定事实，并保留你的原话来源；你随时可以更正或忘记。", fontSize = 15.sp, modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(active, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.text, color = Ink, fontSize = 17.sp)
                        item.sources.forEach { source -> Text("来源 ${if (source.kind.name == "USER_CONFIRMATION") "手动更正" else "你的发言"} · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(source.occurredAtMillis))}\n${source.text}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp)) }
                        Row { TextButton(onClick = { editing = item.id; replacement = item.text }) { Text("更正") }; TextButton(onClick = { forgetting = item.id }) { Text("忘记") } }
                    }
                }
            }
            item { Text("忘记表示不再用于未来回忆。原聊天与已导出的旧备份仍然保留。", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 14.dp)) }
        }
    }
    if (viewingSummary && state.data.summary != null) AlertDialog(onDismissRequest = { viewingSummary = false }, title = { Text("对话摘要 · 机器整理，可能有误") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainerCompat(state.data.summary.text); Text("派生资料，不会自动保存为用户事实。", fontSize = 12.sp, color = Color.Gray) }
    }, confirmButton = { TextButton(onClick = { model.clearSummary(); viewingSummary = false }) { Text("清除摘要，保留聊天") } }, dismissButton = { TextButton(onClick = { viewingSummary = false }) { Text("关闭") } })
    if (editing != null) AlertDialog(onDismissRequest = { editing = null }, title = { Text("更正记忆") }, text = {
        OutlinedTextField(replacement, { replacement = it }, label = { Text("正确的事实") }, maxLines = 6, isError = replacement.length > 500, modifier = Modifier.semantics { contentDescription = "记忆更正输入" },
            supportingText = { Text("${replacement.length}/500" + if (replacement.length > 500) " · 超出上限，请缩短后保存" else "") })
    }, confirmButton = { TextButton(onClick = { model.editMemory(editing!!, replacement); editing = null }, enabled = replacement.isNotBlank() && replacement.length <= 500) { Text("保存更正") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    if (forgetting != null) AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("让浅忘记这件事？") }, text = { Text("该条记忆及相同来源会退出未来上下文。原聊天和旧备份不会被删除。") }, confirmButton = { TextButton(onClick = { model.editMemory(forgetting!!, null); forgetting = null }) { Text("确认忘记") } }, dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } })
}

@Composable private fun UsageLine(label: String, value: UsageTotals) {
    fun count(known: Long, unknown: Int) = if (unknown == 0) known.toString() else known.toString() + "（另有" + unknown + "次未确认）"
    val text = if (value.requests == 0) "尚无请求" else
        "输入 " + count(value.prompt, value.promptUnknown) + " · 输出 " + count(value.completion, value.completionUnknown) +
        "\n缓存命中 " + count(value.cacheHit, value.cacheHitUnknown) + " · 未命中 " + count(value.cacheMiss, value.cacheMissUnknown) +
        "\n" + (if (value.requests == value.goRequests) "官方参考计价：无官方请求" else if (value.unpriced == value.requests - value.goRequests) "官方参考费用未确认（${value.unpriced}次请求）" else
        String.format(Locale.CHINA, "官方已确认部分参考 ¥%.6f–%.6f", value.feeLow, value.feeHigh) + if (value.unpriced > 0) "；另有" + value.unpriced + "次费用未确认" else "") +
        (if (value.goRequests == 0) "" else "\nOpenCode Go ${value.goRequests}次 · usage.cost合计 ${value.goReportedCost} · billing_source ${value.goBillingSources.joinToString().ifBlank { "未返回" }}；${value.goCostUnknown}次cost未确认。金额单位/实际账单未确认，不含套餐月费。")
    Text(label + " · " + text, fontSize = 12.sp, color = Ink)
}

@Composable private fun Settings(state: CompanionUiState, model: ChatViewModel, onExport: () -> Unit, onRestore: () -> Unit) {
    val storedSettings = state.data.settings
    // Music saves independently; keep unsaved voice and profile edits while its controls change.
    var settings by remember(storedSettings.provider, storedSettings.model, storedSettings.speechMode, storedSettings.voiceId, storedSettings.systemVoice, storedSettings.userAddress, storedSettings.warmth, storedSettings.playfulness, storedSettings.tsundere) { mutableStateOf(storedSettings) }
    var musicVolume by remember(storedSettings.musicVolume) { mutableDoubleStateOf(storedSettings.musicVolume) }
    var musicExpanded by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    var deepKey by remember { mutableStateOf("") }; var voiceKey by remember { mutableStateOf("") }; var goKey by remember { mutableStateOf("") }
    var clearDeep by remember { mutableStateOf(false) }; var clearVoice by remember { mutableStateOf(false) }; var clearGo by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val ordinaryDraft = settings.copy(musicEnabled = storedSettings.musicEnabled, musicVolume = storedSettings.musicVolume, musicTrack = storedSettings.musicTrack, musicSource = storedSettings.musicSource)
    val dirty = ordinaryDraft != storedSettings || deepKey.isNotBlank() || voiceKey.isNotBlank() || goKey.isNotBlank() || clearDeep || clearVoice || clearGo
    LaunchedEffect(saving) {
        if (saving) {
            val result = model.state.first { it.notice == "设置已保存。" || it.notice == "设置保存失败，请核对输入。" }
            if (result.notice == "设置已保存。") { deepKey = ""; voiceKey = ""; goKey = ""; clearDeep = false; clearVoice = false; clearGo = false }
            saving = false
        }
    }
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("把陪伴调成你的样子", fontSize = 25.sp, color = Ink)
        Text("服务由你配置。Key 仅在本机加密保存，不随备份导出。", fontSize = 13.sp, color = Color.Gray)
        Label("实际用量与参考费用")
        if (state.usageSavePending) Text("部分用量尚未保存；关闭提示不会清除此状态。退出进程可能丢失这部分本机记录。", color = Color(0xFF9B3A32), fontSize = 12.sp)
        Text("服务返回的实际 Token；聊天与摘要分别计量。本轮指最近发送的用户回合及其摘要维护。", fontSize = 12.sp, color = Color.Gray)
        UsageLine("本轮聊天", state.data.usage.roundChat)
        UsageLine("本轮摘要", state.data.usage.roundSummary)
        UsageLine("本轮合计", state.data.usage.roundChat + state.data.usage.roundSummary)
        val today = state.data.usage.day == LocalDate.now().toString()
        val todayChat = if (today) state.data.usage.chat else UsageTotals()
        val todaySummary = if (today) state.data.usage.summary else UsageTotals()
        UsageLine("今日聊天", todayChat)
        UsageLine("今日摘要", todaySummary)
        UsageLine("今日合计", todayChat + todaySummary)
        Text("今日搜索成功 ${if (today) state.data.usage.searchSuccess else 0} 次 · 失败/取消且计费未确认 ${if (today) state.data.usage.searchUncertain else 0} 次。官方参考每次成功 US\$0.00075，搜索费另计；不等于账号实际扣费。", fontSize = 11.sp, color = Color.Gray)
        Text("2026-10-25 DeepSeek Flash 公开价参考：每百万命中/未命中/输出，空闲 ¥0.02/1/4，高峰 ¥0.04/2/8；显示两档区间，不代替账单。未知模型或不完整用量不计准确总价。取消或错误也可能产生费用；未返回的用量显示未确认。聊天的 4800 UTF8 字节是应用预算代理，并非 Token。", fontSize = 11.sp, color = Color.Gray)
        Label("背景音乐")
        Text("更改自动保存，与下方普通设置草稿分开。", fontSize = 12.sp, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = storedSettings.musicSource == "local", onClick = { model.selectMusicSource("local") }, label = { Text("星伴钢琴") })
            FilterChip(selected = storedSettings.musicSource == "qq", onClick = { model.selectMusicSource("qq") }, label = { Text("QQ音乐") })
        }
        if (storedSettings.musicSource == "qq") QqMusicControls(state, model)
        else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("轻柔钢琴", Modifier.weight(1f), color = Ink)
            Switch(checked = storedSettings.musicEnabled, onCheckedChange = { model.saveMusicSettings(it, musicVolume, storedSettings.musicTrack) }, modifier = Modifier.semantics { contentDescription = "背景音乐开关" })
        }
        Text("只在打开应用时播放，录音和朗读时会暂停。", fontSize = 13.sp, color = Color.Gray)
        Text("音乐音量 · ${(musicVolume * 100).toInt()}%", fontSize = 13.sp, color = Ink)
        Slider(value = musicVolume.toFloat(), onValueChange = { musicVolume = it.toDouble() }, onValueChangeFinished = { model.saveMusicSettings(storedSettings.musicEnabled, musicVolume, storedSettings.musicTrack) }, modifier = Modifier.semantics { contentDescription = "背景音乐音量" })
        Box {
            OutlinedButton(onClick = { musicExpanded = true }) { Text("选择曲目 · ${storedSettings.musicTrack.title}") }
            DropdownMenu(expanded = musicExpanded, onDismissRequest = { musicExpanded = false }) {
                BackgroundMusicTrack.entries.forEach { track ->
                    DropdownMenuItem(text = { Text(track.title) }, onClick = { musicExpanded = false; model.saveMusicSettings(storedSettings.musicEnabled, musicVolume, track, selectTrack = true) })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (!storedSettings.musicEnabled) "已关闭" else "${if (state.musicPlaying) "正在播放" else "已暂停"} · ${state.musicTrack.title}", Modifier.weight(1f), fontSize = 12.sp, color = Color.Gray)
            TextButton(onClick = model::nextMusic) { Text("下一首") }
        }
        }
        Label("对话服务")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatProvider.entries.forEach { provider -> FilterChip(selected = settings.provider == provider.setting, enabled = !saving, onClick = { model.stop(); settings = settings.copy(provider = provider.setting, model = provider.model) }, label = { Text(if (provider == ChatProvider.DEEPSEEK) "DeepSeek官方" else provider.setting) }) }
        }
        OutlinedTextField(ChatProvider.fromSetting(settings.provider).model, {}, readOnly = true, enabled = !saving, label = { Text("固定模型") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "对话模型" }, singleLine = true)
        Text("最高思考已启用。两家Key分别保存，失败不会自动换服务。联网搜索始终只使用DeepSeek官方Key。", fontSize = 12.sp, color = Color.Gray)
        SecretField(deepKey, { deepKey = it }, "DeepSeek API Key", state.hasDeepSeekKey, clearDeep, enabled = !saving)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearDeep, { clearDeep = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "删除 DeepSeek Key" }); Text("删除已保存的 DeepSeek Key", fontSize = 13.sp) }
        SecretField(goKey, { goKey = it }, "OpenCode Go API Key", state.hasGoKey, clearGo, enabled = !saving)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearGo, { clearGo = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "删除 OpenCode Go Key" }); Text("删除已保存的 OpenCode Go Key", fontSize = 13.sp) }
        Text("手机命令示例：暂停QQ音乐、下一首、QQ音乐音量30%、打开QQ音乐、设置8点30分闹钟。仅当前整句命令执行；系统闹钟实际状态请在时钟中确认。", fontSize = 12.sp, color = Color.Gray)
        Label("声音 · 明确选择")
        Text("系统识别服务可能联网。语音合成关闭时只显示文字；不会自动切换声音服务。", fontSize = 13.sp, color = Color.Gray)
        Row(modifier = Modifier.semantics { contentDescription = "语音方式" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("off" to "关闭", "system" to "系统 TTS", "minimax" to "MiniMax").forEach { (key, title) -> FilterChip(selected = settings.speechMode == key, onClick = { settings = settings.copy(speechMode = key) }, enabled = !saving, label = { Text(title) }) }
        }
        if (settings.speechMode == "system") {
            Text("本机开发/可用模式，需已安装支持中文的 TTS 引擎。声音效果由手机决定。", fontSize = 13.sp)
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { expanded = true }, enabled = !saving) { Text(if (settings.systemVoice.isBlank()) "选择系统音色（当前引擎默认中文）" else settings.systemVoice, maxLines = 1) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("引擎默认中文") }, enabled = !saving, onClick = { settings = settings.copy(systemVoice = ""); expanded = false })
                model.systemVoices().forEach { (name, label) -> DropdownMenuItem(text = { Text(label) }, enabled = !saving, onClick = { settings = settings.copy(systemVoice = name); expanded = false }) }
            }
        }
        if (settings.speechMode == "minimax") {
            Text("使用 speech-2.8-hd 按句合成。请自行确认账号、区域、费用与音色权限；未填完整配置不会请求。", fontSize = 13.sp)
            SecretField(voiceKey, { voiceKey = it }, "MiniMax API Key", state.hasMiniMaxKey, clearVoice, enabled = !saving)
            OutlinedTextField(settings.voiceId, { settings = settings.copy(voiceId = it) }, enabled = !saving, label = { Text("已选音色 ID（必填）") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearVoice, { clearVoice = it }, enabled = !saving, modifier = Modifier.semantics { contentDescription = "删除 MiniMax Key" }); Text("删除已保存的 MiniMax Key", fontSize = 13.sp) }
        }
        Label("浅的相处偏好")
        OutlinedTextField(settings.userAddress, { settings = settings.copy(userAddress = it) }, enabled = !saving, label = { Text("希望浅怎么称呼你") }, isError = settings.userAddress.length > 40,
            supportingText = { Text("${settings.userAddress.length}/40" + if (settings.userAddress.length > 40) " · 超出上限，请缩短后保存" else "") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "昵称输入" })
        PreferenceSlider("温柔", settings.warmth, enabled = !saving) { settings = settings.copy(warmth = it) }
        PreferenceSlider("活泼", settings.playfulness, enabled = !saving) { settings = settings.copy(playfulness = it) }
        PreferenceSlider("傲娇", settings.tsundere, enabled = !saving) { settings = settings.copy(tsundere = it) }
        HorizontalDivider()
        Label("聊天、心情与记忆备份")
        Text("导出文件包含私人聊天，不含 Key。恢复会替换当前聊天、心情、记忆和普通设置；坏文件不会覆盖原数据。", fontSize = 13.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedButton(onClick = onExport) { Text("导出备份") }; OutlinedButton(onClick = { restoring = true }) { Text("恢复备份") } }
        HorizontalDivider()
        Label("音乐鸣谢")
        Text("以下原曲由 Kevin MacLeod（incompetech.com）创作，采用 CC BY 4.0 许可。音乐保留该许可下的使用权。", fontSize = 12.sp, color = Color.Gray)
        BackgroundMusicTrack.entries.forEach { track ->
            Text("“${track.title}” · Kevin MacLeod (incompetech.com)", fontSize = 12.sp, color = Ink)
            TextButton(onClick = { uriHandler.openUri(track.sourceUrl) }) { Text("官方来源 · ${track.title}", fontSize = 12.sp) }
        }
        TextButton(onClick = { uriHandler.openUri("https://creativecommons.org/licenses/by/4.0/") }) { Text("Creative Commons Attribution 4.0 许可", fontSize = 12.sp) }
        Text("星伴 0.1.0 · 浅\n轻碰浅的头发、脸颊或书本，看看她的回应。", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 16.dp))
    }
    Surface(color = Linen, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(if (saving) "正在保存普通设置…" else if (dirty) "普通设置有未保存更改" else "普通设置无未保存更改", fontSize = 12.sp, color = Ink)
            Button(onClick = {
                model.dismissNotice(); saving = true
                model.saveSettings(ordinaryDraft, if (clearDeep) "" else deepKey.takeIf { it.isNotBlank() }, if (clearVoice) "" else voiceKey.takeIf { it.isNotBlank() }, if (clearGo) "" else goKey.takeIf { it.isNotBlank() })
            }, enabled = dirty && settings.userAddress.length <= 40 && !saving, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
        }
    }
    }
    if (restoring) AlertDialog(onDismissRequest = { restoring = false }, title = { Text("恢复备份") }, text = { Text("建议先导出当前数据。选定有效备份后会替换聊天、记忆、心情与普通设置，本机 API Key 保留。") }, confirmButton = { TextButton(onClick = { restoring = false; onRestore() }) { Text("选择备份文件") } }, dismissButton = { TextButton(onClick = { restoring = false }) { Text("取消") } })
}

@Composable private fun QqMusicControls(state: CompanionUiState, model: ChatViewModel) {
    val music = state.qqMusic
    Text(when {
        !music.installed -> "未安装QQ音乐。"
        !music.accessGranted -> "需要在系统通知访问设置中允许星伴，才能控制QQ音乐会话。星伴不会读取或保存通知正文。"
        !music.listenerConnected -> "通知访问尚未连接，请从系统设置返回后重试。"
        !music.sessionAvailable -> "QQ音乐没有活跃播放会话，请主动打开QQ音乐并播放歌曲。"
        else -> listOfNotNull(music.title ?: "当前曲目未提供", music.artist).joinToString(" · ") + "\n" + when (music.playback) { QqPlayback.PLAYING -> "正在播放"; QqPlayback.PAUSED -> "已暂停"; QqPlayback.STOPPED -> "已停止"; QqPlayback.BUFFERING -> "缓冲中"; QqPlayback.ERROR -> "播放异常"; else -> "播放状态未提供" }
    }, fontSize = 13.sp, color = Ink)
    music.error?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
    if (!music.accessGranted) OutlinedButton(onClick = model::openNotificationAccessSettings) { Text("打开通知访问设置") }
    OutlinedButton(onClick = { model.send("打开QQ音乐") }, enabled = music.installed && !state.busy) { Text("打开QQ音乐") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.send("上一首") }, enabled = music.canPrevious && !state.busy) { Text("上一首") }
        OutlinedButton(onClick = { model.send(if (music.playback == QqPlayback.PLAYING) "暂停QQ音乐" else "播放QQ音乐") }, enabled = (if (music.playback == QqPlayback.PLAYING) music.canPause else music.canPlay) && !state.busy) { Text(if (music.playback == QqPlayback.PLAYING) "暂停" else "播放") }
        OutlinedButton(onClick = { model.send("下一首") }, enabled = music.canNext && !state.busy) { Text("下一首") }
    }
    val volumeLabel = when (music.volumeScope) { DeviceVolumeScope.LOCAL_SHARED -> "手机播放音量（系统共享）"; DeviceVolumeScope.REMOTE_OUTPUT -> "播放设备音量"; null -> "音量目标不可用" }
    var volume by remember(music.volumePercent, music.volumeScope) { mutableFloatStateOf((music.volumePercent ?: 0) / 100f) }
    var gestureScope by remember { mutableStateOf<DeviceVolumeScope?>(null) }
    var changingVolume by remember { mutableStateOf(false) }
    LaunchedEffect(music.canSetVolume) { if (!music.canSetVolume) { gestureScope = null; changingVolume = false } }
    Text(if (music.canSetVolume && music.volumeScope != null) "$volumeLabel · ${(volume * 100).toInt()}%" else "当前QQ音乐输出不支持设置绝对音量。", fontSize = 12.sp, color = Color.Gray)
    Slider(volume, { if (!changingVolume) { gestureScope = music.volumeScope; changingVolume = true }; volume = it }, enabled = music.canSetVolume && music.volumeScope != null && !state.busy, onValueChangeFinished = {
        if (changingVolume) { val expected = gestureScope; changingVolume = false; gestureScope = null; model.sendMusicVolume((volume * 100).toInt(), expected) }
    }, modifier = Modifier.semantics { contentDescription = volumeLabel })
    val queue = music.queue
    Text(if (queue == null) "QQ音乐未提供当前队列。" else if (queue.isEmpty()) "当前队列为空。" else "当前队列（QQ音乐提供）", fontSize = 12.sp, color = Color.Gray)
    queue?.forEach { Text(listOfNotNull(it.title, it.artist).joinToString(" · "), fontSize = 12.sp, color = Ink) }
}
@Composable private fun Label(text: String) { Text(text, fontSize = 18.sp, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
@Composable private fun SecretField(value: String, change: (String) -> Unit, label: String, exists: Boolean, deleting: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, enabled = enabled, label = { Text(label) }, placeholder = { Text(if (exists) "已安全保存；留空保持" else "尚未配置") },
        supportingText = { Text(when { deleting -> "待保存：删除已保存的 Key"; value.isNotBlank() -> if (exists) "待保存：替换现有 Key" else "待保存：添加 Key"; exists -> "已配置 · 留空保持现有 Key"; else -> "尚未配置 · 留空不作更改" }) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
@Composable private fun PreferenceSlider(label: String, value: Double, enabled: Boolean = true, change: (Double) -> Unit) { Column { Text("$label · ${(value * 100).toInt()}%", fontSize = 13.sp, color = Ink); Slider(value.toFloat(), { change(it.toDouble()) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "${label}偏好" }) } }
