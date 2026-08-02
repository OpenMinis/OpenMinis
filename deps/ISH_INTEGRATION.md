# iSH-ARM64 iOS 集成指南

本文档说明如何将 iSH-ARM64 静态库集成到 iOS 项目中，实现在 iOS 设备上运行 Linux aarch64 (ARM64) 环境。

> **仓库**: [OpenMinis/ish-arm64](https://github.com/OpenMinis/ish-arm64) (分支: `feature-arm64`)

## 目录

1. [项目配置](#1-项目配置)
2. [资源文件](#2-资源文件)
3. [桥接头文件](#3-桥接头文件)
4. [初始化代码](#4-初始化代码)
5. [终端 I/O](#5-终端-io)
6. [完整示例](#6-完整示例)

---

## 1. 项目配置

### 1.1 添加静态库

在 Xcode 中，将以下库添加到 **Target → Build Phases → Link Binary With Libraries**:

```
deps/libs/libish.a
deps/libs/libish_emu.a
deps/libs/libfakefs.a
```

### 1.2 添加系统库依赖

iSH 依赖以下系统框架:

```
libsqlite3.tbd
```

### 1.3 配置 Header Search Paths

在 **Build Settings → Header Search Paths** 中添加:

```
$(PROJECT_DIR)/../deps/include
```

### 1.4 配置 Other Linker Flags

```
-ObjC
-all_load
```

### 1.5 配置 C 编译选项

在 **Build Settings → Other C Flags** 添加:

```
-DISH_INTERNAL
```

---

## 2. 资源文件

### 2.1 添加资源到项目

将以下资源添加到 Xcode 项目 (Copy Bundle Resources):

```
deps/resources/alpine-rootfs.zip    # Alpine Linux rootfs
deps/resources/libvdso.so.elf       # VDSO (可选，内置版本通常够用)
```

### 2.2 首次启动解压 Rootfs

```swift
import Foundation

class RootfsManager {
    static let shared = RootfsManager()

    var rootfsPath: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("alpine-rootfs")
    }

    var dataPath: URL {
        return rootfsPath.appendingPathComponent("data")
    }

    var isInstalled: Bool {
        return FileManager.default.fileExists(atPath: rootfsPath.path)
    }

    func installIfNeeded() throws {
        guard !isInstalled else { return }

        guard let zipURL = Bundle.main.url(forResource: "alpine-rootfs", withExtension: "zip") else {
            throw NSError(domain: "RootfsManager", code: 1,
                         userInfo: [NSLocalizedDescriptionKey: "alpine-rootfs.zip not found"])
        }

        // 解压到 Documents
        try FileManager.default.unzipItem(at: zipURL, to: rootfsPath.deletingLastPathComponent())
    }
}
```

> **注意**: 需要添加 ZIP 解压库，如 [ZIPFoundation](https://github.com/weichsel/ZIPFoundation)

---

## 3. 桥接头文件

### 3.1 创建 Bridging Header

创建 `MinisApp-Bridging-Header.h`:

```c
#ifndef MinisApp_Bridging_Header_h
#define MinisApp_Bridging_Header_h

// iSH Core
#include "ish/misc.h"
#include "ish/debug.h"

// Kernel
#include "ish/kernel/init.h"
#include "ish/kernel/task.h"
#include "ish/kernel/calls.h"
#include "ish/kernel/fs.h"
#include "ish/kernel/errno.h"

// File System
#include "ish/fs/fd.h"
#include "ish/fs/tty.h"
#include "ish/fs/fake.h"
#include "ish/fs/dev.h"
#include "ish/fs/devices.h"

// Platform
#include "ish/platform/platform.h"

#endif
```

### 3.2 在 Build Settings 中配置

**Objective-C Bridging Header**:
```
$(PROJECT_DIR)/MinisApp-Bridging-Header.h
```

---

## 4. 初始化代码

### 4.1 内核初始化 (Objective-C)

创建 `ISHKernel.m`:

```objc
#import <Foundation/Foundation.h>
#include "ish/kernel/init.h"
#include "ish/kernel/task.h"
#include "ish/kernel/calls.h"
#include "ish/kernel/fs.h"
#include "ish/fs/fake.h"
#include "ish/fs/tty.h"
#include "ish/fs/dev.h"
#include "ish/fs/devices.h"

// 外部钩子
extern void (*exit_hook)(struct task *task, int code);

// 进程退出处理
static void handle_exit(struct task *task, int code) {
    pid_t pid = task->pid;
    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter]
            postNotificationName:@"ISHProcessExited"
            object:nil
            userInfo:@{@"pid": @(pid), @"code": @(code)}];
    });
}

@interface ISHKernel : NSObject
+ (int)bootWithRootPath:(NSString *)rootPath;
+ (int)executeCommand:(NSArray<NSString *> *)command;
@end

@implementation ISHKernel

+ (int)bootWithRootPath:(NSString *)rootPath {
    int err;

    // 1. 挂载根文件系统
    NSString *dataPath = [rootPath stringByAppendingPathComponent:@"data"];
    err = mount_root(&fakefs, dataPath.fileSystemRepresentation);
    if (err < 0) {
        NSLog(@"❌ mount_root failed: %d", err);
        return err;
    }
    NSLog(@"✅ Root filesystem mounted");

    // 2. 成为 init 进程
    err = become_first_process();
    if (err < 0) {
        NSLog(@"❌ become_first_process failed: %d", err);
        return err;
    }
    current->thread = pthread_self();
    NSLog(@"✅ Init process created (PID 1)");

    // 3. 创建设备节点
    [self createDeviceNodes];

    // 4. 挂载 proc 文件系统
    err = do_mount(&procfs, "proc", "/proc", "", 0);
    if (err < 0) {
        NSLog(@"⚠️ Failed to mount /proc: %d", err);
    }

    // 5. 设置退出钩子
    exit_hook = handle_exit;

    NSLog(@"✅ ISH Kernel initialized");
    return 0;
}

+ (void)createDeviceNodes {
    // 创建 /dev 目录结构
    generic_mkdirat(AT_PWD, "/dev", 0755);
    generic_mkdirat(AT_PWD, "/dev/pts", 0755);

    // TTY 设备
    generic_mknodat(AT_PWD, "/dev/tty1", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 1));
    generic_mknodat(AT_PWD, "/dev/tty2", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 2));
    generic_mknodat(AT_PWD, "/dev/console", S_IFCHR|0666, dev_make(TTY_CONSOLE_MAJOR, 1));
    generic_mknodat(AT_PWD, "/dev/tty", S_IFCHR|0666, dev_make(TTY_MAJOR, 0));
    generic_mknodat(AT_PWD, "/dev/ptmx", S_IFCHR|0666, dev_make(TTY_ALTERNATE_MAJOR, 2));

    // 内存设备
    generic_mknodat(AT_PWD, "/dev/null", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_NULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/zero", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_ZERO_MINOR));
    generic_mknodat(AT_PWD, "/dev/full", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_FULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/random", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_RANDOM_MINOR));
    generic_mknodat(AT_PWD, "/dev/urandom", S_IFCHR|0666, dev_make(MEM_MAJOR, DEV_URANDOM_MINOR));

    NSLog(@"✅ Device nodes created");
}

+ (int)executeCommand:(NSArray<NSString *> *)command {
    if (command.count == 0) return -1;

    // 构建参数
    char argv[4096];
    size_t pos = 0;
    for (NSString *arg in command) {
        const char *carg = arg.UTF8String;
        size_t len = strlen(carg) + 1;
        if (pos + len >= sizeof(argv)) break;
        memcpy(argv + pos, carg, len);
        pos += len;
    }
    argv[pos] = '\0';

    // 设置环境变量
    const char *envp = "TERM=xterm-256color\0HOME=/root\0PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin\0";

    // 执行命令
    int err = do_execve(command[0].UTF8String, (int)command.count, argv, envp);
    if (err < 0) {
        NSLog(@"❌ do_execve failed: %d", err);
        return err;
    }

    // 启动任务
    task_start(current);
    NSLog(@"✅ Process started: %@", command[0]);

    return 0;
}

@end
```

### 4.2 头文件 `ISHKernel.h`

```objc
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSNotificationName const ISHProcessExitedNotification;

@interface ISHKernel : NSObject

/// 初始化内核并挂载根文件系统
/// @param rootPath fakefs 根目录路径 (包含 data/ 和 meta.db)
/// @return 0 成功，负数为错误码
+ (int)bootWithRootPath:(NSString *)rootPath;

/// 执行命令
/// @param command 命令和参数数组，如 @[@"/bin/sh", @"-l"]
/// @return 0 成功，负数为错误码
+ (int)executeCommand:(NSArray<NSString *> *)command;

@end

NS_ASSUME_NONNULL_END
```

---

## 5. 终端 I/O

### 5.1 自定义 TTY 驱动

要实现终端交互，需要注册自定义 TTY 驱动:

```objc
#include "ish/fs/tty.h"

// TTY 操作回调
static int my_tty_write(struct tty *tty, const void *buf, size_t len, bool blocking) {
    // 将输出发送到 UI
    NSData *data = [NSData dataWithBytes:buf length:len];
    dispatch_async(dispatch_get_main_queue(), ^{
        // 更新 TerminalView
        [[NSNotificationCenter defaultCenter]
            postNotificationName:@"ISHTerminalOutput"
            object:nil
            userInfo:@{@"data": data}];
    });
    return (int)len;
}

static int my_tty_init(struct tty *tty) {
    // 初始化 TTY
    return 0;
}

static void my_tty_cleanup(struct tty *tty) {
    // 清理 TTY
}

// 定义 TTY 驱动
static struct tty_driver_ops my_tty_ops = {
    .init = my_tty_init,
    .write = my_tty_write,
    .cleanup = my_tty_cleanup,
};

// 注册驱动
DEFINE_TTY_DRIVER(my_console_driver, &my_tty_ops, TTY_CONSOLE_MAJOR, 8);

// 在 boot 时注册
+ (void)registerTTYDriver {
    tty_drivers[TTY_CONSOLE_MAJOR] = &my_console_driver;
}
```

### 5.2 发送用户输入

```objc
+ (void)sendInput:(NSData *)data toTTY:(int)ttyNum {
    struct tty *tty = tty_get(TTY_CONSOLE_MAJOR, ttyNum);
    if (tty) {
        tty_input(tty, data.bytes, data.length, 0);
        tty_release(tty);
    }
}
```

---

## 6. 完整示例

### 6.1 AppDelegate.swift

```swift
import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        // 安装 rootfs
        do {
            try RootfsManager.shared.installIfNeeded()
        } catch {
            print("❌ Failed to install rootfs: \(error)")
            return false
        }

        // 启动内核
        let rootPath = RootfsManager.shared.rootfsPath.path
        let err = ISHKernel.boot(withRootPath: rootPath)
        if err < 0 {
            print("❌ Kernel boot failed: \(err)")
            return false
        }

        // 监听进程退出
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ISHProcessExited"),
            object: nil,
            queue: .main
        ) { notification in
            if let userInfo = notification.userInfo,
               let pid = userInfo["pid"] as? Int32,
               let code = userInfo["code"] as? Int32 {
                print("Process \(pid) exited with code \(code)")
            }
        }

        return true
    }
}
```

### 6.2 TerminalViewController.swift

```swift
import UIKit

class TerminalViewController: UIViewController {

    private var textView: UITextView!

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
        setupNotifications()
        startShell()
    }

    private func setupUI() {
        textView = UITextView(frame: view.bounds)
        textView.backgroundColor = .black
        textView.textColor = .green
        textView.font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
        textView.isEditable = true
        textView.autocapitalizationType = .none
        textView.autocorrectionType = .no
        view.addSubview(textView)
    }

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("ISHTerminalOutput"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let data = notification.userInfo?["data"] as? Data,
               let text = String(data: data, encoding: .utf8) {
                self?.appendOutput(text)
            }
        }
    }

    private func appendOutput(_ text: String) {
        textView.text += text
        let bottom = NSRange(location: textView.text.count - 1, length: 1)
        textView.scrollRangeToVisible(bottom)
    }

    private func startShell() {
        // 创建 stdio
        let err1 = create_stdio("/dev/tty1", TTY_CONSOLE_MAJOR, 1)
        if err1 < 0 {
            print("❌ create_stdio failed: \(err1)")
            return
        }

        // 启动 shell
        let err2 = ISHKernel.execute(command: ["/bin/sh", "-l"])
        if err2 < 0 {
            print("❌ Failed to start shell: \(err2)")
        }
    }
}
```

---

## 注意事项

### 内存管理

- iSH 内核运行在独立的 pthread 上
- 使用 `dispatch_async` 与主线程通信
- 避免在内核线程直接操作 UI

### 线程安全

- `current` 是 thread-local 变量，指向当前进程
- 不要在主线程调用可能阻塞的内核函数
- 使用 Notification 进行内核 → UI 通信

### 文件系统

- fakefs 使用 SQLite 存储元数据
- 实际文件存储在 `data/` 目录
- 确保 Documents 目录有足够空间

### 调试

启用日志:
```c
// 在编译时添加
-DDEBUG_strace=1
-DDEBUG_verbose=1
```

---

## 参考资料

- [iSH-ARM64 仓库](https://github.com/OpenMinis/ish-arm64) (feature-arm64 分支)
- [iSH 原始仓库](https://github.com/ish-app/ish)
- [iSH Wiki](https://github.com/ish-app/ish/wiki)
- [Alpine Linux](https://alpinelinux.org/) (使用 aarch64 版本)
