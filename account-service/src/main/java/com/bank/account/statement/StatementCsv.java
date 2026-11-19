package com.bank.account.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * A statement as a spreadsheet file (CSV) that Excel, Numbers and Google Sheets open as they are: UTF-8
 * with a byte order mark so Excel reads accented names right, commas between cells and CRLF line ends.
 * Amounts are plain numbers with two decimals and no thousands separator, so they can be added up.
 */
public final class StatementCsv {

    public static final String CONTENT_TYPE = "text/csv";

    private static final String BYTE_ORDER_MARK = "﻿";

    private final StringBuilder text = new StringBuilder(BYTE_ORDER_MARK);

    public StatementCsv(String... header) {
        row((Object[]) header);
    }

    /** One line; an amount goes in as a number, anything else as text. A null is an empty cell. */
    public StatementCsv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(cell(cells[i]));
        }
        text.append("\r\n");
        return this;
    }

    public byte[] toBytes() {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal amount) {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        }
        String cell = value.toString();
        // A spreadsheet runs text starting with these as a formula, so it is kept as text.
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            cell = '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }
}
