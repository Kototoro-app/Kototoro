---
name: android-architecture-reference
description: >
  Android 架构、依赖注入、数据层、测试规范官方参考。涵盖 UDF/MVI 模式、Hilt/Dagger、Room、Paging、
  DataStore、WorkManager、ViewModel、测试策略等。安卓开发第一要义：参考官方文档和案例！
  Triggers: "Android 架构", "MVVM", "MVI", "UDF", "Hilt", "Dagger", "Room", "Paging",
  "ViewModel", "WorkManager", "DataStore", "测试", "架构设计", "DI", "依赖注入"
---

# Android 架构与数据层官方参考

> **安卓开发第一要义：参考官方文档和案例！**

---

## 🏗️ 架构模式

### 主仓库：[architecture-samples](https://github.com/android/architecture-samples) ⭐45.7k

**官方推荐的架构演进路径**：

```
传统 MVP/MVVM → 推荐 UDF (Unidirectional Data Flow)
```

当前推荐模式（`main` 分支）：
- **UI Layer**：Jetpack Compose + ViewModel
- **Domain Layer**：UseCase（可选）
- **Data Layer**：Repository + DataSource + Room

核心原则：
- 数据向下流动（ViewModel → UI State → Compose）
- 事件向上流动（User Action → ViewModel → Repository）
- 状态通过 `StateFlow` 暴露
- UI 只负责渲染，不包含业务逻辑

```kotlin
// 推荐模式
@HiltViewModel
class TaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()
    
    fun onAction(action: TaskAction) { /* 处理用户操作 */ }
}
```

---

### 生产级参考：[nowinandroid](https://github.com/android/nowinandroid) ⭐21.4k

**💡 这是 Kototoro 最相关的单一参考仓库** — 一个仓库覆盖 Compose、Room、Hilt、Navigation、Coroutines、WorkManager、Paging、DataStore 八项核心技术栈。

**模块化架构参考**：
```
app/
├── :core:data           → 数据层（Room DAO、Entity、Repository、离线优先）
├── :core:datastore      → DataStore 偏好存储
├── :core:domain         → 领域层（UseCase）
├── :core:model          → 数据模型
├── :core:network        → 网络层（OkHttp + Retrofit + kotlinx-serialization）
├── :core:ui             → UI 基础组件（主题、导航、通用 Compose）
├── :core:data-test     → 测试数据
├── :core:testing       → 测试工具
├── :sync:work           → WorkManager 后台同步
├── :feature:for_you    → 推荐页 Feature
├── :feature:bookmarks  → 书签 Feature
├── :feature:topic      → 话题 Feature
├── :feature:settings   → 设置 Feature
└── :benchmark           → Macrobenchmark 性能测试
```

