package org.healthtg.bot.recognition;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

public final class ImageValidator {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final long MAX_PIXELS = 12_000_000;

    public record ValidatedImage(byte[] bytes, String mimeType) {
        public ValidatedImage { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public ValidatedImage validate(Path path) throws RecognitionException {
        try (var input = Files.newInputStream(path)) {
            // Bounded read also covers a file that grows after opening.
            return validate(input.readNBytes(MAX_BYTES + 1));
        } catch (IOException e) {
            throw new RecognitionException(INVALID_IMAGE);
        }
    }

    public ValidatedImage validate(byte[] bytes) throws RecognitionException {
        if (bytes.length > MAX_BYTES) throw new RecognitionException(IMAGE_TOO_LARGE);
        if (bytes.length == 0) throw new RecognitionException(INVALID_IMAGE);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new RecognitionException(INVALID_IMAGE);
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw new RecognitionException(INVALID_IMAGE);
                if (format.equals("png")) validatePngChunks(bytes);
                // Some JPEG readers tolerate truncation. Warnings are failures here.
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1) throw new RecognitionException(INVALID_IMAGE);
                if ((long) width * height > MAX_PIXELS) throw new RecognitionException(TOO_MANY_PIXELS);
                var decoded = reader.read(0);
                if (decoded == null || warning[0]) throw new RecognitionException(INVALID_IMAGE);
                decoded.flush();
                return new ValidatedImage(bytes, "image/" + format);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) {
            throw new RecognitionException(INVALID_IMAGE);
        }
    }

    /** ImageIO may ignore missing IEND and CRC failures; check framing, not pixel decoding. */
    private static void validatePngChunks(byte[] bytes) throws RecognitionException {
        int offset = 8; // ImageIO has already identified the PNG signature.
        boolean headerSeen = false, dataSeen = false, dataEnded = false;
        while (offset <= bytes.length - 12) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).getInt());
            if (length > bytes.length - offset - 12) throw new RecognitionException(INVALID_IMAGE);
            int size = (int) length;
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, size + 4);
            long stored = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset + 8 + size, 4).getInt());
            if (crc.getValue() != stored) throw new RecognitionException(INVALID_IMAGE);
            if (!headerSeen) {
                if (!type.equals("IHDR") || size != 13) throw new RecognitionException(INVALID_IMAGE);
                headerSeen = true;
            } else if (type.equals("IHDR")) throw new RecognitionException(INVALID_IMAGE);
            if (type.equals("IDAT")) {
                if (dataEnded) throw new RecognitionException(INVALID_IMAGE);
                dataSeen = true;
            } else if (dataSeen) dataEnded = true;
            offset += size + 12;
            if (type.equals("IEND")) {
                if (size != 0 || !dataSeen || offset != bytes.length) throw new RecognitionException(INVALID_IMAGE);
                return;
            }
        }
        throw new RecognitionException(INVALID_IMAGE);
    }
}
