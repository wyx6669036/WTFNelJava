package org.wyx6669036.wtfnel.launch;

import com.sun.jna.platform.win32.Advapi32Util;

/**
 * 提权检测。移植自 WtfNel.Core/Launch/Elevation.cs。
 *
 * <p>官方启动器是提权跑的，要把客户端挂到它下面（{@link ParentedLauncher} 的父进程伪造），
 * 本进程的完整性级别必须不低于它，否则 OpenProcess 会返回拒绝访问。这里只做判断，
 * 是否继续由调用方决定 —— 和原版一样不主动要求提权。</p>
 */
public final class Elevation {

    private Elevation() {
    }

    /** 当前进程是否以管理员身份运行。非 Windows、JNA 不可用时一律 false。 */
    public static boolean isElevated() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return false;
        }
        try {
            // 与 WindowsPrincipal.IsInRole(Administrator) 等价：读令牌的 TokenElevation
            return Advapi32Util.isCurrentProcessElevated();
        } catch (Throwable ignored) {
            // UnsatisfiedLinkError / Win32Exception 都当成未提权
            return false;
        }
    }
}
