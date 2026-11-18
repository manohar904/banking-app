package com.bank.account.statement;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lays out a statement as a one-column A4 PDF: a title, details about whose statement it is, a summary,
 * then a table of entries. Only the PDF's built-in fonts are used, so nothing has to be installed.
 */
public final class StatementPdf {

    public static final String CONTENT_TYPE = "application/pdf";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
    private static final Color ACCENT = new Color(0x2E, 0x4B, 0xC6);
    private static final Color LIGHT = new Color(0xEE, 0xF1, 0xFB);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, ACCENT);
    private static final Font BANK = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.DARK_GRAY);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
    private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
    private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    /** One table column; numeric ones are right-aligned. */
    public record Column(String title, float width, boolean numeric) {
    }

    private final String title;
    private final List<String[]> details = new ArrayList<>();
    private final List<String[]> summary = new ArrayList<>();
    private String tableTitle = "";
    private List<Column> columns = List.of();
    private final List<String[]> rows = new ArrayList<>();
    private String emptyTable = "";
    private String footnote = "";

    public StatementPdf(String title) {
        this.title = title;
    }

    public StatementPdf detail(String label, String value) {
        details.add(new String[] {label, value});
        return this;
    }

    public StatementPdf summary(String label, String value) {
        summary.add(new String[] {label, value});
        return this;
    }

    public StatementPdf table(String title, String whenEmpty, Column... columns) {
        this.tableTitle = title;
        this.emptyTable = whenEmpty;
        this.columns = List.of(columns);
        return this;
    }

    public StatementPdf row(String... cells) {
        if (cells.length != columns.size()) {
            throw new IllegalArgumentException("Expected " + columns.size() + " cells.");
        }
        rows.add(cells);
        return this;
    }

    public StatementPdf footnote(String text) {
        this.footnote = text;
        return this;
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 42, 42, 42, 42);
        PdfWriter.getInstance(document, out);
        document.addTitle(title);
        document.addCreator("Banking");
        document.open();

        document.add(new Paragraph("Banking", BANK));
        Paragraph heading = new Paragraph(title, TITLE);
        heading.setSpacingAfter(12);
        document.add(heading);

        document.add(pairs(details, false));
        if (!summary.isEmpty()) {
            Paragraph label = new Paragraph("Summary", BOLD);
            label.setSpacingBefore(14);
            label.setSpacingAfter(4);
            document.add(label);
            document.add(pairs(summary, true));
        }

        Paragraph label = new Paragraph(tableTitle, BOLD);
        label.setSpacingBefore(14);
        label.setSpacingAfter(4);
        document.add(label);
        if (rows.isEmpty()) {
            document.add(new Paragraph(emptyTable, TEXT));
        } else {
            document.add(entries());
        }

        if (!footnote.isBlank()) {
            Paragraph note = new Paragraph(footnote, LABEL);
            note.setSpacingBefore(16);
            document.add(note);
        }
        document.close();
        return out.toByteArray();
    }

    private static PdfPTable pairs(List<String[]> pairs, boolean shaded) {
        PdfPTable table = new PdfPTable(new float[] {1.3f, 3f});
        table.setWidthPercentage(100);
        for (String[] pair : pairs) {
            PdfPCell name = cell(pair[0], LABEL, Element.ALIGN_LEFT);
            PdfPCell value = cell(pair[1], shaded ? BOLD : TEXT, Element.ALIGN_LEFT);
            if (shaded) {
                name.setBackgroundColor(LIGHT);
                value.setBackgroundColor(LIGHT);
            }
            table.addCell(name);
            table.addCell(value);
        }
        return table;
    }

    private PdfPTable entries() {
        float[] widths = new float[columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = columns.get(i).width();
        }
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1); // Repeated at the top of each page.
        for (Column column : columns) {
            PdfPCell cell = cell(column.title(), HEAD, column.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            cell.setBackgroundColor(ACCENT);
            table.addCell(cell);
        }
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            for (int i = 0; i < row.length; i++) {
                PdfPCell cell = cell(row[i], TEXT,
                        columns.get(i).numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(LIGHT);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    public static String money(BigDecimal amount) {
        return amount == null ? "" : String.format(Locale.ROOT, "%,.2f", amount);
    }

    public static String day(LocalDate day) {
        return DAY.format(day);
    }

    /** The account or card shown by its last four digits only, as in emails. */
    public static String ending(String number) {
        return "ending " + number.substring(Math.max(0, number.length() - 4));
    }
}