**构建系统**：
- Gradle Version Catalog（[gradle/libs.versions.toml](https://github.com/android/nowinandroid/blob/main/gradle/libs.versions.toml)）
- Convention Plugins（统一插件配置）
- Build Logic 模块

> ⚠️ **Kototoro 差异**：nowinandroid 使用 Kotlin DSL (`.kts`)，而 Kototoro 的 `app/build.gradle` 使用 Groovy DSL —— 学习架构和代码模式，但不要直接复制构建脚本。

**与本项目的技术栈对应**：
| NIA 模块 | Kototoro 对应 | 学习重点 |
|----------|-------------|---------|
| `:core:data` | manga 数据层 | Room DAO 模式、Entity 设计、Flow 查询 |
| `:core:network` | OkHttp 网络层 | 拦截器、缓存策略、离线处理 |
| `:core:ui` | Compose 基础组件 | Material3 主题、导航基础设施 |
| `:core:datastore` | 用户偏好 | 阅读设置、主题选择 |
| `:sync:work` | 章节下载/同步 | Worker 约束、唯一定期任务 |
| `:feature:*` | 各 Feature 模块 | 页面级 Composition、ViewModel |
| `:benchmark` | （建议添加） | 启动性能、滚动性能 |

---

## 💉 依赖注入：Hilt

官方 Hilt 参考在 **architecture-samples** 和 **nowinandroid** 中。

关键模式：
```kotlin
// 1. Application 级模块
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "app.db").build()
}

// 2. ViewModel 注入
@HiltViewModel
class FeatureViewModel @Inject constructor(
    private val repository: FeatureRepository,
) : ViewModel()

// 3. Compose 集成
@Composable
fun FeatureScreen(
    viewModel: FeatureViewModel = hiltViewModel(),
)
```

---

## 💾 数据层

### Room 数据库
参考：[codelab-android-room-with-a-view](https://github.com/android/codelab-android-room-with-a-view) ⭐768

```kotlin
@Entity(tableName = "items")
data class Item(
    @PrimaryKey val id: String,
    val title: String,
    val timestamp: Long,
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<Item>>
}

@Database(entities = [Item::class], version = 1)
abstract class AppDatabase : RoomDatabase()
```

### Paging 分页
参考：[codelab-android-paging](https://github.com/android/codelab-android-paging) ⭐512

```kotlin
// Room 分页
@Query("SELECT * FROM items ORDER BY timestamp DESC")
fun pagingSource(): PagingSource<Int, Item>

// Repository 层
fun getItems(): Flow<PagingData<Item>> = Pager(
    config = PagingConfig(pageSize = 20),
    pagingSourceFactory = { dao.pagingSource() }
).flow
```

### DataStore
参考：[storage-samples](https://github.com/android/storage-samples) ⭐1.7k

```kotlin
// Preferences DataStore
val Context.dataStore by preferencesDataStore(name = "settings")
```

---

## ⚡ WorkManager

参考：[codelab-android-workmanager](https://github.com/android/codelab-android-workmanager) ⭐559

```kotlin
// 定义 Worker
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repository: SyncRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            repository.sync()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

// 调度
val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
    .build()
WorkManager.getInstance(context).enqueue(request)
```

---

## 🧪 测试策略

参考：[testing-samples](https://github.com/android/testing-samples) ⭐9.3k

### 三层测试金字塔

```
         /\
        /UI\        10% - Compose UI 测试（少量端到端验证）
       /----\
      / 集成 \      20% - Hilt + Room 集成测试
     /--------\
    /  单元测试  \   70% - Repository / ViewModel 单元测试
   /------------\
```

### 本项目测试工具链

| 层级 | 工具 | 参考 |
|------|------|------|
| 单元测试 | JUnit5 + Kotest + MockK | [testing-samples](https://github.com/android/testing-samples) |
| 集成测试 | Hilt Testing + Room In-Memory | [nowinandroid](https://github.com/android/nowinandroid) |
| UI 测试 | Compose Testing + Espresso | [compose-samples](https://github.com/android/compose-samples) |
| Mock Web | MockWebServer (OkHttp) | 本项目已有 |
| 快照测试 | Roborazzi / Paparazzi | [nowinandroid](https://github.com/android/nowinandroid) |

### ViewModel 单元测试示例
```kotlin
@Test
fun `when data loaded, uiState shows items`() = runTest {
    val repo = mockk<FeatureRepository> { coEvery { getItems() } returns flowOf(testItems) }
    val viewModel = FeatureViewModel(repo)
    viewModel.uiState.test {
        assertEquals(expectItemCount, awaitItem().items.size)
    }
}
```

---

## 🚀 本项目架构决策参考

当遇到架构决策时：

```
1. architecture-samples   → 看推荐模式（UDF + StateFlow）
2. nowinandroid           → 看模块化划分、Gradle 配置
3. sunflower              → 看 Room + Paging + WorkManager 组合用法
4. testing-samples        → 看测试如何组织
```

**关键原则**：
- 不盲目跟风第三方方案，以官方为准
- 架构选择要匹配项目规模（Kototoro 不需要过度工程化）
- 每个决策都要能在官方示例中找到依据
