package org.wyx6669036.wtfnel.injection;

import java.util.function.Consumer;

/**
 * 一次注入的可选参数。移植自 WtfNel.Core/Injection/InjectionResult.cs 里的 InjectionOptions。
 *
 * <p>C# 用 init-only 属性，Java 侧改成可变字段 + 链式 setter，默认值与原文一致。</p>
 */
public final class InjectionOptions {

    private InjectionStrategy strategy = InjectionStrategy.AUTO;
    private int waitMs = 15000;
    private long parameter;
    private boolean fallbackToLoadLibrary = true;
    private String initExport;
    private Consumer<String> log;

    public InjectionStrategy strategy() {
        return strategy;
    }

    public InjectionOptions strategy(InjectionStrategy value) {
        this.strategy = value;
        return this;
    }

    /** 等待远程线程结束的上限。超时在反射注入里算成功，在调用桩里算失败。 */
    public int waitMs() {
        return waitMs;
    }

    public InjectionOptions waitMs(int value) {
        this.waitMs = value;
        return this;
    }

    /** 传给远程线程的参数（CreateRemoteThread 的 lpParameter）。 */
    public long parameter() {
        return parameter;
    }

    public InjectionOptions parameter(long value) {
        this.parameter = value;
        return this;
    }

    public boolean fallbackToLoadLibrary() {
        return fallbackToLoadLibrary;
    }

    public InjectionOptions fallbackToLoadLibrary(boolean value) {
        this.fallbackToLoadLibrary = value;
        return this;
    }

    /** 加载完成后要调用的初始化导出名，例如 WtfNelProxyInit。 */
    public String initExport() {
        return initExport;
    }

    public InjectionOptions initExport(String value) {
        this.initExport = value;
        return this;
    }

    public Consumer<String> log() {
        return log;
    }

    public InjectionOptions log(Consumer<String> value) {
        this.log = value;
        return this;
    }

    /** 实际写日志的方法：没设置就静默，与 C# 的 options.Log ?? (_ => {}) 对应。 */
    void emit(String message) {
        Consumer<String> sink = log;
        if (sink != null) {
            sink.accept(message);
        }
    }
}
