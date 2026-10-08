package com.medirag.diagnostic_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts raw text from uploaded medical report files.
 *
 * Supports:
 *   - PDF  (application/pdf)          — Apache PDFBox
 *   - DOCX (application/vnd.openxmlformats...) — Apache POI
 *   - TXT  (text/plain)               — direct stream read
 *
 * Used exclusively by the REPORT PIPELINE in DiagnosticService.
 * The IMAGE PIPELINE never touches this class.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportTextExtractionService {

    private final DocumentChunkingService chunkingService;

    public static final Set<String> SUPPORTED_REPORT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );

    private static final int MIN_EXTRACTABLE_LENGTH = 50;

    public boolean isReportFile(String contentType) {
        if (contentType == null) return false;
        return SUPPORTED_REPORT_TYPES.contains(contentType.toLowerCase().trim());
    }

    /**
     * Extracts raw text from a MultipartFile upload.
     */
    public String extractText(MultipartFile file) {
        try {
            return extractText(file.getInputStream(), file.getContentType(), file.getOriginalFilename(), file.getSize());
        } catch (IOException e) {
            log.error("IO error reading multipart file: {}", e.getMessage());
            throw new ReportExtractionException("Failed to read uploaded file: " + e.getMessage());
        }
    }

    /**
     * Extracts raw text from an InputStream (e.g. from MinIO).
     * This avoids needing MockMultipartFile in production code.
     *
     * @param stream      The input stream to read from (caller is responsible for closing)
     * @param contentType MIME type of the file
     * @param fileName    Original filename (for error messages)
     * @param size        File size in bytes (for logging)
     */
    public String extractText(InputStream stream, String contentType,
                               String fileName, long size) {
        log.info("Extracting text from report: fileName={}, contentType={}, size={}bytes",
                fileName, contentType, size);

        try {
            String raw = switch (contentType) {
                case "application/pdf" -> extractFromPdf(stream);
                case "application/vnd.openxmlformats-officedocument"
                        + ".wordprocessingml.document"
                        -> extractFromDocx(stream);
                case "text/plain" -> extractFromTxt(stream);
                default -> throw new ReportExtractionException(
                        "Unsupported report type: " + contentType +
                        ". Supported: PDF, DOCX, TXT");
            };

            String cleaned = clean(raw);

            if (cleaned.length() < MIN_EXTRACTABLE_LENGTH) {
                throw new ReportExtractionException(
                        "Extracted text too short (" + cleaned.length() + " chars). " +
                        "The file may be empty, image-only, or password-protected.");
            }

            log.info("Extracted {} chars from {}", cleaned.length(), fileName);
            return cleaned;

        } catch (ReportExtractionException e) {
            throw e;
        } catch (IOException e) {
            log.error("IO error extracting text from {}: {}", fileName, e.getMessage());
            throw new ReportExtractionException(
                    "Failed to read file '" + fileName + "': " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error extracting text from {}: {}", fileName, e.getMessage(), e);
            throw new ReportExtractionException(
                    "Unexpected error processing '" + fileName + "': " + e.getMessage());
        }
    }

    /**
     * Convenience: extract + chunk from MultipartFile.
     */
    public List<String> extractAndChunk(MultipartFile file) {
        String text = extractText(file);
        List<String> chunks = chunkingService.chunk(text);
        log.info("Report split into {} chunks for embedding", chunks.size());
        return chunks;
    }

    /**
     * Convenience: extract + chunk from InputStream.
     */
    public List<String> extractAndChunk(InputStream stream, String contentType,
                                         String fileName, long size) {
        String text = extractText(stream, contentType, fileName, size);
        List<String> chunks = chunkingService.chunk(text);
        log.info("Report split into {} chunks for embedding", chunks.size());
        return chunks;
    }

    // ── Extractors ───────────────────────────────────────────────────────

    private String extractFromPdf(InputStream stream) throws IOException {
        try (PDDocument doc = PDDocument.load(stream)) {
            if (doc.isEncrypted()) {
                throw new ReportExtractionException(
                        "PDF is password-protected. Please upload an unencrypted file.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String text = stripper.getText(doc);

            if (text == null || text.isBlank()) {
                throw new ReportExtractionException(
                        "PDF contains no extractable text. It may be a scanned image PDF. " +
                        "Please upload a text-based PDF or a TXT/DOCX version of the report.");
            }

            log.debug("PDFBox extracted {} chars across {} pages",
                    text.length(), doc.getNumberOfPages());
            return text;
        }
    }

    private String extractFromDocx(InputStream stream) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(stream)) {
            String text = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining(" "));

            if (text.isBlank()) {
                throw new ReportExtractionException(
                        "DOCX contains no extractable text. " +
                        "The document may be empty or contain only images/tables.");
            }

            log.debug("POI extracted {} chars from DOCX ({} paragraphs)",
                    text.length(), doc.getParagraphs().size());
            return text;
        }
    }

    private String extractFromTxt(InputStream stream) throws IOException {
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) {
            throw new ReportExtractionException("TXT file is empty.");
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        log.debug("Read {} chars from TXT file", text.length());
        return text;
    }

    // ── Text cleaning ────────────────────────────────────────────────────

    private String clean(String raw) {
        return raw
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("\\f", "\n")
                .replaceAll("\\x00", "")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(?m)^\\s*-?\\s*\\d+\\s*-?\\s*$", "")
                .trim();
    }

    // ── Custom exception ─────────────────────────────────────────────────

    public static class ReportExtractionException extends RuntimeException {
        public ReportExtractionException(String message) {
            super(message);
        }
    }
}
