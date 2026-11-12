package org.wyx6669036.wtfnel.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * UTF-8 文本读写的小工具，唯一职责是吃掉 BOM。
 *
 * <p>C# 那边用的是 {@code File.ReadAllText}，它的 {@code StreamReader} 默认
 * {@code detectEncodingFromByteOrderMarks: true}，会静默剥掉 UTF-8 BOM；Java 的
 * {@code Files.readString(UTF_8)} 会把 BOM 当成普通字符保留。而记事本、PowerShell 的
 * {@code Set-Content -Encoding UTF8}、不少编辑器写出来的 JSON / txt 都带 BOM，
 * 于是 C# 能读的配置到 Java 侧会抛「Unexpected character (code 65279)」。
 * 为了让两个实现对同样的文件给出同样的结果，所有「读用户手写的文本」的地方都走这里。</p>
 */
public final class Text {

    /** UTF-8 BOM（U+FEFF）。 */
    private static final char BOM = '\uFEFF';

    private Text() {
    }

    /** Reads a UTF-8 text file, dropping a leading BOM if there is one. */
    public static String readUtf8(Path path) throws IOException {
        return stripBom(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** Drops one leading BOM; a null input stays null. */
    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == BOM) {
            return text.substring(1);
        }
        return text;
    }
}
