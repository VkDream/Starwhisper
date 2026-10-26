# 架构图册维护

当前设计入口为 [ARCHITECTURE.md](../ARCHITECTURE.md)，图册入口为 [index.html](index.html)。本页只记录当前图源、维护方法和验证边界；旧轮次流水已保留在本机私有备份，不混入当前公开设计。

## 单一图源

| 当前视图 | 唯一可编辑源 | 生成物 | 节点 / 边 |
| --- | --- | --- | --- |
| 系统架构 | [candidate.json](candidate.json) | [HTML](xingban-architecture.html) · [SVG](xingban-architecture.svg) · [PNG](xingban-architecture.png) | 14 / 18 |
| 聊天回合 | [chat-turn.json](chat-turn.json) | [HTML](chat-turn.html) · [SVG](chat-turn.svg) · [PNG](chat-turn.png) | 11 / 10 |
| 只读时间与搜索 | [read-only-tools.json](read-only-tools.json) | [HTML](read-only-tools.html) · [SVG](read-only-tools.svg) · [PNG](read-only-tools.png) | 9 / 9 |
| 手机与音频 | [phone-audio.json](phone-audio.json) | [HTML](phone-audio.html) · [SVG](phone-audio.svg) · [PNG](phone-audio.png) | 12 / 12 |
| 数据与记忆 | [storage-memory.json](storage-memory.json) | [HTML](storage-memory.html) · [SVG](storage-memory.svg) · [PNG](storage-memory.png) | 10 / 10 |

总览沿用既有 Archify 源、稳定节点 ID 和人工位置/尺寸；根据本轮已确认清理范围移除未接入的旧 3D 节点与相关边，其余布局保持。四张流程图各自只拥有一个 JSON 源，不是第二份系统总览。不要直接改生成 HTML、SVG 或 PNG 代替修改源模型。

总览按职责聚合，线表示输入、控制或状态数据流，不是逐函数调用图。平台执行门、资源释放和持久化顺序由对应流程图及源码说明展开。

## 来源与事实边界

物理源码读取时间：**2026-10-26T01:49:36.387Z**。最终复核中，仍属当前生产树的 22 个已读源码/配置文件与读取字节一致；已获准移出的旧样例不再作为当前节点或公开来源。完整仓库相对路径、函数和真实行号在 [ARCHITECTURE.md](../ARCHITECTURE.md) 逐节给出，图内普通卡片保留简明来源。

本轮使用本机物理源码模式，未执行 Git；没有启用 meta.repository、任意层级 sources 或 --repo-root，不把未提交的工作区字节标为某个提交。后续源码改变时重新核受影响调用/写入位置，更新对应来源与视图，不把旧聊天或历史总结当成当前实现证据。

区分三类陈述：当前源码可证明的实现、由实现可推断的效果与代价、未记录或未验证的 UNKNOWN。尤其保持：

- Room 只有 companion_snapshot 一个业务表，应用使用 id=1；消息、记忆、摘要是 payload 内逻辑数据。
- CompanionSession 只持有回合身份；ViewModel 编排取消与提交；SpeechRuntime / PhoneRuntime 分别拥有星伴音频和 QQ 会话资源。
- 当前为六张 2D 原画、呼吸和触摸；Live2D、3D、音素级口型不是当前能力。
- 搜索和记忆都是资料来源，不增加手机动作权限。QQ 歌曲/队列和系统移交结果不能由模型编造。

## 本轮图产物验证

五图均使用已安装的 **Archify 3.1.0** 运行 finalize；validate、deliver、严格 provenance check、真实 Chrome browser-check 全部通过。每图严格产物检查为 **9 项**，构图错误 **0**、警告 **0**。未安装、升级或替换工具。

浏览器自动测量覆盖 1440×900、1600×1000、1920×1080、2048×1320 的浅色，以及首尾档深色和 READ/Still 状态。没有横向溢出；总览在较小桌面使用查看器声明的整页纵向滚动。自动回执没有截图，不能据此单独宣称视觉验收。

另使用本任务独立 Chrome，按查看器自身的导出菜单生成五组浅色 SVG/PNG；SVG 不含脚本、foreignObject 或外链图像。已实际查看最终五张静态预览、总览深色 HTML 截图，以及导航 1440px / 390px 截图：未见文字、节点、边界或图例裁切。导航无横向溢出，五张卡片及资源链接存在。

总览保留人工布局，自动建议仍记录一处已处理交叉和一条三弯返回线；它们未触发构图错误，目视可沿箭头辨认。这里的视觉结论是当前截图范围内未见裁切和阻读，不是“零交叉”或所有尺寸审美保证。流程图没有交叉建议。

本轮没有运行 App、业务测试、API、ADB、真机或 Release。既有 r17 的 63 项 JVM、6 个隔离模拟器方法、3 个真实 App 链路样本属于已有项目记录，未在此轮复跑。真实 QQ 歌曲/队列、真机、Release 与长期体验仍未验证。

## 增量维护

1. 先核受影响的物理入口、直接调用链和真实读写/执行者，判断是否改变模块、接口、数据/资源所有者、权限或部署边界。
2. 改对应 JSON 的必要节点、线与普通来源卡片。结构未变时保留节点 ID 和人工布局；新行为放进已有相关流程，不另造重复总览。
3. 对改过的源执行 Archify finalize，质量沿用 showcase；已有输出绑定过浏览器证据时，使用新的私有证据目录。不要覆盖旧回执来制造当前验证。
4. 在查看器静态/浅色状态重新导出对应 SVG 和 PNG，再实际查看，确认文字、箭头、边界和图例完整。HTML 自动检查通过不等于静态导出已看过。
5. 同步 ARCHITECTURE.md 的责任、字段、来源行号与验证边界，检查图册相对链接。

## 公开文件与私有证据

本公开页已去除旧机器绝对路径、内部 RUN/合同链接、请求标识、私密设置位置与本机回执链接。公开阅读只依赖本页、ARCHITECTURE.md、index.html、上表五个 JSON 及五组 HTML/SVG/PNG。路径均为仓库相对路径，不依赖本机文件协议链接。

历史图源/维护说明备份、历史来源目录、自动收据、delivery sidecar、进程所有权和浏览器截图继续保留在本机证据范围。它们可能包含机器路径，不应把整个 architecture 目录或本机证据目录无差别公开。旧证据保留不代表仍在当前运行结构中。

Archify 缓存更新提示为 3.1.0 → 3.2.0；本轮继续使用原工具，没有升级。更新信息不影响本次实际图门结果。
