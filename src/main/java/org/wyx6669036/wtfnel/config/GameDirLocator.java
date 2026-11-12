package org.wyx6669036.wtfnel.config;

import org.wyx6669036.wtfnel.log.Log;
import org.wyx6669036.wtfnel.util.AppPaths;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行时解析 .minecraft 根目录，避免把某台机器的盘符布局写死。
 * shadow.json 里显式填了 GameDir 就优先用它；否则在每块固定盘上探测已有安装，
 * 都没有就在可写盘上新建一棵（先 exe 所在盘，再 C:）。结果写回 {@link ShadowConfig#setGameDir}。
 *
 * <p>移植自 WtfNel.Core/Configuration/GameDirLocator.cs。</p>
 */
public final class GameDirLocator {

    private static final String[] RELATIVE_LAYOUTS = {
            "MCLDownload\\Game\\.minecraft",
            ".minecraft",
            "Game\\.minecraft",
    };

    private GameDirLocator() {
    }

    public static String resolve(ShadowConfig cfg) {
        if (cfg.getGameDir() != null && !cfg.getGameDir().isBlank()) {
            Path pinned = Path.of(cfg.getGameDir()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(pinned);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建游戏目录 " + pinned + " - " + e.getMessage(), e);
            }
            cfg.setGameDir(pinned.toString());
            Log.info("[gamedir] 使用配置目录 " + pinned);
            return pinned.toString();
        }

        List<Path> candidates = enumerateCandidates();
        for (Path dir : candidates) {
            if (Files.isDirectory(dir.resolve("versions").resolve(cfg.getVersionId()))) {
                cfg.setGameDir(dir.toString());
                Log.info("[gamedir] 探测到已有安装（含 " + cfg.getVersionId() + "）" + dir);
                return dir.toString();
            }
        }

        for (Path dir : candidates) {
            if (Files.isDirectory(dir.resolve("versions")) || Files.isDirectory(dir)) {
                cfg.setGameDir(dir.toString());
                Log.info("[gamedir] 探测到已有目录 " + dir);
                return dir.toString();
            }
        }

        String created = createFresh();
        cfg.setGameDir(created);
        Log.info("[gamedir] 未找到已有安装，新建 " + created);
        return created;
    }

    private static List<Path> enumerateCandidates() {
        List<Path> list = new ArrayList<>();
        Path base = appBaseDirectory();
        for (String layout : RELATIVE_LAYOUTS) {
            list.add(base.resolve(layout).toAbsolutePath().normalize());
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            list.add(Path.of(appData, ".minecraft"));
        }

        for (Path root : readyDrives()) {
            for (String layout : RELATIVE_LAYOUTS) {
                list.add(root.resolve(layout));
            }
        }
        return list;
    }

    /** 只取就绪的固定盘（U 盘/光驱跳过）。 */
    private static List<Path> readyDrives() {
        List<Path> drives = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return drives;
        }
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            Path root = Path.of(letter + ":\\");
            try {
                FileStore store = Files.getFileStore(root);
                if (store.isReadOnly()) {
                    continue;
                }
                if (!Files.isDirectory(root)) {
                    continue;
                }
                drives.add(root);
            } catch (Exception ignored) {
                // 盘不存在或不可访问，跳过
            }
        }
        return drives;
    }

    private static String createFresh() {
        Set<String> targets = new LinkedHashSet<>();
        Path base = appBaseDirectory();
        Path exeRoot = base.getRoot();
        if (exeRoot != null) {
            targets.add(exeRoot.toString());
        }
        targets.add("C:\\");
        for (Path drive : readyDrives()) {
            targets.add(drive.toString());
        }

        for (String root : targets) {
            Path dir = Path.of(root).resolve("MCLDownload").resolve("Game").resolve(".minecraft");
            try {
                Files.createDirectories(dir);
                return dir.toAbsolutePath().normalize().toString();
            } catch (Exception e) {
                Log.debug("[gamedir] 不能在 " + root + " 创建: " + e.getMessage());
            }
        }

        Path fallback = base.resolve("MCLDownload").resolve("Game").resolve(".minecraft");
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建游戏目录 " + fallback + " - " + e.getMessage(), e);
        }
        return fallback.toAbsolutePath().normalize().toString();
    }

    /** 与 C# 的 AppContext.BaseDirectory 对齐：程序所在目录，而不是当前工作目录。 */
    private static Path appBaseDirectory() {
        return AppPaths.baseDirectory();
    }
}
