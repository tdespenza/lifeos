package com.lifeos.documentvault.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extracts a bounded, privacy-safe search source from supported document formats.
 *
 * <p>The extractor never returns the original bytes and deliberately caps both the number of
 * pages/office entries visited and the resulting UTF-8 text. Malformed, encrypted, or suspiciously
 * large documents remain opaque objects but contribute no searchable content rather than making
 * upload unavailable. Images contribute bounded ImageIO metadata; an explicitly enabled local
 * OCR adapter may add bounded text, but pixels are never persisted as a durable search field.
 */
final class DocumentTextExtractor {

    static final int MAX_TEXT_CHARS = 65_536;
    private static final int MAX_PDF_PAGES = 100;
    private static final int MAX_OFFICE_ENTRIES = 512;
    private static final int MAX_OFFICE_XML_BYTES = 4 * 1024 * 1024;

    private DocumentTextExtractor() {}

    static String extract(Path objectPath, DocumentContentType contentType) {
        return extract(objectPath, contentType, DocumentOcrExtractor.disabled());
    }

    static String extract(Path objectPath, DocumentContentType contentType, DocumentOcrExtractor ocrExtractor) {
        if (objectPath == null || contentType == null) {
            return "";
        }
        try {
            return switch (contentType) {
                case PDF -> extractPdf(objectPath);
                case DOCX, PPTX, XLSX -> extractOfficeXml(objectPath, contentType);
                case PNG, JPEG -> mergeImageFacts(
                        extractImageMetadata(objectPath, contentType),
                        ocrExtractor == null ? "" : ocrExtractor.extract(objectPath, contentType));
                default -> "";
            };
        } catch (IOException | RuntimeException ignored) {
            // Some PDFs/Office packages require a password or contain malformed objects. Search
            // remains optional and must never turn a verified upload into a service failure.
            return "";
        }
    }

    private static String mergeImageFacts(String metadata, String ocrText) {
        if (metadata == null || metadata.isBlank()) {
            return sanitize(ocrText);
        }
        if (ocrText == null || ocrText.isBlank()) {
            return metadata;
        }
        return sanitize(metadata + " " + ocrText);
    }

    private static String extractPdf(Path objectPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(objectPath.toFile())) {
            if (document.isEncrypted()) {
                return "";
            }
            int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
            if (pageCount == 0) {
                return "";
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pageCount);
            return sanitize(stripper.getText(document));
        }
    }

    /** Returns bounded, non-sensitive image facts without decoding the raster. */
    private static String extractImageMetadata(Path objectPath, DocumentContentType contentType) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(Files.newInputStream(objectPath))) {
            if (input == null) {
                return "";
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return "";
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 100_000 || height > 100_000
                        || ((long) width * (long) height) > 100_000_000L) {
                    return "";
                }
                return "image format=" + contentType.name().toLowerCase(Locale.ROOT)
                        + " width=" + width + " height=" + height;
            } finally {
                reader.dispose();
            }
        }
    }

    private static String extractOfficeXml(Path objectPath, DocumentContentType contentType) throws IOException {
        StringBuilder text = new StringBuilder();
        try (ZipFile zip = new ZipFile(objectPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int entryCount = 0;
            int bytesRead = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_OFFICE_ENTRIES) {
                    return "";
                }
                if (entry.isDirectory() || !isSafeEntryName(entry.getName())
                        || !isSearchableEntry(entry.getName(), contentType)) {
                    continue;
                }
                if (entry.getSize() > MAX_OFFICE_XML_BYTES || entry.getSize() < -1) {
                    return "";
                }
                byte[] xml = readEntry(zip, entry, MAX_OFFICE_XML_BYTES - bytesRead);
                if (xml == null) {
                    return "";
                }
                bytesRead += xml.length;
                appendXmlText(text, xml);
                if (text.length() >= MAX_TEXT_CHARS) {
                    break;
                }
            }
        }
        return sanitize(text.toString());
    }

    private static boolean isSearchableEntry(String name, DocumentContentType contentType) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (contentType == DocumentContentType.DOCX) {
            return normalized.equals("word/document.xml")
                    || normalized.equals("word/footnotes.xml")
                    || normalized.equals("word/endnotes.xml")
                    || normalized.matches("word/header[0-9]+\\.xml")
                    || normalized.matches("word/footer[0-9]+\\.xml");
        }
        if (contentType == DocumentContentType.PPTX) {
            return normalized.matches("ppt/slides/slide[0-9]+\\.xml")
                    || normalized.matches("ppt/notesSlides/notesSlide[0-9]+\\.xml");
        }
        return normalized.equals("xl/sharedstrings.xml")
                || normalized.matches("xl/worksheets/sheet[0-9]+\\.xml")
                || normalized.matches("xl/comments[0-9]+\\.xml");
    }

    private static boolean isSafeEntryName(String name) {
        return name != null && !name.isBlank() && !name.startsWith("/") && !name.contains("\\")
                && java.util.Arrays.stream(name.split("/", -1)).noneMatch(".."::equals);
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int remainingBytes) throws IOException {
        if (remainingBytes <= 0) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (output.size() > remainingBytes - read) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void appendXmlText(StringBuilder target, byte[] xml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE) {
                    if (target.length() < MAX_TEXT_CHARS) {
                        target.append(reader.getText()).append(' ');
                    }
                }
            }
        } catch (XMLStreamException exception) {
            throw new IOException("Office XML is malformed", exception);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Search extraction is best effort; malformed close state is not searchable.
                }
            }
        }
    }

    private static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return normalized.length() <= MAX_TEXT_CHARS
                ? normalized
                : normalized.substring(0, MAX_TEXT_CHARS).strip();
    }
}
