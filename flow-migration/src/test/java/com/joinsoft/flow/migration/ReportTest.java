package com.joinsoft.flow.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告：阶段汇总、告警截断统计、CSV 结构。
 */
class ReportTest {

    @Test
    void csvContainsSummaryAndWarnings(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, "UTF-8");
        Report report = new Report(out, 1);
        report.stage("flow_def", 10, 9, 1);
        report.stage("flow_inst", 4, 4, 0);
        report.warn("flow_def", "d1", "问题一", "详情一");
        report.warn("flow_def", "d2", "问题二", "详情二");

        assertEquals(1, report.warnings());
        assertEquals(1, report.suppressedWarnings());
        assertEquals(2, report.totalWarnings());

        Path file = dir.resolve("report.csv");
        report.writeCsv(file.toString());
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertTrue(text.contains("阶段汇总"), text);
        assertTrue(text.contains("flow_def,10,9,1"), text);
        assertTrue(text.contains("flow_inst,4,4,0"), text);
        assertTrue(text.contains("合计,14,13,1"), text);
        assertTrue(text.contains("告警数,2"), text);
        assertTrue(text.contains("问题一"), text);
        assertFalse(text.contains("问题二"), "超出 --max-warnings 的告警只统计不落明细");
        assertTrue(text.contains("截断"), text);
    }

    @Test
    void csvEscapesCommaAndNewline(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, "UTF-8");
        Report report = new Report(out, 10);
        report.warn("flow_inst", "i,1", "多行\n问题", "详情,含逗号");

        Path file = dir.resolve("quoted.csv");
        report.writeCsv(file.toString());
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertTrue(text.contains("\"i,1\""), text);
        assertTrue(text.contains("多行 问题"), text);
        assertTrue(text.contains("\"详情,含逗号\""), text);
    }
}