package org.wyx6669036.wtfnel.injection;

/**
 * 一次注入的结果。移植自 WtfNel.Core/Injection/InjectionResult.cs 的 InjectionResult 记录。
 */
public record InjectionResult(boolean success, InjectionStrategy strategy, String detail, long elapsedMs,
                              long remoteModuleBase, Throwable error) {

    public static InjectionResult fail(InjectionStrategy strategy, String detail, long elapsedMs) {
        return new InjectionResult(false, strategy, detail, elapsedMs, 0, null);
    }

    public static InjectionResult fail(InjectionStrategy strategy, String detail, long elapsedMs,
                                        long remoteModuleBase) {
        return new InjectionResult(false, strategy, detail, elapsedMs, remoteModuleBase, null);
    }

    public static InjectionResult fail(InjectionStrategy strategy, String detail, long elapsedMs,
                                        long remoteModuleBase, Throwable error) {
        return new InjectionResult(false, strategy, detail, elapsedMs, remoteModuleBase, error);
    }

    public static InjectionResult ok(InjectionStrategy strategy, String detail, long elapsedMs) {
        return new InjectionResult(true, strategy, detail, elapsedMs, 0, null);
    }

    public static InjectionResult ok(InjectionStrategy strategy, String detail, long elapsedMs,
                                     long remoteModuleBase) {
        return new InjectionResult(true, strategy, detail, elapsedMs, remoteModuleBase, null);
    }

    /** 换掉 Detail、其余字段照旧（对应 C# 的 result with { Detail = ... }）。 */
    public InjectionResult withDetail(String newDetail) {
        return new InjectionResult(success, strategy, newDetail, elapsedMs, remoteModuleBase, error);
    }

    @Override
    public String toString() {
        return (success ? "成功" : "失败") + " [" + strategy.label() + "] " + detail
                + String.format(" (%.0f ms)", (double) elapsedMs);
    }
}
