package com.medirag.diagnostic_service.service;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.*;
import com.itextpdf.text.pdf.draw.LineSeparator;
import com.medirag.diagnostic_service.entity.DiagnosticReport;
import com.medirag.diagnostic_service.entity.Finding;
import com.medirag.diagnostic_service.entity.ScanUpload;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfReportService {

    // Reuse clients from MinioService — no duplicate clients
    private final MinioService minioService;

    @Value("${minio.report-bucket}")
    private String reportBucket;

    @Value("${minio.presigned-expiry-minutes:15}")
    private int presignedExpiryMinutes;

    private static final Font TITLE_FONT =
        new Font(Font.FontFamily.HELVETICA, 20, Font.BOLD, new BaseColor(30, 58, 95));
    private static final Font HEADER_FONT =
        new Font(Font.FontFamily.HELVETICA, 13, Font.BOLD, new BaseColor(46, 117, 182));
    private static final Font BODY_FONT =
        new Font(Font.FontFamily.HELVETICA, 11, Font.NORMAL, BaseColor.DARK_GRAY);
    private static final Font LABEL_FONT =
        new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.DARK_GRAY);
    private static final Font VALUE_FONT =
        new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, BaseColor.DARK_GRAY);
    private static final Font SMALL_FONT =
        new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.GRAY);

    public String generateAndUpload(DiagnosticReport report, ScanUpload scan) {
        log.info("=== generateAndUpload called for scanId={}, reportId={}",
                scan.getId(), report.getId());
        try {
            byte[] pdfBytes = buildPdf(report, scan);
            log.info("PDF bytes generated: {} bytes", pdfBytes.length);

            String objectName = "reports/" + scan.getPatientId() + "/"
                    + UUID.randomUUID() + "-report.pdf";

            // Upload using internal upload client
            MinioClient uploadClient = minioService.getUploadClient();
            uploadClient.putObject(
                PutObjectArgs.builder()
                    .bucket(reportBucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(pdfBytes), pdfBytes.length, -1)
                    .contentType("application/pdf")
                    .build()
            );
            log.info("PDF uploaded to MinIO: {}/{}", reportBucket, objectName);

            // Generate presigned URL using external presigned client
            // presignedClient uses host.docker.internal:9000
            // → signs with that host → browser sends Host: localhost:9000
            // → Docker maps localhost:9000 → minio:9000 → signature validates
            MinioClient presignedClient = minioService.getPresignedClient();
            String presigned = presignedClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(reportBucket)
                    .object(objectName)
                    .expiry(presignedExpiryMinutes, TimeUnit.MINUTES)
                    .build()
            );

            log.info("PDF presigned URL (public): {}", presigned.substring(0, Math.min(presigned.length(), 80)) + "...");
            return presigned;

        } catch (Exception e) {
            log.error("Failed to generate/upload PDF report: {}", e.getMessage(), e);
            return null;
        }
    }

    private byte[] buildPdf(DiagnosticReport report, ScanUpload scan) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 60, 60);
        PdfWriter writer = PdfWriter.getInstance(doc, out);

        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                PdfContentByte cb = w.getDirectContent();
                cb.setColorStroke(new BaseColor(30, 58, 95));
                cb.setLineWidth(1.5f);
                cb.moveTo(40, 30);
                cb.lineTo(555, 30);
                cb.stroke();
                Phrase footer = new Phrase("MediRAG Diagnostic Report  |  Page " +
                        w.getPageNumber(), SMALL_FONT);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                        footer, 297, 20, 0);
            }
        });

        doc.open();

        Paragraph title = new Paragraph("MediRAG", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        Paragraph subtitle = new Paragraph("AI Diagnostic Report",
            new Font(Font.FontFamily.HELVETICA, 13, Font.NORMAL, new BaseColor(46, 117, 182)));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(4);
        doc.add(subtitle);

        LineSeparator line = new LineSeparator();
        line.setLineColor(new BaseColor(46, 117, 182));
        doc.add(new Chunk(line));
        doc.add(Chunk.NEWLINE);

        doc.add(sectionHeader("Scan Information"));

        PdfPTable infoTable = new PdfPTable(2);
        infoTable.setWidthPercentage(100);
        infoTable.setWidths(new float[]{1f, 2f});
        infoTable.setSpacingAfter(12);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
        addInfoRow(infoTable, "Patient ID",        scan.getPatientId().toString());
        addInfoRow(infoTable, "File Name",          scan.getFileName());
        addInfoRow(infoTable, "Scan Type",          scan.getScanType().name());
        addInfoRow(infoTable, "Uploaded At",        scan.getUploadedAt().format(fmt));
        addInfoRow(infoTable, "Report Generated",   report.getGeneratedAt().format(fmt));
        addInfoRow(infoTable, "Overall Confidence",
                report.getOverallConfidence() != null
                    ? String.format("%.0f%%", report.getOverallConfidence() * 100)
                    : "N/A");
        doc.add(infoTable);

        doc.add(sectionHeader("AI Summary"));
        Paragraph summary = new Paragraph(
            report.getSummary() != null ? report.getSummary() : "No summary available.",
            BODY_FONT);
        summary.setSpacingAfter(12);
        summary.setLeading(16);
        doc.add(summary);

        doc.add(sectionHeader("Findings"));

        if (report.getFindings() == null || report.getFindings().isEmpty()) {
            doc.add(new Paragraph("No findings recorded.", BODY_FONT));
        } else {
            PdfPTable findingsTable = new PdfPTable(5);
            findingsTable.setWidthPercentage(100);
            findingsTable.setWidths(new float[]{2.5f, 1.2f, 1.2f, 1.5f, 1.5f});
            findingsTable.setSpacingAfter(12);

            for (String h : new String[]{"Condition","Confidence","Severity","Location","Bounding Box"}) {
                PdfPCell cell = new PdfPCell(new Phrase(h,
                    new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE)));
                cell.setBackgroundColor(new BaseColor(30, 58, 95));
                cell.setPadding(6);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setBorderColor(new BaseColor(200, 200, 200));
                findingsTable.addCell(cell);
            }

            boolean alternate = false;
            for (Finding f : report.getFindings()) {
                BaseColor rowColor = alternate
                        ? new BaseColor(232, 244, 253) : BaseColor.WHITE;
                alternate = !alternate;
                addFindingCell(findingsTable, f.getCondition(), rowColor, Element.ALIGN_LEFT);
                addFindingCell(findingsTable,
                        f.getConfidence() != null
                            ? String.format("%.0f%%", f.getConfidence() * 100) : "N/A",
                        rowColor, Element.ALIGN_CENTER);
                addFindingCell(findingsTable,
                        f.getSeverity() != null ? f.getSeverity().name() : "N/A",
                        rowColor, Element.ALIGN_CENTER);
                addFindingCell(findingsTable,
                        f.getLocation() != null ? f.getLocation() : "N/A",
                        rowColor, Element.ALIGN_CENTER);
                addFindingCell(findingsTable,
                        f.getBoundingBox() != null ? f.getBoundingBox() : "N/A",
                        rowColor, Element.ALIGN_CENTER);
            }
            doc.add(findingsTable);
        }

        doc.add(new Chunk(line));
        doc.add(Chunk.NEWLINE);
        Paragraph disclaimer = new Paragraph(
            "DISCLAIMER: This report is generated by an AI system and is intended for " +
            "informational purposes only. It does not constitute medical advice, diagnosis, " +
            "or treatment. Always consult a qualified radiologist or medical professional " +
            "for clinical interpretation of diagnostic imaging.",
            SMALL_FONT);
        disclaimer.setAlignment(Element.ALIGN_JUSTIFIED);
        disclaimer.setLeading(13);
        doc.add(disclaimer);

        doc.close();
        return out.toByteArray();
    }

    private Paragraph sectionHeader(String text) {
        Paragraph p = new Paragraph(text, HEADER_FONT);
        p.setSpacingBefore(6);
        p.setSpacingAfter(6);
        return p;
    }

    private void addInfoRow(PdfPTable table, String label, String value) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, LABEL_FONT));
        labelCell.setBackgroundColor(new BaseColor(214, 228, 240));
        labelCell.setPadding(5);
        labelCell.setBorderColor(new BaseColor(200, 200, 200));
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, VALUE_FONT));
        valueCell.setBackgroundColor(BaseColor.WHITE);
        valueCell.setPadding(5);
        valueCell.setBorderColor(new BaseColor(200, 200, 200));
        table.addCell(valueCell);
    }

    private void addFindingCell(PdfPTable table, String text, BaseColor bg, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "N/A", VALUE_FONT));
        cell.setBackgroundColor(bg);
        cell.setPadding(5);
        cell.setHorizontalAlignment(alignment);
        cell.setBorderColor(new BaseColor(200, 200, 200));
        table.addCell(cell);
    }
}