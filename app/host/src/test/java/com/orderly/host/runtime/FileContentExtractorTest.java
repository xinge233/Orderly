package com.orderly.host.runtime;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileContentExtractorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsPdfDocxAndXlsxTextLocally() throws Exception {
        Path pdf = temporaryDirectory.resolve("sample.pdf");
        createPdf(pdf);
        Path docx = temporaryDirectory.resolve("sample.docx");
        createDocx(docx);
        Path xlsx = temporaryDirectory.resolve("sample.xlsx");
        createXlsx(xlsx);
        Path markdown = temporaryDirectory.resolve("sample.md");
        Files.writeString(markdown, "Orderly Markdown summary");

        assertThat(FileContentExtractor.extract(pdf).content()).contains("Orderly PDF");
        assertThat(FileContentExtractor.extract(docx).content()).contains("Orderly Word");
        assertThat(FileContentExtractor.extract(xlsx).content()).contains("Orderly Excel");
        assertThat(FileContentExtractor.extract(markdown).content())
                .contains("Orderly Markdown");
    }

    private void createPdf(Path file) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Orderly PDF summary");
                content.endText();
            }
            document.save(file.toFile());
        }
    }

    private void createDocx(Path file) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Orderly Word summary");
            document.write(output);
        }
    }

    private void createXlsx(Path file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(file)) {
            workbook.createSheet("Summary").createRow(0).createCell(0).setCellValue("Orderly Excel");
            workbook.write(output);
        }
    }
}
