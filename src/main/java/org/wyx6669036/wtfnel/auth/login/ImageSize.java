package org.wyx6669036.wtfnel.auth.login;

/**
 * Reads width/height from a PNG, JPEG or GIF header.
 *
 * <p>Port of WtfNel.Core/Auth/Login/CaptchaPrompt.cs (ImageSize).</p>
 */
public final class ImageSize {

    private ImageSize() {
    }

    /** The image size, or {@code null} when the format is unknown. */
    public static Size read(byte[] data) {
        if (data.length > 24 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return new Size(bigEndian(data, 16), bigEndian(data, 20));
        }
        if (data.length > 10 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return new Size((data[6] & 0xFF) | ((data[7] & 0xFF) << 8), (data[8] & 0xFF) | ((data[9] & 0xFF) << 8));
        }
        if (data.length > 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < data.length) {
                if ((data[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int marker = data[i + 1] & 0xFF;
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    int height = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                    int width = ((data[i + 7] & 0xFF) << 8) | (data[i + 8] & 0xFF);
                    return new Size(width, height);
                }
                i += 2 + (((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF));
            }
        }
        return null;
    }

    private static int bigEndian(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    public record Size(int width, int height) {
    }
}
