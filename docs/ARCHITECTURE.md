# 星伴 · Starwhisper 架构与关键流程

星伴是一个原生 Android 陪伴应用。当前主页采用六张 2D 原画、轻微呼吸与分区触摸，支持文字聊天、可选语音、带来源的本地记忆、本地背景音乐，以及用户明确发起的 QQ 音乐和系统闹钟操作。仓库展示名为 **星伴 · Starwhisper**，应用名称仍为 **星伴**。

本文描述当前物理源码，不把早期设计、保留素材或未来设想当成已实现功能。来源读取于 **2026-10-26T01:49:36.387Z**；行号对应当时工作区字节，未绑定 Git 提交。架构图及浏览器检查只证明文档和图产物。

## 阅读入口

| 视图 | 要回答的问题 | 静态预览 | 离线查看器 | 可编辑源 |
| --- | --- | --- | --- | --- |
| 系统架构 | 模块、外部服务、唯一所有者 | [SVG](architecture/xingban-architecture.svg) · [PNG](architecture/xingban-architecture.png) | [HTML](architecture/xingban-architecture.html) | [candidate.json](architecture/candidate.json) |
| 聊天回合 | 输入分流、流式显示、最终提交、取消和失败 | [SVG](architecture/chat-turn.svg) · [PNG](architecture/chat-turn.png) | [HTML](architecture/chat-turn.html) | [JSON](architecture/chat-turn.json) |
| 只读时间与搜索 | 一轮规划、工具校验、最终回复 | [SVG](architecture/read-only-tools.svg) · [PNG](architecture/read-only-tools.png) | [HTML](architecture/read-only-tools.html) | [JSON](architecture/read-only-tools.json) |
| 手机与音频 | QQ 权限/action/scope、系统移交、媒体生命周期 | [SVG](architecture/phone-audio.svg) · [PNG](architecture/phone-audio.png) | [HTML](architecture/phone-audio.html) | [JSON](architecture/phone-audio.json) |
| 数据与记忆 | 真实 Room 结构、来源校验、备份恢复 | [SVG](architecture/storage-memory.svg) · [PNG](architecture/storage-memory.png) | [HTML](architecture/storage-memory.html) | [JSON](architecture/storage-memory.json) |

[统一图册入口](architecture/index.html)可从下载的仓库本地打开；GitHub 可直接预览上表中的 SVG/PNG。HTML 不需要联网加载脚本、字体或图源。修改图时以对应 JSON 为单一源，再重新生成 HTML 和静态导出。总览按职责聚合，连线表达逻辑输入、控制或状态数据流；平台执行与资源生命周期由对应流程展开。

![系统架构](architecture/xingban-architecture.svg)

## 模块与运行边界

应用只有两个 Gradle 业务模块，依赖方向为 `:app → :core`。

