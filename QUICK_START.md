# 实况照片拼接 APP - 快速入门指南

## 🎯 项目目标

创建一个安卓应用，特别优化小米手机，实现：
1. 3张实况照片拼接
2. 智能人物抠图
3. 背景处理功能

## 📁 项目文件说明

```
实况照片拼接app/
├── README.md                    # 项目说明文档
├── PROJECT_PLAN.md              # 详细项目规划
├── PROJECT_SUMMARY.md           # 项目总结
├── TEST_PLAN.md                 # 测试计划
├── QUICK_START.md               # 本文件
└── app/                         # 应用源代码
    ├── build.gradle.kts         # 应用构建配置
    └── src/main/
        ├── java/com/example/livephotostitch/
        │   ├── MainActivity.kt              # 主活动
        │   ├── data/model/                  # 数据模型
        │   │   └── LivePhoto.kt            # 核心数据类
        │   ├── engine/                      # 核心引擎
        │   │   ├── LivePhotoStitchEngine.kt # 拼接引擎
        │   │   └── BackgroundProcessor.kt   # 背景处理
        │   ├── ui/screens/                  # 页面
        │   │   ├── HomeScreen.kt            # 首页
        │   │   ├── PhotoSelectionScreen.kt  # 照片选择
        │   │   ├── TemplateSelectionScreen.kt # 模板选择
        │   │   ├── EditScreen.kt            # 编辑页
        │   │   └── PreviewScreen.kt         # 预览页
        │   ├── ui/theme/                    # 主题
        │   └── utils/                       # 工具类
        │       ├── LivePhotoUtils.kt        # 实况照片工具
        │       └── XiaomiIntegration.kt     # 小米集成
        └── res/                             # 资源文件
```

## 🚀 5分钟快速开始

### 1. 环境检查
```bash
# 检查Java版本
java -version  # 需要 JDK 8+

# 检查Android SDK
echo $ANDROID_HOME

# 检查Gradle
./gradlew --version
```

### 2. 导入项目
1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择项目根目录
4. 等待 Gradle 同步完成

### 3. 运行应用
1. 连接Android设备或启动模拟器
2. 点击 Run 按钮 (▶️)
3. 选择目标设备
4. 等待应用安装完成

## 📱 功能演示流程

### 步骤1: 选择照片
```
首页 → 点击"开始创作" → 照片选择页
→ 点击3个槽位选择照片
→ 点击"下一步"
```

### 步骤2: 选择模板
```
模板选择页 → 浏览6种模板
→ 点击喜欢的模板
→ 点击"下一步"
```

### 步骤3: 编辑照片
```
编辑页 → 使用底部工具
→ 缩放: 调整照片大小
→ 抠图: 自动识别人物
→ 滤镜: 添加特效
→ 旋转: 调整角度
→ 点击"预览"
```

### 步骤4: 保存分享
```
预览页 → 查看最终效果
→ 点击"保存"
→ 选择保存格式
→ 完成保存
```

## 🔧 核心代码位置

### 照片拼接
**文件**: `app/src/main/java/com/example/livephotostitch/engine/LivePhotoStitchEngine.kt`

```kotlin
class LivePhotoStitchEngine(private val context: Context) {
    suspend fun stitchLivePhotos(
        photos: List<LivePhoto>,
        template: StitchTemplate,
        outputDir: File
    ): Result<LivePhoto> {
        // 实现拼接逻辑
    }
}
```

### 智能抠图
**文件**: `app/src/main/java/com/example/livephotostitch/engine/BackgroundProcessor.kt`

```kotlin
class BackgroundProcessor(private val context: Context) {
    suspend fun cutoutPerson(bitmap: Bitmap): Result<CutoutResult> {
        // 使用ML Kit进行抠图
    }
}
```

### 小米集成
**文件**: `app/src/main/java/com/example/livephotostitch/utils/XiaomiIntegration.kt`

