package org.wyx6669036.wtfnel.injection;

/** 注入策略。移植自 WtfNel.Core/Injection/InjectionResult.cs 里的 InjectionStrategy 枚举。 */
public enum InjectionStrategy {
    /** 有 ReflectiveLoader 导出就走反射注入，否则落盘 LoadLibrary。 */
    AUTO("Auto"),
    /** 把文件写进目标进程，远程线程直接跳到 ReflectiveLoader。 */
    REFLECTIVE("Reflective"),
    /** 自己铺节、修重定位与导入表，不依赖 payload 自身的加载器。 */
    MANUAL_MAP("ManualMap"),
    /** 让目标进程自己 LoadLibrary 一个磁盘上的 DLL。 */
    LOAD_LIBRARY("LoadLibrary");

    private final String label;

    InjectionStrategy(String label) {
        this.label = label;
    }

    /** 与 C# 枚举 ToString() 一致的名字，日志里直接用。 */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
