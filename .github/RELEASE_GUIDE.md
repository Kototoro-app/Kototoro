# GitHub 和 GitCode Release 发布指南

本项目支持通过 GitHub Actions 自动构建，并将 Release 同时发布到 GitHub 和 GitCode。

## 方式一：通过Git标签自动发布（推荐）

### 步骤

1. **确保代码已提交并推送到GitHub**
   ```bash
   git add .
   git commit -m "准备发布 v1.0.0"
   git push origin main
   ```

2. **创建并推送标签**
   ```bash
   # 创建标签（版本号格式：v主版本.次版本.修订号）
   git tag -a v1.0.0 -m "Release version 1.0.0"
   
   # 推送标签到GitHub
   git push origin v1.0.0
   ```

3. **自动构建**
   - 推送标签后，GitHub Actions会自动触发构建
   - 访问仓库的 Actions 标签页查看构建进度
   - 构建完成后会自动创建Release并上传APK

### 标签命名规范

- 格式：`v主版本.次版本.修订号`
- 示例：`v1.0.0`, `v1.2.3`, `v2.0.0-beta`
- 必须以 `v` 开头

## 方式二：手动触发发布

### 步骤

1. **访问GitHub仓库的Actions页面**
   - 进入你的仓库
   - 点击顶部的 "Actions" 标签

2. **选择Manual Release Build工作流**
   - 在左侧列表中找到 "Manual Release Build"
   - 点击 "Run workflow" 按钮

3. **填写发布信息**
   - Release tag: 输入版本标签（如 `v1.0.0`）
   - Release notes: 输入发布说明（可选）

4. **运行工作流**
   - 点击绿色的 "Run workflow" 按钮
   - 等待构建完成

## 方式三：通过GitHub网页直接创建Release

### 步骤

1. **先手动构建APK**
   ```bash
   cd Kototoro
   ./gradlew assembleRelease
   ```
   
   APK位置：`Kototoro/app/build/outputs/apk/release/app-release.apk`

2. **在GitHub上创建Release**
   - 访问仓库页面
   - 点击右侧的 "Releases"
   - 点击 "Draft a new release"
   - 填写以下信息：
     - Tag version: 输入版本号（如 `v1.0.0`）
     - Release title: 输入标题（如 `Kototoro v1.0.0`）
     - Description: 输入更新说明
   - 拖拽或选择APK文件上传
   - 点击 "Publish release"

## 配置签名密钥（可选但推荐）

为了使用正式的签名密钥，需要在GitHub仓库中配置Secrets：

### 1. 准备密钥文件

如果还没有密钥，创建一个：
```bash
keytool -genkey -v -keystore release.keystore -alias release \
  -keyalg RSA -keysize 2048 -validity 10000
```

### 2. 转换为Base64

```bash
base64 -i release.keystore -o keystore.base64
```

### 3. 在GitHub中添加Secrets

访问仓库的 Settings > Secrets and variables > Actions，添加以下secrets：

- `KEYSTORE_BASE64`: keystore.base64文件的内容
- `KEYSTORE_PASSWORD`: keystore密码
- `KEY_ALIAS`: 密钥别名（通常是 `release`）
- `KEY_PASSWORD`: 密钥密码
- `GITCODE_TOKEN`: GitCode 个人访问令牌，需要具备目标仓库的代码推送和 Release API 写入权限

正式版发布到 GitCode 仓库 `2401_87187946/Kototoro`，Nightly 预发布版本发布到
`2401_87187946/Kototoro-Nightly`。两个工作流使用同一个 Secret，同步 `devel` 分支和发布标签，
然后创建或更新同名 Release，并上传所有 ABI APK。不要把令牌直接写入 workflow、仓库 URL 或文档。

### 4. 删除本地Base64文件

```bash
rm keystore.base64
```

## 版本号管理

在发布前，记得更新 `Kototoro/app/build.gradle` 中的版本信息：

```gradle
defaultConfig {
    versionCode = 1034  // 每次发布递增
    versionName = '0.0.3'  // 语义化版本号
}
```

## 发布检查清单

- [ ] 更新版本号（versionCode 和 versionName）
- [ ] 测试所有功能正常工作
- [ ] 提交所有代码更改
- [ ] 编写更新日志
- [ ] 创建并推送Git标签
- [ ] 等待GitHub Actions构建完成
- [ ] 验证Release页面的APK可以正常下载和安装

## 常见问题

### Q: 构建失败怎么办？
A: 访问Actions页面查看详细日志，通常是依赖问题或签名配置问题。

### Q: 如何删除错误的Release？
A: 在Releases页面找到对应的Release，点击Delete删除。同时删除对应的Git标签：
```bash
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0
```

### Q: 如何创建预发布版本？
A: 在标签名中添加后缀，如 `v1.0.0-beta` 或 `v1.0.0-rc1`

### Q: 构建时间太长？
A: GitHub Actions的免费额度有限制，可以考虑：
- 使用Gradle缓存（已配置）
- 减少不必要的依赖
- 使用本地构建后手动上传

## 自动化流程说明

当你推送标签时，GitHub Actions会：

1. 检出代码
2. 设置Java环境
3. 配置签名密钥
4. 构建Release APK
5. 提取版本信息
6. 重命名APK文件
7. 创建 GitHub Release 并上传 APK
8. 同步发布提交和标签到 GitCode
9. 创建或更新 GitCode Release 并上传 APK

Nightly 工作流每天使用相同流程发布到独立的 GitHub 和 GitCode Nightly 仓库，并将 Release 标记为预发布版本。

整个过程大约需要5-10分钟。