```kotlin
object XiaomiIntegration {
    fun isXiaomiDevice(): Boolean {
        // 检查是否为小米手机
    }
    
    fun callXiaomiPortraitCutout(
        context: Context,
        imageUri: Uri,
        callback: (Result<Bitmap>) -> Unit
    ) {
        // 调用小米相机抠图
    }
}
```

## 🎨 自定义开发

### 添加新模板
在 `TemplateSelectionScreen.kt` 的 `TemplateData` 对象中添加：

```kotlin
val templates = listOf(
    // 现有模板...
    StitchTemplate(
        id = "my_template",
        name = "我的模板",
        layout = listOf(
            TemplateItem(0.0f, 0.0f, 0.5f, 0.5f),   // 左上
            TemplateItem(0.5f, 0.0f, 0.5f, 0.5f),   // 右上
            TemplateItem(0.0f, 0.5f, 1.0f, 0.5f)    // 底部
        ),
        aspectRatio = 1f,
        thumbnailResId = 0
    )
)
```

### 添加新滤镜
在 `BackgroundProcessor.kt` 中添加：

```kotlin
fun applyVintageFilter(bitmap: Bitmap): Bitmap {
    val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(outputBitmap)
    val paint = Paint()
    
    // 应用复古效果
    val colorMatrix = ColorMatrix().apply {
        setSaturation(0.5f)
    }
    paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    
    return outputBitmap
}
```

## 🐛 常见问题

### 1. Gradle同步失败
**解决方案**:
```bash
# 清理项目
./gradlew clean

# 重新同步
./gradlew build
```

### 2. 权限被拒绝
**解决方案**:
- 在 `AndroidManifest.xml` 中添加权限
- 在运行时请求权限
- 检查设备设置中的应用权限

### 3. 小米功能无法使用
**解决方案**:
- 确认设备是小米手机
- 检查小米相机是否安装
- 查看日志中的错误信息

### 4. 照片处理失败
**解决方案**:
- 检查照片格式是否支持
- 确认存储空间充足
- 查看错误日志

## 📚 学习资源

### Android开发
- [Android官方文档](https://developer.android.com/docs)
- [Jetpack Compose教程](https://developer.android.com/jetpack/compose/tutorial)
- [Kotlin协程指南](https://kotlinlang.org/docs/coroutines-guide.html)

### 图片处理
- [OpenCV文档](https://docs.opencv.org/)
- [Android图形处理](https://developer.android.com/training/graphics)
- [GPUImage文档](https://github.com/cats-oss/android-gpuimage)

### 小米开发
- [小米开放平台](https://dev.mi.com/)
- [MIUI开发者文档](https://dev.mi.com/docs/)
- [小米推送服务](https://dev.mi.com/push/)

## 🔍 调试技巧

### 1. 查看日志
```bash
# 过滤应用日志
adb logcat | grep "LivePhotoStitch"

# 查看错误日志
adb logcat *:E
```

### 2. 性能分析
```bash
# 启动性能分析
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 使用Android Profiler
# Android Studio → View → Tool Windows → Profiler
```

### 3. 布局检查
```bash
# 使用Layout Inspector
# Android Studio → Tools → Layout Inspector
```

## 📞 获取帮助

### 遇到问题？
1. 查看 [PROJECT_PLAN.md](PROJECT_PLAN.md) 了解详细设计
2. 查看 [TEST_PLAN.md](TEST_PLAN.md) 了解测试方法
3. 查看代码注释和文档
4. 提交 Issue

### 贡献代码
1. Fork 项目
2. 创建分支
3. 提交更改
4. 发起 Pull Request

## ✅ 检查清单

开发前检查：
- [ ] 环境配置完成
- [ ] 项目成功同步
- [ ] 设备连接正常
- [ ] 权限配置正确

开发中检查：
- [ ] 代码风格一致
- [ ] 添加必要注释
- [ ] 进行单元测试
- [ ] 检查内存使用

发布前检查：
- [ ] 所有功能测试通过
- [ ] 性能优化完成
- [ ] 兼容性测试完成
- [ ] 文档更新完整

---

**祝你开发顺利！** 🎉

如有问题，请参考详细文档或联系开发团队。
