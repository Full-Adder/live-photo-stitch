# 实况照片拼接 APP

一款专为安卓设计、特别优化小米手机的实况照片拼接应用。

## ✨ 主要功能

### 📸 实况照片拼接
- 支持3张照片拼接
- 提供6种以上精美模板
- 保持实况照片效果
- 高清输出质量

### 🤖 智能抠图
- AI自动识别人物
- 小米手机专属AI引擎
- 精准边缘处理
- 支持手动调整

### 🎨 背景处理
- 人物放大，背景保持
- 整体放大智能裁剪
- 背景虚化效果
- 多种滤镜选择

### 🌟 小米手机专属优化
- GPU Turbo加速
- 小米AI引擎调用
- 系统级性能优化
- 专属UI提示

## 🚀 快速开始

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 8 或更高版本
- Android SDK 26+
- Gradle 8.0+

### 安装步骤

1. **克隆项目**
```bash
git clone <repository-url>
cd 实况照片拼接app
```

2. **打开项目**
```bash
# 使用Android Studio打开项目
# 或者使用命令行
./gradlew build
```

3. **运行应用**
```bash
# 连接Android设备或启动模拟器
./gradlew installDebug
```

### 权限配置
应用需要以下权限：
- 📷 相机权限（用于拍照）
- 🖼️ 存储权限（用于读取照片）
- 🎥 视频权限（用于处理实况照片）

## 📱 使用说明

### 1. 选择照片
- 点击"开始创作"进入首页
- 点击照片槽位选择3张照片
- 支持实况照片和普通照片

### 2. 选择模板
- 浏览6种拼接模板
- 点击模板预览效果
- 选择喜欢的模板

### 3. 编辑照片
- **缩放**: 调整照片大小
- **抠图**: 自动识别人物
- **滤镜**: 添加特效
- **旋转**: 调整角度

### 4. 保存分享
- 预览最终效果
- 选择保存格式
- 分享到社交平台

## 🛠️ 技术架构

### 技术栈
```
- Kotlin
- Jetpack Compose
- MVVM架构
- OpenCV
- ML Kit
- MediaCodec
- Hilt DI
- Coroutines
```

### 项目结构
```
app/
├── data/           # 数据层
├── engine/         # 核心引擎
├── ui/             # UI层
├── utils/          # 工具类
└── MainActivity.kt # 主活动
```

## 📋 开发指南

### 添加新模板
在 `TemplateSelectionScreen.kt` 中添加新模板：
```kotlin
StitchTemplate(
    id = "new_template",
    name = "新模板",
    layout = listOf(
        TemplateItem(0.05f, 0.25f, 0.28f, 0.5f),
        // ... 其他位置
    ),
    aspectRatio = 16f / 9f,
    thumbnailResId = 0
)
```

### 添加新滤镜
在 `BackgroundProcessor.kt` 中实现：
```kotlin
fun applyNewFilter(bitmap: Bitmap): Bitmap {
    // 实现滤镜逻辑
    return processedBitmap
}
```

### 集成小米功能
在 `XiaomiIntegration.kt` 中调用：
```kotlin
XiaomiIntegration.callXiaomiPortraitCutout(context, imageUri) { result ->
    // 处理结果
}
```

## 🧪 测试

### 运行测试
```bash
# 单元测试
./gradlew test

# UI测试
./gradlew connectedAndroidTest

# 代码覆盖率
./gradlew jacocoTestReport
```

### 测试设备
- 小米13 Pro
- 小米12
- Redmi K60
- 其他Android 8.0+设备

## 📦 发布

### 构建APK
```bash
# 调试版本
./gradlew assembleDebug

# 发布版本
./gradlew assembleRelease
```

### 签名配置
在 `app/build.gradle.kts` 中配置签名：
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("keystore.jks")
        storePassword = "your_password"
        keyAlias = "your_alias"
        keyPassword = "your_password"
    }
}
```

## 🤝 贡献

欢迎贡献代码！请遵循以下步骤：

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 📞 联系方式

- 项目维护者: [Your Name]
- 邮箱: [your.email@example.com]
- 项目地址: [https://github.com/yourusername/live-photo-stitch](https://github.com/yourusername/live-photo-stitch)

## 🙏 致谢

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [OpenCV](https://opencv.org/)
- [ML Kit](https://developers.google.com/ml-kit)
- [小米开放平台](https://dev.mi.com/)

## 📊 项目状态

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Version](https://img.shields.io/badge/version-1.0.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)

---

**注意**: 本项目仍在积极开发中，欢迎反馈和建议！
