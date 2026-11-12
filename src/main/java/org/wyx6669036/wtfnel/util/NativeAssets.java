package org.wyx6669036.wtfnel.util;

import org.wyx6669036.wtfnel.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * jar 内自带的原生产物：{@code WPFLauncher.exe} 替身与两个 payload DLL。
 *
 * <p>打包时它们被收进 jar 的 {@code native/} 目录（见 build.gradle 的 jar / fatJar）。
 * 运行期（{@code new WtfNel(...)}、CLI 启动、真正拉起客户端之前）把它们同步到程序目录，
 * 这样分发时只给一个 jar 也能跑。</p>
 *
 * <p>同步按内容比对而不是「文件名对不对 / 文件在不在」：先释放到同目录的临时文件，
 * 再用 MD5 跟目标比对——一致就删掉临时文件、目标原样不动，不一致才把临时文件挪过去覆盖。
 * 所以 jar 里换了新编译的 payload，下次启动就会跟着更新；手工替换过的文件只要内容不同，
 * 同样会被 jar 内的版本覆盖回来。</p>
 *
 * <p>文件名必须与 {@link org.wyx6669036.wtfnel.injection.InjectionService} 的默认 payload 名、
 * 以及 ShadowConfig 的 LauncherPath 默认值一致。</p>
 */
public final class NativeAssets {

    /** jar 内资源根目录（不带前导斜杠的相对路径）。 */
    public static final String RESOURCE_ROOT = "native";

    /** 启动器替身的文件名。 */
    public static final String LAUNCHER_FILE = "WPFLauncher.exe";

    /** 默认 payload 文件名（与 InjectionService 的常量一一对应）。 */
    public static final List<String> PAYLOAD_FILES =
            List.of("WtfNel.Payload.HwidSpoof.dll", "WtfNel.Payload.Proxy.dll");

    private NativeAssets() {
    }

    /** jar 里有没有带原生产物（用 -PskipNative 打的包就没有）。 */
    public static boolean available() {
        return resource(LAUNCHER_FILE) != null;
    }

    /**
     * 把 jar 里带的原生产物同步到程序目录：{@code <base>/WPFLauncher.exe} 与
     * {@code <base>/payloads/*.dll}。内容一致的不动，不一致（或缺失）的覆盖。
     *
     * @return 这次真正写入/替换的文件数（0 表示内容都已一致，或 jar 里没带）
     */
    public static int install(Path baseDirectory) {
        if (baseDirectory == null) {
            return 0;
        }
        Path base = baseDirectory.toAbsolutePath().normalize();
        int written = 0;
        try {
            written += sync(LAUNCHER_FILE, base.resolve(LAUNCHER_FILE)) ? 1 : 0;
            Path payloadDir = base.resolve("payloads");
            for (String name : PAYLOAD_FILES) {
                written += sync(name, payloadDir.resolve(name)) ? 1 : 0;
            }
        } catch (RuntimeException e) {
            Log.warn("[native] 释放 jar 内的原生产物失败：" + e.getMessage());
        }
        if (written > 0) {
            Log.info("[native] 已从 jar 更新 " + written + " 个原生产物到 " + base);
        }
        return written;
    }

    /** 同步启动器替身：jar 里有就按内容比对同步，返回目标路径（jar 里没带、目标又不存才返回 null）。 */
    public static Path installLauncher(Path baseDirectory) {
        if (baseDirectory == null) {
            return null;
        }
        Path target = baseDirectory.toAbsolutePath().normalize().resolve(LAUNCHER_FILE);
        sync(LAUNCHER_FILE, target);
        return Files.isRegularFile(target) ? target : null;
    }

    /** 同步某个 payload 到 {@code <payloadDirectory>/}；jar 里没这个资源时不碰磁盘。 */
    public static Path installPayload(Path payloadDirectory, String fileName) {
        if (payloadDirectory == null || fileName == null || fileName.isBlank()) {
            return null;
        }
        Path target = payloadDirectory.toAbsolutePath().normalize().resolve(fileName);
        sync(fileName, target);
        return Files.isRegularFile(target) ? target : null;
    }

    /** jar 里带的 payload 文件名（按资源实际存在与否过滤）。 */
    public static List<String> bundledPayloads() {
        List<String> names = new ArrayList<>();
        for (String name : PAYLOAD_FILES) {
            if (resource(name) != null) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 让 {@code target} 与 jar 内的 {@code native/<fileName>} 内容一致。
     *
     * <p>先落到同目录的临时文件（同卷才能原子移动），再与目标做 MD5 比对：
     * 一致就删临时文件、目标一个字节都不动；不一致或目标缺失才把临时文件挪过去覆盖。
     * 判断依据只有内容，跟文件名和「文件在不在」无关。</p>
     *
     * @return true 表示这次真的写入/替换了目标
     */
    private static boolean sync(String fileName, Path target) {
        Path temp = null;
        try (InputStream in = resource(fileName)) {
            if (in == null) {
                return false;                       // jar 里没带（-PskipNative 打的包）
            }
            Path dir = target.getParent();
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, ".wtfnel-native-", ".tmp");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            if (Files.isRegularFile(target) && sameContent(temp, target)) {
                return false;                       // 内容一致：保留目标
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            Log.debug("[native] 从 jar 释放 " + target);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.warn("[native] 释放 " + target + " 失败：" + e.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    /** 两个文件内容是否完全一致（先比长度，再比 MD5）。 */
    private static boolean sameContent(Path left, Path right) {
        try {
            if (Files.size(left) != Files.size(right)) {
                return false;
            }
            return Arrays.equals(md5(left), md5(right));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] md5(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 里没有 MD5", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String fileName) {
        return NativeAssets.class.getResourceAsStream("/" + RESOURCE_ROOT + "/" + fileName);
    }
}
