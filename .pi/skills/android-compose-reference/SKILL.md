---
name: android-compose-reference
description: >
  Jetpack Compose 官方示例与最佳实践参考。涵盖 Compose UI 实现、动画、手势、导航、Material3 主题、
  性能优化、测试等所有方面。安卓开发第一要义：参考官方文档和案例！
  Triggers: "Compose 怎么写", "Compose 动画", "Compose 导航", "Compose 性能", "Compose 主题",
  "Jetpack Compose sample", "Material3 Compose", "Compose testing", "Compose best practice"
---

# Jetpack Compose 官方参考索引

> **安卓开发第一要义：参考官方文档和案例！**
>
> Compose 是现代 Android UI 开发的推荐方式。以下仓库由 Android 团队维护，
> 是学习 Compose 最佳实践的第一手资料。

---

## 📦 核心官方仓库

### 1. [compose-samples](https://github.com/android/compose-samples) ⭐23.2k
**官方的 Jetpack Compose 示例集合** — Compose 学习首选仓库。

内含多个完整应用示例：

| 示例应用 | 展示内容 |
|----------|---------|
| **JetNews** | 新闻阅读应用 — Compose 基础：列表、导航、主题 |
| **Jetchat** | 聊天应用 — 高级 Compose：动画、状态提升、自定义布局 |
| **JetSnack** | 电商应用 — 复杂布局、图片处理、BottomSheet |
| **Crane** | 旅行应用 — Material3、自定义绘制、MapView 集成 |
| **Owl** | 教育应用 — 自定义主题、深色模式、复杂排版 |
| **Reply** | 邮件应用 — 自适应布局（手机/平板/折叠屏）、WindowSizeClass |

**关键学习路径**：
```
compose-samples/
├── JetNews/       # 💡 新手入门：基础 Compose
├── Crane/         # 🎨 Material3 + 自定义主题
├── Jetchat/       # 🚀 进阶：动画 + 状态管理
├── Reply/         # 📱 自适应布局
└── Owl/           # 🎭 高级主题定制
```

---

### 2. [nowinandroid](https://github.com/android/nowinandroid) ⭐21.4k
**生产级 Compose 应用** — 架构参考首选。

展示内容：
- 完整的 Compose + Material3 应用
- 模块化架构（`:core:ui`, `:feature:*`, `:core:data` 等）
- UDF (Unidirectional Data Flow) 模式
- Gradle 版本目录（Version Catalog）
- 完整的 CI/CD 流程
- Compose 测试（UI 测试 + 截图测试）

---

### 3. [architecture-samples](https://github.com/android/architecture-samples) ⭐45.7k
**架构模式演进** — 理解 Compose 时代的架构。

关键分支：
- `main`：UDF + StateFlow，当前推荐
- `views`：经典 View 架构
- `compose`：Compose 架构演进

---

### 4. [sunflower](https://github.com/android/sunflower) ⭐17.8k
**View → Compose 迁移** — 适合从 View 迁移到 Compose 的项目。

展示 View 代码如何逐步迁移到 Compose。

---

### 5. [user-interface-samples](https://github.com/android/user-interface-samples) ⭐4.6k
**UI 专项示例** — 深入 UI 细节。

关键示例：
- `WindowInsetsAnimation` — 键盘动画、系统栏适配
- `MotionLayout` — 复杂交互动画
- `CustomLayout` — 自定义 Compose Layout
- `Accessibility` — 无障碍
- `Insets` — WindowInsets 处理

---

## 🧭 导航 (Navigation)

| 仓库 | 说明 |
|------|------|
| [nav3-recipes](https://github.com/android/nav3-recipes) ⭐1.3k | **Navigation3 常见用例**：类型安全导航、深层链接、BottomNavigation 集成、返回栈管理等 |
| [codelab-android-navigation](https://github.com/android/codelab-android-navigation) ⭐633 | Navigation Codelab |

**Navigation3 类型安全导航（推荐）**：
```kotlin
// 定义路由（类型安全，无需字符串）
@Serializable data class Profile(val id: String)

// 导航
navController.navigate(Profile(id = "123"))
```

---

## 🎨 Material3 主题

参考仓库：
- [compose-samples/Crane](https://github.com/android/compose-samples/tree/main/Crane) — Material3 颜色方案
- [compose-samples/Owl](https://github.com/android/compose-samples/tree/main/Owl) — 自定义主题
- [nowinandroid](https://github.com/android/nowinandroid) — 生产级主题配置

官方文档：[Material Design 3 for Compose](https://developer.android.com/compose/material3)

---

## 🎬 动画

参考仓库：
- [compose-samples/Jetchat](https://github.com/android/compose-samples/tree/main/Jetchat) — 复杂动画
- [user-interface-samples](https://github.com/android/user-interface-samples) — WindowInsetsAnimation

Compose 动画 API 层级：
```
animate*AsState       → 简单属性动画（推荐起手）
AnimatedVisibility    → 显示/隐藏动画
AnimatedContent       → 内容切换动画
Crossfade            → 淡入淡出
updateTransition      → 多属性协同动画
rememberInfiniteTransition → 无限循环动画
AnimationSpec        → 自定义动画曲线（tween, spring, keyframes）
```

---

## 🧪 Compose 测试

参考仓库：[testing-samples](https://github.com/android/testing-samples) ⭐9.3k

Compose 测试三件套：
```kotlin
// 1. 元素查找
composeTestRule.onNodeWithText("Submit")

// 2. 交互
composeTestRule.onNodeWithText("Submit").performClick()

// 3. 断言
composeTestRule.onNodeWithText("Success").assertIsDisplayed()
```

含截图测试（Screenshot Testing）、语义测试等高级主题。

---

## ⚡ Compose 性能

参考仓库：[performance-samples](https://github.com/android/performance-samples) ⭐1.4k

关键主题：
- **Baseline Profile** — 提升 Compose 首帧速度
- **Stability 标记** — 使用 `@Stable` / `@Immutable`
- **重组优化** — 减少不必要的重组
- **延迟布局** — `LazyColumn` / `LazyRow` 优化
- **remember/derivedStateOf** — 状态缓存
- **Compose Compiler Metrics** — 编译期分析

---

## 📱 自适应布局

| 仓库 | 说明 |
|------|------|
| [adaptive-apps-samples](https://github.com/android/adaptive-apps-samples) | 自适应布局示例 |
| [large-screen-codelabs](https://github.com/android/large-screen-codelabs) | 大屏/折叠屏适配 |

关键 API：
- `WindowSizeClass` — 根据窗口尺寸调整布局
- `BoxWithConstraints` — 约束感知布局
- `LocalConfiguration.current` — 配置变化响应

---

## 🚀 本项目 (Kototoro) Compose 开发工作流

当需要实现 Compose 相关功能时:

```
1. compose-samples 中搜索相似 UI → 找到参考实现
2. nowinandroid 中搜索架构模式 → 理解状态管理
3. user-interface-samples 中搜索细节 → 手势/Insets/动画
4. 在官方实现基础上适配 → 而非从零开始
```

**禁止**：从非官方博客/教程中复制代码而不验证。任何第三方方案都要和官方示例对比。

---

## 📚 补充链接

- [Compose 官方文档](https://developer.android.com/compose)
- [Compose API 参考](https://developer.android.com/reference/kotlin/androidx/compose/package-summary)
- [Compose Material3](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)
- [Compose 路线图](https://developer.android.com/jetpack/compose/roadmap)
- [Compose 性能指南](https://developer.android.com/jetpack/compose/performance)
