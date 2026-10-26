package cn.xingban.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorAndPromptTest {
    @Test fun wallClockIsTransientBudgetedControlContextRatherThanAUserFact() {
        val memories = MemoryState()
        val clock = "2026-10-26T13:30:00+08:00[Asia/Shanghai]"
        val context = PromptAssembler.assemble(CharacterProfile.DEFAULT, CharacterState(), memories, emptyList(), "现在几点", currentTime = clock)
        assertTrue(context.messages.first().content.contains(clock)); assertTrue(context.messages.first().content.contains("不是用户事实"))
        assertTrue(context.estimatedInputTokens <= 4800); assertEquals("现在几点", context.messages.last().content)
        assertTrue(memories.items.isEmpty()); assertTrue(context.usedMemoryIds.isEmpty())
    }
    @Test fun activityLifecycleKeepsSemanticOwnerAndQuietBoundaries() {
        val initial = CompanionActivity()
        val reading = initial.apply(ActivityCommand(ActivityAction.START, ActivityType.READ))
        assertTrue(reading.quiet); assertEquals(1L, reading.revision)
        assertEquals(reading, reading.rotate())
        assertEquals(reading, reading.apply(ActivityCommand(ActivityAction.START, ActivityType.WATCH)))
        val paused = reading.apply(ActivityCommand(ActivityAction.PAUSE))
        assertEquals(ActivityPhase.PAUSED, paused.phase); assertEquals(ActivityType.READ, paused.type)
        val resumed = paused.apply(ActivityCommand(ActivityAction.CONTINUE))
        assertEquals(ActivityPhase.ACTIVE, resumed.phase)
        val chat = resumed.apply(ActivityCommand(ActivityAction.SWITCH, ActivityType.CHAT))
        assertFalse(chat.quiet)
        assertTrue(chat.localStop(true).quiet)
        val done = chat.apply(ActivityCommand(ActivityAction.FINISH))
        assertEquals(ActivityMode.AUTONOMOUS, done.mode); assertNull(done.type)
        assertEquals(1, done.rotate().autonomousScene)
        assertEquals(reading, reading.returnedAfter(86_399_999L))
        assertEquals(ActivityPhase.AWAITING_CONTINUE, reading.returnedAfter(86_400_000L).phase)
    }

    @Test fun reunionUsesElapsedAbsenceInsteadOfCalendarOrInteractionTime() {
        val a = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.READ))
        assertNull(CompanionReunion.elapsed(null, 100))
        assertNull(CompanionReunion.elapsed(101, 100))
        assertEquals(2_000L, CompanionReunion.elapsed(86_399_000L, 86_401_000L))
        assertNull(CompanionReunion.reply(2_000L, a))
        assertNull(CompanionReunion.reply(21_599_999L, a))
        assertTrue(CompanionReunion.reply(21_600_000L, a)!!.contains("忙完"))
        assertTrue(CompanionReunion.reply(86_400_000L, a.returnedAfter(86_400_000L))!!.contains("要继续吗"))
        assertTrue(CompanionReunion.reply(259_200_000L, a)!!.startsWith("哼"))
    }

    @Test fun localQuestionIsOnlyOneTurnAssistantContextAndActivityIsExplicit() {
        val question = "还在忙呀？要不要和我说两句。"
        val context = PromptAssembler.assemble(CharacterProfile.DEFAULT, CharacterState(), MemoryState(), emptyList(), "在忙呢", activity = CompanionActivity().apply(ActivityCommand(ActivityAction.START, ActivityType.WORK)), localQuestion = question)
        assertEquals(PromptMessage(PromptRole.ASSISTANT, question), context.messages[context.messages.lastIndex - 1])
        assertEquals("在忙呢", context.messages.last().content)
        assertTrue(context.messages.first().content.contains("WORK")); assertTrue(context.usedMemoryIds.isEmpty())
    }
    @Test fun stopAndLocalGatesStayExplicitWhileFreeTextUsesChat() {
        val stopped = BehaviorSelector.select(BehaviorInput(userText = "别说了", touch = TouchArea.CHEEK, reunionDue = true))
        assertEquals(BehaviorKind.STOP, stopped.kind)
        assertFalse(stopped.maySpeak)
        for (text in listOf("哈哈，其实我很难过", "我不难过，刚才是逗你的哈哈", "他说‘压力很大’，不是在说我", "你今天挺好看的", "‘别说了’这个词是什么意思")) {
            val chat = BehaviorSelector.select(BehaviorInput(userText = text, touch = TouchArea.HEAD, reunionDue = true))
            assertEquals(BehaviorKind.CHAT, chat.kind); assertEquals(Expression.NEUTRAL, chat.expression)
            assertTrue(chat.maySpeak)
        }
        assertEquals(BehaviorKind.STOP, BehaviorSelector.select(BehaviorInput(userText = "我不难过", stopRequested = true)).kind)
        val occupied = BehaviorSelector.select(BehaviorInput(isListening = true, touch = TouchArea.HEAD, reunionDue = true))
        assertEquals(BehaviorKind.TOUCH, occupied.kind)
        assertFalse(occupied.maySpeak)
        assertNull(occupied.localReply)
        assertEquals(BehaviorKind.NONE, BehaviorSelector.select(BehaviorInput(reunionDue = true, triggerAlreadyHandled = true)).kind)
    }

    @Test fun expressionSuggestionsAreFiniteWhitelistedData() {
        assertNull(ModelOutputRules.expression("execute_file", 0.5))
        assertNull(ModelOutputRules.expression("HAPPY", Double.NaN))
        assertNull(ModelOutputRules.expression("HAPPY", 1.1))
        assertEquals(Expression.HAPPY, ModelOutputRules.expression("happy", 0.6)!!.expression)
    }

    @Test fun sourcedFactsAndUncertainSummariesDoNotBecomeSystemInstructions() {
        val source = MemorySource("message-1", MemorySourceKind.USER_MESSAGE, 100, "记住：我喜欢咖啡")
        val state = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single()).state
        val context = PromptAssembler.assemble(
            CharacterProfile.DEFAULT, CharacterState(), state, listOf(ChatMessage(source.id, ChatRole.USER, source.text, 100), ChatMessage("a1", ChatRole.ASSISTANT, "好呀", 101)), "咖啡",
            summaries = listOf(ConversationSummary("猜测我们一起旅行过", setOf("message-1", "a1"))),
        )
        val system = context.messages.single { it.role == PromptRole.SYSTEM }.content
        assertTrue(system.contains("reply字段只放自然台词"))
        assertTrue(system.contains("外层响应遵从App规定JSON结构"))
        assertTrue(system.contains("metadata不属于台词，不得朗读"))
        assertTrue(system.contains("接住用户本句与上轮话题"))
        assertTrue(system.contains("接受用户更正"))
        assertTrue(system.contains("不每轮追问或列建议，不机械撒娇"))
        assertFalse(system.contains("我喜欢咖啡"))
        assertFalse(system.contains("一起旅行过"))
        val evidence = context.messages.filter { it.role == PromptRole.USER }.joinToString { it.content }
        assertTrue(evidence.contains("用户明确记忆"))
        assertTrue(evidence.contains("message-1"))
        assertTrue(evidence.contains("派生摘要（可能有误"))
    }

    @Test fun forgottenSourcesInvalidateSummaryAndHistory() {
        val source = MemorySource("message-secret", MemorySourceKind.USER_MESSAGE, 100, "记住：秘密暗号蓝鲸")
        val saved = MemoryRules.commit(MemoryState(), MemoryRules.extract(source, 0).single())
        val forgotten = MemoryRules.forget(saved.state, saved.item!!.id, 200).state
        val context = PromptAssembler.assemble(
            CharacterProfile.DEFAULT, CharacterState(), forgotten,
            listOf(
                ChatMessage(source.id, ChatRole.USER, source.text, 100),
                ChatMessage("reply", ChatRole.ASSISTANT, "暗号蓝鲸", 101),
                ChatMessage("repeat", ChatRole.USER, "暗号蓝鲸要记住", 150),
                ChatMessage("new-user", ChatRole.USER, "今天天气好吗", 201),
                ChatMessage("new-reply", ChatRole.ASSISTANT, "我们聊点轻松的吧", 202),
            ),
            "你好", summaries = listOf(
                ConversationSummary("暗号蓝鲸", setOf(source.id)),
                ConversationSummary("重复记录暗号蓝鲸", setOf("repeat")),
                ConversationSummary("猜测旧事暗号蓝鲸", setOf("unresolved-source")),
                ConversationSummary("最近在谈天气", setOf("new-user", "new-reply"), forgotten.contextBoundaryMillis),
            ),
        )
        assertTrue(context.messages.none { it.content.contains("蓝鲸") })
        assertTrue(context.usedMemoryIds.isEmpty())
        assertTrue(context.messages.any { it.role == PromptRole.ASSISTANT && it.content == "我们聊点轻松的吧" })
        assertTrue(context.messages.any { it.content.contains("最近在谈天气") })
    }

    @Test fun contextHasAnExplicitConservativeBudget() {
        val budget = PromptBudget(maxTokens = 4000, reservedReplyTokens = 500)
        val context = PromptAssembler.assemble(CharacterProfile.DEFAULT, CharacterState(), MemoryState(), emptyList(), "长输入🙂".repeat(5_000), budget = budget)
        assertTrue(context.truncated)
        assertTrue(context.estimatedInputTokens <= budget.maxTokens - budget.reservedReplyTokens)
        assertEquals(PromptRole.USER, context.messages.last().role)
        assertFalse(context.messages.last().content.any { it == '\uFFFD' })
    }

    @Test fun wholeTurnsKeepEveryAssistantAndSkipOversizedNewestWithoutLosingEarlierTurns() {
        val six = (1..6).flatMap { n -> listOf(ChatMessage("u$n", ChatRole.USER, "question$n", n * 10L), ChatMessage("a$n", ChatRole.ASSISTANT, "answer$n", n * 10L + 1, "u$n")) }
        val bounded = PromptAssembler.assemble(CharacterProfile.DEFAULT, CharacterState(), MemoryState(), six, "继续")
        assertEquals((3..6).flatMap { listOf("question$it", "answer$it") }, bounded.messages.drop(1).dropLast(1).map { it.content })
        assertTrue(bounded.truncated); assertTrue(bounded.estimatedInputTokens <= 4800)
        val history = listOf(
            ChatMessage("u", ChatRole.USER, "可容纳的问题", 1),
            ChatMessage("a1", ChatRole.ASSISTANT, "第一段", 2, "u"),
            ChatMessage("a2", ChatRole.ASSISTANT, "第二段", 3, "u"),
            ChatMessage("big", ChatRole.USER, "长".repeat(5000), 4),
            ChatMessage("big-a", ChatRole.ASSISTANT, "超长轮的回复", 5, "big"),
            ChatMessage("bad", ChatRole.USER, "失败轮", 6, status = "failed"),
            ChatMessage("bad-a", ChatRole.ASSISTANT, "不得带入", 7, "bad"),
        )
        val context = PromptAssembler.assemble(CharacterProfile.DEFAULT, CharacterState(), MemoryState(), history, "继续")
        assertTrue(context.truncated); assertTrue(context.estimatedInputTokens <= 4800)
        assertTrue(context.messages.any { it.content == "第一段" }); assertTrue(context.messages.any { it.content == "第二段" })
        assertTrue(context.messages.none { it.content.contains("超长轮") || it.content.contains("失败轮") || it.content == "不得带入" })
        assertEquals(listOf("u"), ConversationHistory.completeTurns(history.take(3), MemoryState()).map { it.user.id })
    }

    @Test fun rollingSummaryUsesCompleteBoundedSourcesAndAttemptsOnlyAfterEightMoreTurns() {
        val history = (1..20).flatMap { n -> listOf(ChatMessage("u$n", ChatRole.USER, if (n == 2) "长".repeat(10000) else "虚构故事第${n}幕", n * 10L), ChatMessage("a$n", ChatRole.ASSISTANT, "故事回复$n", n * 10L + 1, "u$n")) }
        assertNull(ConversationHistory.summaryBatch(history.take(22), MemoryState(), null, 0))
        val first = ConversationHistory.summaryBatch(history.take(24), MemoryState(), null, 0)!!
        assertEquals(12, first.completedTurns)
        assertFalse("u2" in first.sourceIds); assertFalse("a2" in first.sourceIds)
        assertTrue(first.messages.sumOf { it.content.toByteArray(Charsets.UTF_8).size + 8 } <= 24 * 1024)
        val old = ConversationSummary("虚构故事：约定在蓝门相见", first.sourceIds)
        assertTrue(ConversationHistory.validSummary(old, MemoryState(), history))
        assertNull(ConversationHistory.summaryBatch(history.take(38), MemoryState(), old, 12))
        val next = ConversationHistory.summaryBatch(history, MemoryState(), old, 12)!!
        assertTrue(next.sourceIds.containsAll(first.sourceIds))
        assertTrue(next.messages.any { it.content.contains("旧派生摘要") })
        assertFalse(ConversationHistory.validSummary(old.copy(sourceIds = setOf("u1")), MemoryState(), history))
        assertFalse(ConversationHistory.validSummary(old.copy(text = "字".repeat(401)), MemoryState(), history))
        assertFalse(ConversationHistory.validSummary(old, MemoryState(contextBoundaryMillis = 999), history))
        assertFalse(ConversationHistory.validSummary(old, MemoryState(), history.filter { it.id != "a1" }))
    }
}
