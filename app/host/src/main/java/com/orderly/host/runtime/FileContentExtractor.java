package com.orderly.host.runtime;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.TextShape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件正文抽取器。
 *
 * <p>只读取用户已授权并签发读取令牌的普通文件。PDF 和 OpenXML/旧版 Office 文本在
 * 宿主机内提取；图片只调用本机 Tesseract，不把原图上传模型。抽取结果不落盘。</p>
 */
final class FileContentExtractor {

    private static final long MAX_EXTRACT_BYTES = 64L * 1024L * 1024L;
    private static final Duration OCR_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OCR_OUTPUT_CHARS = 200_000;

    private FileContentExtractor() {
    }

    static ExtractedText extract(Path file) {
        String extension = extension(file);
        try {
            long size = Files.size(file);
            if (size > MAX_EXTRACT_BYTES) {
                throw new IllegalArgumentException("文件超过 64 MiB 正文抽取上限");
            }
            return switch (extension) {
                case "pdf" -> new ExtractedText(extractPdf(file), "PDF");
                case "docx" -> new ExtractedText(extractDocx(file), "DOCX");
                case "doc" -> new ExtractedText(extractDoc(file), "DOC");
                case "xlsx" -> new ExtractedText(extractWorkbook(file, false), "XLSX");
                case "xls" -> new ExtractedText(extractWorkbook(file, true), "XLS");
                case "pptx" -> new ExtractedText(extractPptx(file), "PPTX");
                case "ppt" -> new ExtractedText(extractPpt(file), "PPT");
                case "png", "jpg", "jpeg", "bmp", "tif", "tiff", "webp" ->
                        new ExtractedText(extractImageText(file), "OCR");
                case "txt", "md", "csv", "json" ->
                        new ExtractedText(readPlainText(file), "UTF-8");
                default -> throw new IllegalArgumentException(
                        "该文件类型暂不提供正文提取：" + extension
                );
            };
        } catch (IOException exception) {
            throw new IllegalStateException("抽取文件正文失败", exception);
        }
    }

    private static String extractPdf(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String extractDocx(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             XWPFDocument document = new XWPFDocument(input)) {
            List<String> blocks = new ArrayList<>();
            document.getParagraphs().forEach(paragraph -> blocks.add(paragraph.getText()));
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText());
                    }
                    blocks.add(String.join(" | ", cells));
                }
            }
            return String.join("\n", blocks);
        }
    }

    @SuppressWarnings("deprecation")
    private static String extractDoc(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             HWPFDocument document = new HWPFDocument(input)) {
            return document.getDocumentText();
        }
    }

    private static String extractWorkbook(Path file, boolean legacy) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             Workbook workbook = legacy ? new HSSFWorkbook(input) : new XSSFWorkbook(input)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> lines = new ArrayList<>();
            for (Sheet sheet : workbook) {
                lines.add("# " + sheet.getSheetName());
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String value = formatter.formatCellValue(cell);
                        if (!value.isBlank()) {
                            cells.add(value);
                        }
                    }
                    if (!cells.isEmpty()) {
                        lines.add(String.join(" | ", cells));
                    }
                }
            }
            return String.join("\n", lines);
        }
    }

    private static String extractPptx(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(input)) {
            return extractSlides(slideShow);
        }
    }

    @SuppressWarnings("deprecation")
    private static String extractPpt(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file);
             HSLFSlideShow slideShow = new HSLFSlideShow(input)) {
            List<String> lines = new ArrayList<>();
            for (HSLFSlide slide : slideShow.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape
                            && textShape.getText() != null
                            && !textShape.getText().isBlank()) {
                        lines.add(textShape.getText());
                    }
                }
            }
            return String.join("\n", lines);
        }
    }

    private static String extractSlides(SlideShow<?, ?> slideShow) {
        List<String> lines = new ArrayList<>();
        for (var slide : slideShow.getSlides()) {
            for (Shape<?, ?> shape : slide.getShapes()) {
                if (shape instanceof TextShape<?, ?> textShape) {
                    String value = textShape.getText();
                    if (value != null && !value.isBlank()) {
                        lines.add(value);
                    }
                }
            }
        }
        return String.join("\n", lines);
    }

    private static String extractImageText(Path file) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(
                    "tesseract",
                    file.toAbsolutePath().toString(),
                    "stdout",
                    "-l",
                    "chi_sim+eng"
            ).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "本地 OCR 不可用：未找到 tesseract，可执行文本和 Office 摘要仍可用；"
                            + "应用不会回退为上传原图",
                    exception
            );
        }
        try {
            boolean finished = process.waitFor(OCR_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                throw new IllegalStateException("本地 OCR 超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String detail = output.length() > 300 ? output.substring(0, 300) : output;
                throw new IllegalStateException("本地 OCR 失败：" + detail.trim());
            }
            return output.length() <= MAX_OCR_OUTPUT_CHARS
                    ? output
                    : output.substring(0, MAX_OCR_OUTPUT_CHARS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本地 OCR 被中断", exception);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readPlainText(Path file) throws IOException {
        if (Files.size(file) > MAX_EXTRACT_BYTES) {
            throw new IllegalArgumentException("文件超过 64 MiB 正文抽取上限");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    record ExtractedText(String content, String encoding) {
    }
}
