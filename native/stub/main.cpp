// WPFLauncher 替身（stub）
//
// 作用：Java 侧用 PROC_THREAD_ATTRIBUTE_PARENT_PROCESS 把网易客户端挂到「启动器」名下，
// 所以要有一个叫 WPFLauncher.exe 的进程一直活着当父进程。这个程序就是这个进程：
//
//   1) 版本资源由 stub.rc 写死成 NetEase / 我的世界启动器 / 1.15.34.21425，
//      LauncherIdentity.isAligned 会因为四项全中就跳过 patchBinary；
//   2) 启动后进入等待循环，默认盯着「启动自己的那个进程」，
//      父进程一退出就跟着退出（宿主崩了不会留孤儿进程）；
//   3) 命令行可以显式指定别的等待目标，方便脱离 Java 手工试：
//        WPFLauncher.exe --wait-pid 1234 --wait-pid 5678 --timeout-ms 60000 --log stub.log
//      --no-parent-watch 关掉默认的父进程监视（此时没有等待目标就一直不退出）。
//
// 退出码恒为 0；被 TerminateProcess（ProcessGuard 收尾就是这条路径）时无所谓退出码。

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>

#include <string>
#include <vector>

namespace {

constexpr DWORD kForever = 0xFFFFFFFFu;
constexpr size_t kMaxWaitHandles = 60;  // MAXIMUM_WAIT_OBJECTS 是 64，留点余量

struct Options {
    std::vector<DWORD> waitPids;
    bool watchParent = true;
    DWORD timeoutMs = kForever;
    std::wstring logPath;
};

std::string toUtf8(const std::wstring& text) {
    if (text.empty()) {
        return std::string();
    }
    int need = WideCharToMultiByte(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()),
                                   nullptr, 0, nullptr, nullptr);
    if (need <= 0) {
        return std::string();
    }
    std::string out(static_cast<size_t>(need), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()),
                        out.data(), need, nullptr, nullptr);
    return out;
}

// 只在给了 --log 时才写文件：替身是 GUI 子系统程序，没有控制台可打。
void logLine(const Options& options, const std::wstring& text) {
    if (options.logPath.empty()) {
        return;
    }
    HANDLE file = CreateFileW(options.logPath.c_str(), FILE_APPEND_DATA,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                              OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return;
    }
    SYSTEMTIME now{};
    GetLocalTime(&now);
    wchar_t stamp[64];
    swprintf_s(stamp, L"[%04d-%02d-%02d %02d:%02d:%02d.%03d] ",
               now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute, now.wSecond,
               now.wMilliseconds);
    std::string line = toUtf8(std::wstring(stamp) + text + L"\r\n");
    DWORD written = 0;
    WriteFile(file, line.data(), static_cast<DWORD>(line.size()), &written, nullptr);
    CloseHandle(file);
}

// 拿自己的父进程 pid：CreateProcess 传进来的 th32ParentProcessID。
DWORD parentProcessId() {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return 0;
    }
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    const DWORD self = GetCurrentProcessId();
    DWORD parent = 0;
    if (Process32FirstW(snapshot, &entry)) {
        do {
            if (entry.th32ProcessID == self) {
                parent = entry.th32ParentProcessID;
                break;
            }
        } while (Process32NextW(snapshot, &entry));
    }
    CloseHandle(snapshot);
    return parent;
}

DWORD parseNumber(const std::wstring& text) {
    wchar_t* end = nullptr;
    unsigned long value = wcstoul(text.c_str(), &end, 10);
    if (end == text.c_str()) {
        return 0;
    }
    return static_cast<DWORD>(value);
}

Options parseOptions() {
    Options options;
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv == nullptr) {
        return options;
    }
    for (int i = 1; i < argc; ++i) {
        const std::wstring arg = argv[i];
        const bool hasValue = (i + 1 < argc);
        if (arg == L"--wait-pid" && hasValue) {
            const DWORD pid = parseNumber(argv[++i]);
            if (pid != 0) {
                options.waitPids.push_back(pid);
            }
        } else if (arg == L"--no-parent-watch") {
            options.watchParent = false;
        } else if (arg == L"--timeout-ms" && hasValue) {
            options.timeoutMs = parseNumber(argv[++i]);
        } else if (arg == L"--log" && hasValue) {
            options.logPath = argv[++i];
        }
    }
    LocalFree(argv);
    return options;
}

// 等待循环：把每个目标 pid 开成可等待句柄，谁先退出就把谁摘掉；
// 全部退出（或超时）才返回。
void waitUntilDone(const Options& options) {
    std::vector<DWORD> targets = options.waitPids;
    if (targets.empty() && options.watchParent) {
        const DWORD parent = parentProcessId();
        if (parent != 0) {
            targets.push_back(parent);
        }
    }

    std::vector<HANDLE> waits;
    for (DWORD pid : targets) {
        if (waits.size() >= kMaxWaitHandles) {
            break;
        }
        HANDLE handle = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION,
                                    FALSE, pid);
        if (handle != nullptr) {
            waits.push_back(handle);
        }
    }

    wchar_t buffer[256];
    swprintf_s(buffer, L"替身启动 pid=%lu 等待目标=%zu 超时=%s",
               GetCurrentProcessId(), waits.size(),
               options.timeoutMs == kForever ? L"无" : L"有限");
    logLine(options, buffer);

    if (waits.empty()) {
        logLine(options, L"没有可等待的目标，转入常驻");
    }

    const ULONGLONG started = GetTickCount64();
    while (true) {
        if (!waits.empty()) {
            const DWORD result = WaitForMultipleObjects(static_cast<DWORD>(waits.size()),
                                                        waits.data(), FALSE, 500);
            if (result >= WAIT_OBJECT_0 && result < WAIT_OBJECT_0 + waits.size()) {
                const size_t index = result - WAIT_OBJECT_0;
                CloseHandle(waits[index]);
                waits.erase(waits.begin() + static_cast<long long>(index));
                if (waits.empty()) {
                    logLine(options, L"等待目标全部退出");
                    break;
                }
            }
        } else {
            Sleep(500);
        }
        if (options.timeoutMs != kForever && GetTickCount64() - started >= options.timeoutMs) {
            logLine(options, L"到达 --timeout-ms，退出");
            break;
        }
    }

    for (HANDLE handle : waits) {
        CloseHandle(handle);
    }
}

}  // namespace

int WINAPI wWinMain(HINSTANCE /*instance*/, HINSTANCE /*previous*/, PWSTR /*commandLine*/, int /*show*/) {
    // 单实例不是必需的（Java 侧按镜像路径复用已经在跑的那个），
    // 但留一个命名互斥量方便排查「为什么有两个替身」。
    HANDLE mutex = CreateMutexW(nullptr, FALSE, L"Local\\WtfNelLauncherStub");
    if (mutex != nullptr && GetLastError() == ERROR_ALREADY_EXISTS) {
        // 已经有替身在跑：照样继续跑完等待逻辑，只是不再重复初始化。
        CloseHandle(mutex);
        mutex = nullptr;
    }

    const Options options = parseOptions();
    waitUntilDone(options);

    if (mutex != nullptr) {
        CloseHandle(mutex);
    }
    return 0;
}
