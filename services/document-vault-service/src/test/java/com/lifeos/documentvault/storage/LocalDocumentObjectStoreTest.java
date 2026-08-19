package com.lifeos.documentvault.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.documentvault.config.DocumentVaultStorageProperties;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDocumentObjectStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesVerifiesAndPromotesOnlyGeneratedOpaquePaths() throws Exception {
        LocalDocumentObjectStore store = store(Clock.systemUTC());
        byte[] content = "%PDF-1.7\nowner scoped document".getBytes(StandardCharsets.US_ASCII);

        StagedDocumentObject staged = store.stage(
                new ByteArrayInputStream(content), DocumentContentType.PDF, 1_024, Duration.ofSeconds(1));
        UUID documentId = UUID.randomUUID();
        StoredDocumentObject stored = store.promote(staged, documentId);

        assertThat(stored.objectReference()).matches("local:[0-9a-f-]{36}:[0-9a-f-]{36}");
        assertThat(temporaryDirectory.resolve("objects").resolve(documentId.toString()).resolve(staged.stagingId() + ".blob"))
                .hasBinaryContent(content);
        assertThat(filesIn(temporaryDirectory.resolve("staging"))).isEmpty();
    }

    @Test
    void extractsOnlyABoundedUtf8PrefixForPlainTextSearch() {
        LocalDocumentObjectStore store = store(Clock.systemUTC());
        StagedDocumentObject staged = store.stage(
                new ByteArrayInputStream("private ledger phrase".getBytes(StandardCharsets.UTF_8)),
                DocumentContentType.PLAIN_TEXT,
                1_024,
                Duration.ofSeconds(1));

        assertThat(staged.searchableText()).isEqualTo("private ledger phrase");
    }

    @Test
    void acceptsCommonBoundedTextFormatsForSearch() {
        LocalDocumentObjectStore store = store(Clock.systemUTC());
        for (DocumentContentType type : List.of(DocumentContentType.CSV, DocumentContentType.MARKDOWN, DocumentContentType.HTML)) {
            StagedDocumentObject staged = store.stage(
                    new ByteArrayInputStream("private text format".getBytes(StandardCharsets.UTF_8)),
                    type,
                    1_024,
                    Duration.ofSeconds(1));
            assertThat(staged.searchableText()).contains("private text format");
            store.discard(staged);
        }
    }

    @Test
    void extractsBoundedTextFromWellFormedPdfWithoutPersistingRawContent() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("private PDF ledger phrase");
                content.endText();
            }
            try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                document.save(output);
                pdf = output.toByteArray();
            }
        }

        StagedDocumentObject staged = store(Clock.systemUTC()).stage(
                new ByteArrayInputStream(pdf), DocumentContentType.PDF, 1_024 * 1_024, Duration.ofSeconds(1));

        assertThat(staged.searchableText()).contains("private PDF ledger phrase");
        assertThat(staged.searchableText()).doesNotContain("%PDF");
    }

    @Test
    void extractsTextFromBoundedDocxAndPptxXmlWithoutUnzippingToDisk() throws Exception {
        byte[] docx = officePackage("word/document.xml", "<w:document xmlns:w=\"urn:word\"><w:p><w:t>private DOCX phrase</w:t></w:p></w:document>");
        byte[] pptx = officePackage("ppt/slides/slide1.xml", "<p:sld xmlns:p=\"urn:ppt\"><a:t xmlns:a=\"urn:drawing\">private PPTX phrase</a:t></p:sld>");
        LocalDocumentObjectStore store = store(Clock.systemUTC());

        StagedDocumentObject stagedDocx = store
                .stage(new ByteArrayInputStream(docx), DocumentContentType.DOCX, 1_024 * 1_024, Duration.ofSeconds(1));
        assertThat(stagedDocx.searchableText())
                .contains("private DOCX phrase");
        StagedDocumentObject stagedPptx = store
                .stage(new ByteArrayInputStream(pptx), DocumentContentType.PPTX, 1_024 * 1_024, Duration.ofSeconds(1));
        assertThat(stagedPptx.searchableText())
                .contains("private PPTX phrase");
        store.delete(store.promote(stagedDocx, UUID.randomUUID()).objectReference());
        store.delete(store.promote(stagedPptx, UUID.randomUUID()).objectReference());
        assertThat(filesIn(temporaryDirectory.resolve("staging"))).isEmpty();
    }

    @Test
    void extractsTextFromBoundedXlsxSharedStringsAndWorksheets() throws Exception {
        byte[] xlsx = officePackage(
                "xl/sharedStrings.xml",
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>private XLSX phrase</t></si></sst>",
                "xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row><c><v>42</v></c></row></sheetData></worksheet>");

        LocalDocumentObjectStore store = store(Clock.systemUTC());
        StagedDocumentObject staged = store.stage(
                new ByteArrayInputStream(xlsx), DocumentContentType.XLSX, 1_024 * 1_024, Duration.ofSeconds(1));

        assertThat(staged.searchableText()).contains("private XLSX phrase", "42");
        store.discard(staged);
    }

    @Test
    void acceptsTheCanonicalXlsxMediaTypeWithParameters() {
        assertThat(DocumentContentType.requireAllowed(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isEqualTo(DocumentContentType.XLSX);
    }

    @Test
    void indexesSafeImageDimensionsWithoutDecodingPixelsIntoSearchText() throws Exception {
        byte[] png;
        BufferedImage image = new BufferedImage(23, 17, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            png = output.toByteArray();
        }

        StagedDocumentObject staged = store(Clock.systemUTC()).stage(
                new ByteArrayInputStream(png), DocumentContentType.PNG, 1_024 * 1_024, Duration.ofSeconds(1));

        assertThat(staged.searchableText()).isEqualTo("image format=png width=23 height=17");
        assertThat(staged.searchableText()).doesNotContain("BLUE");
    }

    @Test
    void appendsOptInOcrTextWithoutChangingTheBoundedImageFacts() throws Exception {
        byte[] png;
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            png = output.toByteArray();
        }

        DocumentVaultStorageProperties properties = new DocumentVaultStorageProperties();
        properties.setLocalRoot(temporaryDirectory.toString());
        LocalDocumentObjectStore store = new LocalDocumentObjectStore(
                properties, Clock.systemUTC(), (ignoredPath, ignoredType) -> "receipt words");

        StagedDocumentObject staged = store.stage(
                new ByteArrayInputStream(png), DocumentContentType.PNG, 1_024 * 1_024, Duration.ofSeconds(1));

        assertThat(staged.searchableText()).isEqualTo("image format=png width=4 height=3 receipt words");
    }

    @Test
    void removesStagingFileWhenInputIsInterrupted() throws Exception {
        LocalDocumentObjectStore store = store(Clock.systemUTC());

        assertThatThrownBy(() -> store.stage(
                        new InterruptedPdfInputStream(), DocumentContentType.PDF, 1_024, Duration.ofSeconds(1)))
                .isInstanceOf(DocumentObjectStorageException.class);

        assertThat(filesIn(temporaryDirectory.resolve("staging"))).isEmpty();
    }

    @Test
    void removesStagingFileWhenContentExceedsConfiguredLimit() throws Exception {
        LocalDocumentObjectStore store = store(Clock.systemUTC());
        byte[] content = "%PDF-1.7\nthis file is too large".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> store.stage(
                        new ByteArrayInputStream(content), DocumentContentType.PDF, 8, Duration.ofSeconds(1)))
                .isInstanceOf(DocumentUploadTooLargeException.class);

        assertThat(filesIn(temporaryDirectory.resolve("staging"))).isEmpty();
    }

    @Test
    void rejectsDeadlineExpiryAndLeavesNoTemporaryObject() throws Exception {
        LocalDocumentObjectStore store = store(new ExpiringClock());

        assertThatThrownBy(() -> store.stage(
                        new ByteArrayInputStream("%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII)),
                        DocumentContentType.PDF,
                        1_024,
                        Duration.ofMillis(1)))
                .isInstanceOf(DocumentUploadDeadlineExceededException.class);

        assertThat(filesIn(temporaryDirectory.resolve("staging"))).isEmpty();
    }

    @Test
    void rejectsTraversalLikeObjectReferencesBeforeFilesystemAccess() {
        LocalDocumentObjectStore store = store(Clock.systemUTC());

        assertThatThrownBy(() -> store.delete("local:../../etc:passwd"))
                .isInstanceOf(DocumentObjectStorageException.class);
    }

    private LocalDocumentObjectStore store(Clock clock) {
        DocumentVaultStorageProperties properties = new DocumentVaultStorageProperties();
        properties.setLocalRoot(temporaryDirectory.toString());
        return new LocalDocumentObjectStore(properties, clock);
    }

    private static byte[] officePackage(String entryName, String xml) throws IOException {
        return officePackage(new String[] {entryName}, new String[] {xml});
    }

    private static byte[] officePackage(String entryName, String xml, String secondEntryName, String secondXml)
            throws IOException {
        return officePackage(new String[] {entryName, secondEntryName}, new String[] {xml, secondXml});
    }

    private static byte[] officePackage(String[] entryNames, String[] xmlParts) throws IOException {
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < entryNames.length; index++) {
                zip.putNextEntry(new ZipEntry(entryNames[index]));
                zip.write(xmlParts[index].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static java.util.List<Path> filesIn(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.toList();
        }
    }

    private static final class InterruptedPdfInputStream extends InputStream {

        private boolean firstRead = true;

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (firstRead) {
                firstRead = false;
                byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(header, 0, bytes, offset, header.length);
                return header.length;
            }
            throw new IOException("simulated input interruption");
        }

        @Override
        public int read() throws IOException {
            throw new IOException("single-byte reads are not expected");
        }
    }

    private static final class ExpiringClock extends Clock {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return reads.getAndIncrement() == 0
                    ? Instant.parse("2026-01-01T00:00:00Z")
                    : Instant.parse("2026-01-01T00:00:01Z");
        }
    }
}
