package org.wyx6669036.wtfnel.cli;

import org.wyx6669036.wtfnel.auth.login.ICaptchaPrompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 控制台版的验证码应答。图片类验证码把图存到临时文件让用户自己看，
 * 滑块/点选类没法在终端里画，直接报错提示改用 cookie 渠道。
 */
public final class ConsoleCaptchaPrompt implements ICaptchaPrompt {

    private final java.util.Scanner scanner;

    public ConsoleCaptchaPrompt() {
        this.scanner = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String image(byte[] image) {
        Path file = dump(image);
        System.out.println("验证码图片已保存到 " + file);
        System.out.print("请输入图片里的字符：");
        System.out.flush();
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    @Override
    public int jigsaw(byte[] background, byte[] piece) {
        Path bg = dump(background);
        Path pc = dump(piece);
        System.out.println("滑块验证码：背景 " + bg + "，碎片 " + pc);
        System.out.print("请输入碎片需要右移的像素数：");
        System.out.flush();
        if (!scanner.hasNextLine()) {
            throw new IllegalStateException("需要滑块位置才能继续");
        }
        try {
            return Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("滑块位置必须是整数", e);
        }
    }

    @Override
    public List<Point> click(byte[] image, String text) {
        Path file = dump(image);
        throw new IllegalStateException("点选验证码需要图形界面：" + file + "（要按顺序点「" + text + "」）");
    }

    private static Path dump(byte[] bytes) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "WtfNel", "captcha");
            Files.createDirectories(dir);
            Path file = Files.createTempFile(dir, "captcha-", ".png");
            Files.write(file, bytes);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("保存验证码图片失败: " + e.getMessage(), e);
        }
    }
}
