package org.wyx6669036.wtfnel.util;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 程序所在目录（对应 C# 的 {@code AppContext.BaseDirectory}）。
 *
 * <p>C# 那边是 exe 所在目录；Java 侧没有 exe，按以下顺序确定：</p>
 * <ol>
 *   <li>{@code -Dwtfnel.home=<目录>}（显式指定，最高优先级）；</li>
 *   <li>从 jar 启动时用 jar 所在目录；若是 installDist 的 {@code <dist>/lib} 布局则退回 {@code <dist>/bin}；</li>
 *   <li>其它情况（IDE / gradle run，代码在 classes 目录里）用 {@code user.dir}。</li>
 * </ol>
 *
 * <p>shadow.json、logs、cache、LocalRuntime、payloads 都相对这个目录。</p>
 *
 * <p>{@link #setBaseDirectory} 可以把它临时钉到别处（{@code new WtfNel(工作路径)} 就是这么做的），
 * 钉过之后 {@link #baseDirectory()} 返回钉住的那个目录；{@link #clearBaseDirectory()} 恢复自动探测。
 * 不钉的时候行为与原版一致——CLI（{@code cli.Main}）从不钉，永远用运行目录。</p>
 */
public final class AppPaths {

    /** 钉住的目录（{@link #setBaseDirectory}），null 表示按运行目录自动探测。 */
    private static volatile Path overrideDirectory;

    private AppPaths() {
    }

    /** 生效的程序目录：钉过就是钉住的目录，否则等于 {@link #runDirectory()}。 */
    public static Path baseDirectory() {
        Path pinned = overrideDirectory;
        return pinned != null ? pinned : runDirectory();
    }

    /**
     * 把程序目录钉到指定目录，之后 {@link #baseDirectory()} 就返回它。
     *
     * <p>给「宿主把启动器嵌进来、希望 shadow.json / logs / cache / payloads 写到指定目录」
     * 的场景用，{@code new WtfNel(工作路径)} 内部就是调这个方法。进程级设置，
     * 后设置会盖掉前面的；{@link #clearBaseDirectory()} 可以撤销。</p>
     */
    public static void setBaseDirectory(Path directory) {
        overrideDirectory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    /** 撤销钉住的目录，回到按运行目录自动探测。 */
    public static void clearBaseDirectory() {
        overrideDirectory = null;
    }

    /** 是否钉过目录。 */
    public static boolean hasOverride() {
        return overrideDirectory != null;
    }

    /** 不带任何覆盖时的运行目录：{@code -Dwtfnel.home} → jar 所在目录 → {@code user.dir}。 */
    public static Path runDirectory() {
        String override = System.getProperty("wtfnel.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        try {
            Path self = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (Files.isRegularFile(self)) {
                return distributionBase(self.getParent());
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // 拿不到就退回工作目录
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /** {@code <dist>/lib/app.jar -> <dist>/bin}（installDist 布局），否则就是 jar 所在目录。 */
    private static Path distributionBase(Path jarDirectory) {
        if (jarDirectory == null) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        Path parent = jarDirectory.getParent();
        if ("lib".equalsIgnoreCase(String.valueOf(jarDirectory.getFileName()))
                && parent != null && Files.isDirectory(parent.resolve("bin"))) {
            return parent.resolve("bin");
        }
        return jarDirectory;
    }
}
