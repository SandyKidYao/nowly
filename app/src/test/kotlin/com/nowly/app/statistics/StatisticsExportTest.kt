package com.nowly.app.statistics

import com.nowly.app.data.local.SampleEntity
import com.nowly.app.data.local.SampleStatus
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.RecordProject
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatisticsExportTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val project = RecordProject("p", "心情", "此刻，你的心情怎么样？", true, 0, true,
        listOf(AnswerOption("bad", "不好", 0), AnswerOption("ok", "还行, 一般", 1), AnswerOption("good", "很好", 2), AnswerOption("gone", "旧选项", 3, active = false)),
        listOf(600, 1200))

    private fun at(date: String, hour: Int, minute: Int = 0) = LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun answer(id: String, date: String, hour: Int, option: String) =
        SampleEntity(id, "p", at(date, hour), at(date, hour, 5), SampleStatus.ANSWERED, option, at(date, hour, 5), date, hour, zone.id, at(date, hour, 5), zone.id)

    private val records = listOf(
        answer("b", "2026-10-15", 12, "good"),
        answer("a", "2026-10-14", 10, "ok"),
        SampleEntity("s", "p", at("2026-10-15", 10), null, SampleStatus.SKIPPED, null, null, "2026-10-15", 10, zone.id, at("2026-10-15", 10)),
        answer("c", "2026-10-15", 20, "gone"),
    )

    @Test fun csvHasOneRowPerPromptOldestFirstWithLevelsAndQuoting() {
        val lines = StatisticsExport.csv(project, records).trimEnd().split("\r\n")
        assertEquals("日期,时间,星期,状态,选项,等级,提醒时间,时区", lines[0])
        assertEquals("2026-10-14,10:05,周三,已回答,\"还行, 一般\",2,10:00,Asia/Shanghai", lines[1])
        assertEquals("2026-10-15,10:00,周四,已跳过,,,10:00,Asia/Shanghai", lines[2])
        assertEquals("2026-10-15,12:05,周四,已回答,很好,3,12:00,Asia/Shanghai", lines[3])
        assertEquals("2026-10-15,20:05,周四,已回答,旧选项,,20:00,Asia/Shanghai", lines[4])
        assertEquals(5, lines.size)
    }

    @Test fun promptDescribesScaleSummaryDailyMeansAndRawRows() {
        val text = StatisticsExport.prompt(project, records, "2026-10-14", "2026-10-15", 1.5, zone.id)
        assertTrue(text.contains("问题：此刻，你的心情怎么样？"))
        assertTrue(text.contains("1 = 不好，2 = 还行, 一般，3 = 很好"))
        assertTrue(text.contains("已停用的旧选项（没有等级，不计入平均）：旧选项"))
        assertTrue(text.contains("每日提醒时间：10:00、20:00"))
        assertTrue(text.contains("回答 3 次 · 跳过 1 次 · 未处理 0 次 · 待处理 0 次；回答率 75%"))
        assertTrue(text.contains("平均等级 2.5 / 3，接近「很好」；前 2 天平均 1.5"))
        assertTrue(text.contains("2026-10-14,周三,1,2.0"))
        assertTrue(text.contains("2026-10-15,周四,2,3.0"))
        assertTrue(text.contains("2026-10-15,周四,10:00,已跳过,,"))
        assertTrue(text.contains("2026-10-14,周三,10:05,已回答,2,\"还行, 一般\""))
        assertTrue(text.contains("不要做医学或心理诊断"))
    }

    @Test fun promptDropsRawRowsWhenTooLong() {
        val many = (0..StatisticsExport.PROMPT_ROW_LIMIT).map { answer("x$it", "2026-10-15", 10, "ok") }
        val text = StatisticsExport.prompt(project, many, "2026-10-15", "2026-10-15", null, zone.id)
        assertTrue(text.contains("这里省略逐条记录"))
        assertTrue(text.contains("前 1 天没有可比较的回答"))
        assertFalse(text.contains("日期,星期,时间,状态,等级,选项"))
    }

    @Test fun fileNameStripsCharactersFilesCannotHold() {
        assertEquals("nowly-a_b_c-2026-10-01-2026-10-07.csv", StatisticsExport.fileName(project.copy(name = "a/b c"), "2026-10-01", "2026-10-07"))
    }
}