| 模块 / 入口 | 当前责任 | 直接来源 |
| --- | --- | --- |
| `:core` | 纯 Kotlin：回合身份、人设、心情、陪伴活动、上下文、记忆来源规则、摘要选择与用量账本 | [core/build.gradle.kts:1](../core/build.gradle.kts#L1)；下方规则来源 |
| `:app` | Compose 页面、ViewModel 编排、Room、凭据存储、HTTP/SSE、ASR/TTS、音乐和手机平台操作 | [app/build.gradle.kts:31](../app/build.gradle.kts#L31) |
| MainActivity | 创建前台 ViewModel，挂接 Compose，申请麦克风权限，提供用户文件选择器，转发 onStart/onStop | [MainActivity.kt:17](../app/src/main/java/cn/xingban/app/MainActivity.kt#L17) |
| CompanionApp | 展示 StateFlow，转发用户操作，保存页面、手动画面及聊天展开选择 | [CompanionApp.kt:53](../app/src/main/java/cn/xingban/app/ui/CompanionApp.kt#L53) |

部署边界是 Android 单应用与本机私有存储。当前源码没有自建业务服务器、账号服务或云端记忆同步。对话、搜索和可选 MiniMax 语音属于外部网络边界；Android 识别服务是否联网取决于设备服务，不能称为已保证离线。

源码中 DeepSeek 官方与 OpenCode Go 使用独立的固定端点、模型配置及凭据。聊天和摘要捕获同一个回合路由，搜索始终使用独立的官方 Key；不自动重试、不随失败换服务、不跟随重定向。这里记录的是**请求配置**，供应商实际运行模型仍为 UNKNOWN。[DialogueClient.kt:33–101](../app/src/main/java/cn/xingban/app/integrations/DialogueClient.kt#L33)

## 唯一状态与资源所有者

| 所有者 | 拥有什么 | 生命周期 / 提交边界 |
| --- | --- | --- |
| ChatViewModel | 唯一 UiState/AppData 投影、当前请求 Job、requestOwner、Mutex、可见性、会话及各运行时 | 主线程编排；Mutex 串行完整快照；commit 先 store.save 再更新 StateFlow |
| CompanionSession | turnId、generation、memoryRevision、当前 TurnToken | 只拥有请求身份，不拥有网络、音频或数据库；stop 推进 generation，使迟到结果失效 |
| CharacterReducer / CompanionActivity | 心情事件归约、活动类型/阶段/修订 | 纯规则返回新值，由 ViewModel 持久化；画面不是第二份活动真值 |
| AppStore / StateCodec | Room 连接、完整 JSON 快照事务及编解码校验 | 一次 save 在事务内替换 id=1；消息、心情、记忆版本一起保存 |
| DialogueClient / ReadOnlyTools | 单次 HTTP Call、响应读取、SSE/JSON 协议及只读工具执行 | 取消关闭对应 Call/reader；无自动重试；用量观察在结束或失败时统一回报 |
| SpeechRuntime | SpeechRecognizer、TTS 队列、MiniMax 请求、播放器、音频文件及焦点 | 代际取消清队列/停播/释放；听说互斥；本地音乐按前台和来源门恢复 |
| PhoneRuntime | 固定 QQ 包的 MediaController、监听与回调、系统 Intent 移交 | 主线程与前台执行；后台解绑监听，close 释放自有 scope；不拥有 QQ 的播放器 |
| Compose / Companion2DAvatar | 手动画面选择、瞬时呼吸/光圈/短句与命中坐标 | 手选仅覆盖显示；退出组合、前后台/尺寸/画面变化清相应视觉状态 |

来源：[ChatViewModel.kt:37–93](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L37)、[CompanionSession.kt:5–40](../core/src/main/kotlin/cn/xingban/core/CompanionSession.kt#L5)、[AppStore.kt:30–44](../app/src/main/java/cn/xingban/app/data/AppStore.kt#L30)、[ReadOnlyTools.kt:41–62](../app/src/main/java/cn/xingban/app/integrations/ReadOnlyTools.kt#L41)、[SpeechRuntime.kt:185–215](../app/src/main/java/cn/xingban/app/integrations/SpeechRuntime.kt#L185)、[PhoneRuntime.kt:59–95](../app/src/main/java/cn/xingban/app/integrations/PhoneRuntime.kt#L59)。

## 聊天回合与上下文

[查看聊天回合图](architecture/chat-turn.svg)

1. 文字或 ASR 最终结果进入同一个 send。当前整句手机命令先分流；STOP/安静随后本地处理；其余非空文本统一 CHAT，再检查所选服务 Key。ASR 中间结果只预览。
2. 新聊天先停止旧回合，捕获服务/凭据/会话路由、活动修订和记忆修订。上下文装配完成后，beginTurn 产生新 token，持久化用户完整原文 pending、心情事件和本轮账本，再发网络请求。
3. IO 流回到主线程，只有 requestOwner、同一 session/current token、前台同时成立，才接受 delta。流式先显示首段，可按句送入显式开启的 TTS；这不等于整轮已提交完成。
4. 最终完成必须同时满足 SSE [DONE]、finish_reason=stop、唯一 JSON 根对象闭合、reply 闭合且非空。合法记忆候选还需匹配 memoryRevision；活动还需匹配 activityRevision。首段、回合状态、合规记忆与活动组成一个快照提交。
5. 同一回合最多三个自然段，后续段落相隔 700ms，**先持久化再发布/朗读**。完成后才按条件整理摘要。摘要失败保留已保存聊天和旧摘要，不重发整轮。

来源：[ChatViewModel.kt:173–327](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L173)、[IncrementalReplyJson.kt:160–204](../app/src/main/java/cn/xingban/app/integrations/IncrementalReplyJson.kt#L160)、[DialogueClient.kt:179–207](../app/src/main/java/cn/xingban/app/integrations/DialogueClient.kt#L179)。

上下文预算的命名为 PromptBudget，但当前估计器是 **UTF-8 字节数加固定开销**，不能当成服务商实际 Token。默认总预算 6000、预留回复 1200。实现先保障 system 与当前输入，再按余量选最近最多四个完整回合、相关 ACTIVE 记忆及一个有效摘要；本地轻问可作为一次性助手上下文。完整本地原文保留，只有实际发送的当前输入被裁剪才提示。失败、中断和不匹配 turnId 的历史不能组成下一轮完整回合。[PromptAssembler.kt:12–94](../core/src/main/kotlin/cn/xingban/core/PromptAssembler.kt#L12)、[ContextMaintenance.kt:14–41](../core/src/main/kotlin/cn/xingban/core/ContextMaintenance.kt#L14)

滚动摘要保留 sourceIds 和 contextBoundaryMillis。每新增八个完整回合才具备尝试资格，最近四个回合不压入新摘要；首次还需至少八个可摘要旧回合。摘要正文上限 1200 UTF-8 字节、输入上限 24KiB；尝试位置先保存，结果再核 owner、会话、记忆修订、上下文边界和来源完整性。摘要是可能出错的派生资料，不能反向成为新的用户事实。[ContextMaintenance.kt:43–63](../core/src/main/kotlin/cn/xingban/core/ContextMaintenance.kt#L43)、[ChatViewModel.kt:419–447](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L419)

停止、切页或进入后台会失效 session 和 owner、取消 Job、停止识别/播报并清瞬时 UI；pending 用户消息和已显示的未完成回复可保存为 interrupted。启动恢复将旧 pending 转为 interrupted，**不自动续发请求**。格式/网络/容量失败保留能保存的中断内容，不提交本轮记忆；首段已提交后若后续保存失败，则保留已提交事实并提示。持久化失败不会被描述为保存成功。[ChatViewModel.kt:65–90,120–169](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L65)

## 只读时间和搜索

[查看只读工具图](architecture/read-only-tools.svg)

普通聊天直接生成最终回复。当前时间问题或当前用户明确搜索意图才进入一轮工具规划；工具只有 get_device_time 和 web_search。规划可以使用当前聊天服务，但搜索需要独立的 DeepSeek 官方 Key。

| 门 | 当前实现 |
| --- | --- |
| 规划完整性 | SSE 完整结束；工具名称、唯一 ID、函数类型和参数全部先校验 |
| 执行范围 | 最多两工具、一次搜索；时间不接受参数；搜索只接受 query，1–128 Unicode 字符 |
| 意图约束 | 没有搜索意图不能调用搜索；明确搜索却没得到实际搜索请求则明确结束，不假装联网 |
| 搜索预算 | 30 秒、256KiB，最多三条有效 https 来源；没有有效来源则失败 |
| 最终生成 | 工具资料标为不可信，按 4800 字节代理预算装配；最终请求不再提供 tools |
| 搜索后发布 | 整体缓冲最终台词，完整性通过且 URL 全属实际返回集合后才显示；附实际查询时间和来源 |

工具返回和网页内容不能增加权限、产生手机动作或成为用户记忆来源。原始规划推理不显示、朗读或持久化。协议、权限意图或预算失败都明确返回，没有自动重试。[ReadOnlyTools.kt:86–149,157–289](../app/src/main/java/cn/xingban/app/integrations/ReadOnlyTools.kt#L86)、[DialogueClient.kt:59–80,105–115](../app/src/main/java/cn/xingban/app/integrations/DialogueClient.kt#L59)

实际 usage 按请求去重记录，CHAT 与 SUMMARY 分列；搜索成功/计费未确认计数单列。官方参考费用和 Go 返回的 cost/billing_source 分开，缺失字段保持未确认。取消后已观察到的用量仍尝试并入最新快照；保存失败显示本机账本未持久化，不据此声称账单为零。[ChatViewModel.kt:393–416](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L393)、[ContextMaintenance.kt:68–122](../core/src/main/kotlin/cn/xingban/core/ContextMaintenance.kt#L68)

## 手机动作、QQ 与媒体生命周期

[查看手机与音频图](architecture/phone-audio.svg)

手机动作只有当前用户的有限整句命令或明确 UI 操作入口。支持 QQ 播放/暂停/上一首/下一首、打开 QQ、绝对音量 0–100%，以及明确时分的系统闹钟设置请求。引用、否定、问句及复合动作不被解析为这些整句命令。模型 JSON、网页、记忆和历史没有到 PhoneRuntime 的执行通道。[DeviceCommands.kt:5–41](../core/src/main/kotlin/cn/xingban/core/DeviceCommands.kt#L5)、[ChatViewModel.kt:173–197,332–374](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L173)

QQ 通过 Android MediaSession 集成，不抓取通知正文。用户在系统设置中自行授予通知访问；运行时还检查安装、listener 连接、固定包 com.tencent.qqmusic 的活跃会话以及对应 action。曲名、播放状态和可选队列只来自该会话，队列缺失不能虚构，最多展示 50 项。[PhoneRuntime.kt:155–229,294–321](../app/src/main/java/cn/xingban/app/integrations/PhoneRuntime.kt#L155)

音量仅接受支持绝对控制且 maxVolume>0 的会话。LOCAL_SHARED 明确表示“手机播放音量（系统共享）”；REMOTE_OUTPUT 表示“播放设备音量”。文字命令解析时或 UI 操作时捕获 expectedScope，执行前重读实际 scope；缺失或改变即拒绝，不能悄悄改成另一个目标。[PhoneRuntime.kt:98–138,302–311](../app/src/main/java/cn/xingban/app/integrations/PhoneRuntime.kt#L98)

打开 QQ 和闹钟移交前，先保存“准备……”的将来时本地回应，持久化失败不执行。平台返回后再保存实际结果。闹钟使用 ACTION_SET_ALARM、SKIP_UI=false；这不保证系统一定出现确认页或已经创建闹钟，只报告已提交请求，结果需在时钟中确认。[ChatViewModel.kt:339–374](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L339)、[PhoneRuntime.kt:269–289](../app/src/main/java/cn/xingban/app/integrations/PhoneRuntime.kt#L269)

| 资源 | 当前所有者和边界 |
| --- | --- |
| ASR | SpeechRuntime 检查平台服务和麦克风权限；启动前 stop 旧回合，听说互斥；最终文字回到 send |
| TTS | 默认 off；用户显式选 system 或 minimax。MiniMax 还需 Key 与音色；缺失只显示文字。按句单队列，最多 32 句，每句最多 4000 字符 |
| 本地背景音乐 | SpeechRuntime 自有播放器与焦点；仅前台、local 来源、启用且无听说/排队/焦点阻断时恢复 |
| QQ 音乐 | PhoneRuntime 观察/提交控制请求，QQ 自己拥有播放。选择 qq 会释放星伴本地音乐播放器；星伴后台解绑观察不等于暂停 QQ |
| 后台与销毁 | MainActivity.onStop 调 background：停星伴回合、识别和播报并解除 QQ 监听；onCleared 调 phone.close / speech.release |

来源：[SpeechRuntime.kt:126–215,242–274,396–444](../app/src/main/java/cn/xingban/app/integrations/SpeechRuntime.kt#L126)、[ChatViewModel.kt:120–128,380–388,459–475,551](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L120)、[MainActivity.kt:59–60](../app/src/main/java/cn/xingban/app/MainActivity.kt#L59)。

## 真实持久化结构与记忆来源

[查看数据与记忆图](architecture/storage-memory.svg)

Room 数据库名为 xingban.db，schema version=1，exportSchema=false。**唯一业务表是 companion_snapshot**；Room/SQLite 内部元数据不在此业务结构图中展开。应用始终读取并替换 id=1。

| Room 实体字段 | Kotlin 类型 | 作用 |
| --- | --- | --- |
| id | Int，主键，默认 1 | 唯一业务快照槽位 |
| payload | String | StateCodec 编码的完整 AppData JSON |

来源：[AppStore.kt:30–44](../app/src/main/java/cn/xingban/app/data/AppStore.kt#L30)。消息、记忆和摘要是 JSON 内的逻辑数据，**不存在独立消息表、记忆表、摘要表或这些表之间的外键**。

| payload 逻辑数据 | 当前主要内容 |
| --- | --- |
| format / version | xingban-backup / 1，同一编解码用于存库与用户备份 |
| messages | id、role、text、time、status、可选 turnId；状态 completed/pending/interrupted/failed/local |
| character | 三个心情量、更新时间/互动时间、触摸时间、已应用事件 ID |
| memory | revision、boundary、suppressed 来源集合、items；每项含 key/text/sources/状态/更正关系 |
| settings | 服务和请求模型配置、语音与音色、称呼和人设参数、本地音乐及 musicSource；无 Key |
| activity / reunion / departedAtMillis | 陪伴活动类型/阶段/模式/修订、重逢记录与离开时间 |
| summary / summaryAttemptTurns | 派生摘要、sourceIds、上下文边界和摘要尝试位置 |
| usage | 当日和本轮 CHAT/SUMMARY 汇总、有限去重 ID、搜索计数及未知项 |

完整字段及解码约束见 [StateCodec.kt:87–156](../app/src/main/java/cn/xingban/app/data/StateCodec.kt#L87)。容量是实现约束：整个备份最多 8MiB、消息最多 20000 条、单条文本最多 100000 字符、记忆最多 10000 项；达到上限报错，不自动清理历史。

当前模型候选提交链只创建 USER_MESSAGE 来源；用户更正创建 USER_CONFIRMATION。APP_EVENT 是已定义的来源枚举，不能据此宣称已经存在通用事件自动记忆通道。每条记忆保存来源 ID、类型、原文、绝对时间和 revision。提交检查本轮记忆修订、抑制集合、上下文边界、候选数量及原文完整句证据；同 key 同文去重，同 key 冲突等待用户更正。[Memory.kt:6–44,119–202](../core/src/main/kotlin/cn/xingban/core/Memory.kt#L6)、[ChatViewModel.kt:260–272,521–529](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L260)

语法校验可以防止从句内任意截断，却不能独立判断一整句是否是假设、转述或值得长期记忆；这部分仍依赖模型语义判断，应允许用户更正。检索只选 ACTIVE 且来源未抑制的记忆，按本地词项/中文双字匹配与更新时间排序，默认最多六条；当前没有向量数据库。[Memory.kt:172–209](../core/src/main/kotlin/cn/xingban/core/Memory.kt#L172)

更正或忘记会先 stop，推进 memoryRevision、抑制旧来源并更新上下文边界，同时清摘要和尝试位置；之后旧上下文不再参与未来回忆。**原聊天和旧备份仍保留**，不能把“退出未来回忆”说成所有副本永久删除。[Memory.kt:145–169](../core/src/main/kotlin/cn/xingban/core/Memory.kt#L145)、[ChatViewModel.kt:521–529](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L521)

## 凭据与用户备份

Key 不属于 AppData。SecretStore 使用 AndroidKeyStore 中的 AES-GCM 密钥加密，把 IV 与密文写入应用私有 preferences；不把 Key 编入 Room JSON 或用户备份。Manifest 禁用系统自动备份并禁止明文 HTTP。[SecretStore.kt:13–37](../app/src/main/java/cn/xingban/app/data/SecretStore.kt#L13)、[AndroidManifest.xml:3–15](../app/src/main/AndroidManifest.xml#L3)

导出通过 Mutex 一致读取当前 AppData，再由用户文件选择器写出 JSON。文件包含用户聊天和记忆，应由用户保管，不能随公开仓库发布。恢复先完整 decode 校验；成功后才 stop 并提交新快照：pending 转 interrupted，清离开时间和摘要，保留**本机已经发生的 usage**，重建 Session 并重配音乐。Key 不导入，继续使用本机凭据。无效、过大或不支持的文件不清空原状态。[MainActivity.kt:22–47](../app/src/main/java/cn/xingban/app/MainActivity.kt#L22)、[ChatViewModel.kt:535–549](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L535)

## 人物、活动与当前显示能力

心情由 CharacterReducer 根据明确事件更新，含事件 ID 去重、时间衰减和 2.5 秒业务触摸冷却。陪伴活动有 AUTONOMOUS/EXPLICIT 模式及 IDLE/ACTIVE/PAUSED/AWAITING_CONTINUE 阶段；长离开后可等待用户确认继续。它们是本地角色状态，不证明应用实际看见视频、听见外部音乐或执行了手机动作。[Character.kt:52–91](../core/src/main/kotlin/cn/xingban/core/Character.kt#L52)、[Behavior.kt:9–54](../core/src/main/kotlin/cn/xingban/core/Behavior.kt#L9)

当前六图按 CompanionActivity 自动投影；用户手选只覆盖 UI，选择跨页面/可恢复重建保存，不改变活动，也不因此发送或停止对话。自动场景轮换需前台、主页、空闲且无人为选图，180 秒；本地轻问需合规空闲 20 分钟，不调用后台模型。重逢基于保存的离开时间作本地处理，不运行常驻监听或推送。[CompanionApp.kt:53–89](../app/src/main/java/cn/xingban/app/ui/CompanionApp.kt#L53)、[Companion2DAvatar.kt:60–115](../app/src/main/java/cn/xingban/app/ui/Companion2DAvatar.kt#L60)、[ChatViewModel.kt:97–155](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L97)

当前视觉是单张 2D 的前台缩放呼吸；有效触点每次重启 520ms 光圈，短句及业务 onTouch 仍有 650ms 节流，且与心情 2.5 秒冷却不同。换图、尺寸或前后台变化会丢弃旧触点像素。**Live2D、3D、音素级口型不是当前能力**。[Companion2DAvatar.kt:156–243](../app/src/main/java/cn/xingban/app/ui/Companion2DAvatar.kt#L156)

## 取舍、未知与验证范围

**已记录的设计选择**：单用户、单设备、本地优先；两个业务模块；显式构造组合；由真正外部边界隔离网络、平台资源和持久化。当前依赖与构造链仍与这些选择一致。单行快照作为消息/心情/记忆版本的事务边界，也有源码注释明确说明。[ChatViewModel.kt:37–45](../app/src/main/java/cn/xingban/app/ChatViewModel.kt#L37)、[AppStore.kt:40–44](../app/src/main/java/cn/xingban/app/data/AppStore.kt#L40)

**据实现可推断的效果与代价**：统一快照和 Mutex 降低分散提交造成状态不一致的机会；代价是整份 JSON 编解码与明确容量上限。请求代际和修订比较帮助拒绝迟到结果，但不能代替真实设备并发/生命周期验收。这些是实现分析，不能倒推为当初全部选型动机。未记录的原始动机为 **UNKNOWN**。

| 层级 | 当前可支持的结论 |
| --- | --- |
| 本轮源码与文档 | 定向读取入口和直接链，维护图源、离线 HTML、SVG/PNG；未改业务源码或运行 API |
| 本轮图产物 | Archify 结构/严格产物/真实 Chrome 自动检查；静态导出与图形查看另外记录于维护说明 |
| 项目既有运行记录 | r17 记录为 63 项聚焦 JVM、6 个隔离模拟器方法、3 个真实 App 链路样本；本轮未复跑，不等于全面验收 |
| 未验证 | 真实 QQ 歌曲与队列、真机、Release、长期体验与供应商实际运行模型 |
| 当前未接入 | Live2D、3D、音素级口型、自建账号/服务器、云端记忆同步 |

图形检查不能证明业务、真实 QQ、设备、语音质量或 Release 通过。维护规则、图源对应关系和本轮产物收据见 [architecture/maintenance.md](architecture/maintenance.md)。
