# 开发指南 (Development)

本文档整理了针对开发者的构建与调试指南，适用于有意向为 Kototoro 贡献代码的工程师。

---

## 环境要求

- **Android Studio** 2022.3+
- **JDK** 17+（推荐 JDK 17 或 JDK 21）
- **Android SDK** API 33+
- **Gradle** 9.0+（工程内置 Gradle Wrapper）

---

## 常用构建命令

### 构建 Debug APK
```bash
./gradlew :app:assembleDebug
```
*注：Debug 构建仅针对 `arm64-v8a` 单一架构编译以极大提升速度。*

### 构建 Release APK
```bash
./gradlew :app:assembleRelease --no-daemon
```

### 仅编译 Kotlin 检查语法
```bash
./gradlew :app:compileDebugKotlin
```

### 运行单元测试
```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

运行单个测试用例：
```bash
./gradlew :app:testDebugUnitTest --tests "org.skepsun.kototoro.ClassName.methodName" --no-daemon
```

---

## 跨平台核心（Kotlin Multiplatform）

Android、Windows、Linux 以及 iOS 共享一套 KMP 核心模块：
- `core-db`：Room 数据库定义与迁移（Migrations）
- `core-domain`：业务模型、归一逻辑与算法
- `core-backup`：备份与多端还原合并
- `core-net`：网络请求与 Nostr 中继连接
- `core-source` / `core-library` / `core-local`：内容源协议与抽象书库
- `reader-core` / `reader-novel`：阅读器核心几何与排版
- `core-ui`：Android 与桌面端共享的 Compose Multiplatform UI 组件

通过以下命令校验 KMP 模块跨端通用性：
```bash
./gradlew :<module>:compileCommonMainKotlinMetadata
./gradlew :<module>:jvmTest
```

---

## Windows 与桌面端开发

桌面模块（`desktop-app`）为可选装配（`-PwithDesktopApp`），默认 Android 构建不会包含它们。桌面构建需要 JDK 21+。

### 源码运行调试
```powershell
./desktop-app/run-windows.ps1 -CompatibilityDirectory "build/windows-ci-compatibility" -JavaVersion 21
```

### 构建绿色运行目录
```powershell
./gradlew.bat -PwithDesktopApp -PwithWindowsDistribution `
  "-PmihonCompatibilityDirectory=build/windows-ci-compatibility" -PmihonCompatibilityJavaVersion=21 `
  :desktop-app:createDistributable --no-configuration-cache
```

产物路径：`desktop-app/build/compose/binaries/main/app/Kototoro/Kototoro.exe`

---

## 文档站点开发 (VitePress)

Kototoro 官方文档采用 VitePress 驱动，并自动部署在 GitHub Pages 上。

### 本地启动文档预览
```bash
npm ci
npm run docs:dev
```

### 静态构建检验
```bash
npm run docs:build
```

---

## 贡献期望

- 保持改动范围聚焦，遵循 Conventional Commits（`feat:`, `fix:`, `docs:` 等）。
- 提交 PR 前在本地完成编译与测试验证。
- 用户可见的功能变化需同步更新文档。

---

## 相关文档

- [贡献准则](./contributing.md)
- [Issue 反馈指引](./issue-submission-guide.md)
- [快速入门指南](./getting-started.md)
- [架构总览](../architecture/architecture-review.md)
- [架构演进路线](../architecture/architecture-roadmap.md)
