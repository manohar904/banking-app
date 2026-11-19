package com.bank.account.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StatementCsvTest {

    @Test
    void amountsArePlainNumbersAndEmptyCellsStayEmpty() {
        assertThat(StatementCsv.cell(new BigDecimal("1234567.50"))).isEqualTo("1234567.50");
        assertThat(StatementCsv.cell(new BigDecimal("5"))).isEqualTo("5.00");
        assertThat(StatementCsv.cell(null)).isEmpty();
    }

    @Test
    void textWithCommasQuotesOrLineBreaksIsQuoted() {
        assertThat(StatementCsv.cell("Smith, Jones")).isEqualTo("\"Smith, Jones\"");
        assertThat(StatementCsv.cell("The \"best\" shop")).isEqualTo("\"The \"\"best\"\" shop\"");
        assertThat(StatementCsv.cell("two\nlines")).isEqualTo("\"two\nlines\"");
    }

    @Test
    void textASpreadsheetWouldRunAsAFormulaIsKeptAsText() {
        assertThat(StatementCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(StatementCsv.cell("+1")).isEqualTo("'+1");
        assertThat(StatementCsv.cell("-1")).isEqualTo("'-1");
        assertThat(StatementCsv.cell("@sum")).isEqualTo("'@sum");
        assertThat(StatementCsv.cell("Deposit")).isEqualTo("Deposit");
    }

    @Test
    void theFileStartsWithAByteOrderMarkAndEndsLinesWithCrlf() {
        byte[] bytes = new StatementCsv("A", "B").row("x", new BigDecimal("1.00")).toBytes();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("﻿A,B\r\nx,1.00\r\n");
    }
}
