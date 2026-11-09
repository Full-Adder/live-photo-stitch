# 实况照片拼接 APP - 项目规划文档

## 项目概述

一款专为安卓设计、特别优化小米手机的实况照片拼接应用，支持智能抠图和背景处理功能。

### 目标用户
- 小米手机用户
- 喜欢分享实况照片的社交媒体用户
- 需要创意照片编辑功能的用户

## 核心功能

### 1. 实况照片拼接
- **输入**: 3张实况照片（Live Photos）
- **输出**: 拼接后的实况照片
- **模板系统**: 提供多种拼接模板
- **格式支持**: 支持 JPEG/HEIC + 视频片段的实况照片格式

### 2. 智能抠图与背景处理
- **人物识别**: 自动识别照片中的人物
- **小米相机集成**: 调用小米手机自带的人物抠图功能
- **背景放大**: 人物放大，背景保持不变
- **图片放大**: 整体放大并智能裁剪

## 技术架构

### 开发环境
```
- 开发语言: Kotlin
- 最低SDK: Android 8.0 (API 26)
- 目标SDK: Android 14 (API 34)
- 开发工具: Android Studio Hedgehog
- 架构模式: MVVM + Clean Architecture
```

### 技术栈
```
- UI框架: Jetpack Compose
- 图片处理: OpenCV + GPUImage
- 视频处理: MediaCodec + ExoPlayer
- 依赖注入: Hilt
- 异步处理: Coroutines + Flow
- 本地存储: Room + DataStore
- 相机集成: CameraX
```

## 功能模块设计

### 模块1: 照片选择与预览
**功能点:**
- 支持从相册选择3张实况照片
- 实时预览实况照片效果
- 支持照片顺序调整
- 支持照片删除和替换

**技术实现:**
```kotlin
// 使用Photo Picker API
val pickMedia = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(3)
) { uris ->
    // 处理选中的照片
}
```

### 模块2: 模板系统
**功能点:**
- 提供至少6种拼接模板:
  1. 横向三格
  2. 纵向三格
  3. 中间大两边小
  4. 左大右小
  5. 右大左小
  6. 自由布局

**技术实现:**
```kotlin
data class StitchTemplate(
    val id: String,
    val name: String,
    val layout: List<RectF>, // 每个照片的位置和大小
    val aspectRatio: Float
)
```

### 模块3: 实况照片拼接引擎
**功能点:**
- 提取静态图片和视频片段
- 按照模板合成静态图片
- 同步视频时间轴
- 生成新的实况照片

**技术实现:**
```kotlin
class LivePhotoStitchEngine {
    fun stitch(
        photos: List<LivePhoto>,
        template: StitchTemplate
    ): LivePhoto {
        // 1. 提取静态图片
        val bitmaps = photos.map { it.getStaticBitmap() }
        
        // 2. 按照模板合成
        val stitchedBitmap = composeWithTemplate(bitmaps, template)
        
        // 3. 处理视频片段
        val stitchedVideo = stitchVideos(photos, template)
        
        // 4. 生成新的实况照片
        return LivePhoto(stitchedBitmap, stitchedVideo)
    }
}
```

### 模块4: 智能抠图
**功能点:**
- 自动识别人物
- 调用小米相机抠图API
- 支持手动调整选区

**小米相机API集成:**
```kotlin
class XiaomiPortraitCutoutManager {
    fun cutout(
        context: Context,
        imageUri: Uri,
        callback: (Result<Bitmap>) -> Unit
    ) {
        // 检查是否为小米手机
        if (!isXiaomiDevice()) {
            callback(Result.failure(UnsupportedDeviceException()))
            return
        }
        
        // 调用小米相机抠图功能
        val intent = Intent("com.miui.camera.action.PORTRAIT_CUTOUT")
        intent.setData(imageUri)
        // ... 启动并处理结果
    }
}
```

### 模块5: 背景处理
**功能点:**
- 人物放大，背景保持
- 整体放大并智能裁剪
- 背景虚化效果

**技术实现:**
```kotlin
class BackgroundProcessor {
    fun zoomPerson(
        bitmap: Bitmap,
        mask: Bitmap,
        zoomLevel: Float
    ): Bitmap {
        // 1. 分离人物和背景
        val person = applyMask(bitmap, mask)
        val background = removeMask(bitmap, mask)
        
        // 2. 放大人物
        val zoomedPerson = zoomBitmap(person, zoomLevel)
        
        // 3. 合成
        return mergeBitmaps(zoomedPerson, background)
    }
    
    fun zoomWithCrop(
        bitmap: Bitmap,
        zoomLevel: Float,
        cropArea: RectF
    ): Bitmap {
        // 智能裁剪并放大
        val cropped = cropBitmap(bitmap, cropArea)
        return zoomBitmap(cropped, zoomLevel)
    }
}
```

## UI/UX 设计

### 主要界面流程
```
1. 启动页 → 2. 照片选择页 → 3. 模板选择页 → 4. 编辑页 → 5. 预览页 → 6. 保存/分享
```

### 界面设计要点
- **照片选择页**: 
  - 3个照片槽位
  - 点击添加照片
  - 长按拖动调整顺序
  - 实时预览实况效果

- **模板选择页**:
  - 网格展示模板
  - 实时预览效果
  - 支持自定义比例

- **编辑页**:
  - 主编辑区域
  - 底部工具栏（模板、抠图、缩放、滤镜）
  - 顶部操作栏（撤销、重做、保存）

## 小米手机特别优化

### 1. 系统能力调用
```kotlin
// 检查小米系统特性
fun isXiaomiDevice(): Boolean {
    return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
           Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
           Build.BRAND.equals("Redmi", ignoreCase = true)
}

// 调用小米相机抠图
fun callXiaomiPortraitCutout(context: Context, imageUri: Uri) {
    try {
        val intent = Intent().apply {
            action = "com.miui.camera.action.PORTRAIT_CUTOUT"
            setData(imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivityForResult(intent, REQUEST_CODE)
    } catch (e: Exception) {
        // 降级到应用内抠图
        fallbackToAppCutout(context, imageUri)
    }
}
```

### 2. 性能优化
- 利用小米手机的GPU Turbo技术
- 适配高刷新率屏幕
- 优化内存使用

### 3. 系统级集成
- 支持小米相册分享
- 适配小米主题
- 支持小米云同步

## 数据结构设计

### 实况照片数据模型
```kotlin
data class LivePhoto(
    val id: String = UUID.randomUUID().toString(),
    val staticImage: Bitmap,
    val videoPath: String?,
    val videoDuration: Long,
    val creationDate: Long,
    val metadata: LivePhotoMetadata
)

data class LivePhotoMetadata(
    val width: Int,
    val height: Int,
    val orientation: Int,
    val location: Location?,
    val deviceInfo: DeviceInfo
)
```

### 项目数据模型
```kotlin
data class StitchProject(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<LivePhoto>,
    val template: StitchTemplate,
    val createdAt: Long,
    val updatedAt: Long
)
```

## 测试计划

### 功能测试
- [ ] 照片选择功能测试
- [ ] 模板拼接功能测试
- [ ] 实况照片生成测试
- [ ] 抠图功能测试
- [ ] 背景处理功能测试
- [ ] 保存和分享功能测试

### 兼容性测试
- [ ] 小米手机各型号测试
- [ ] 不同Android版本测试
- [ ] 不同照片格式测试
- [ ] 性能测试

### 用户体验测试
- [ ] 操作流程测试
- [ ] 界面响应测试
- [ ] 错误处理测试

## 项目里程碑

### 第一阶段 (2周)
- ✅ 项目架构搭建
- ✅ 基础UI实现
- ✅ 照片选择功能

### 第二阶段 (3周)
- ✅ 模板系统实现
- ✅ 基础拼接功能
- ✅ 静态图片拼接

### 第三阶段 (3周)
- ✅ 视频处理功能
- ✅ 实况照片生成
- ✅ 性能优化

### 第四阶段 (2周)
- ✅ 小米相机集成
- ✅ 智能抠图功能
- ✅ 背景处理功能

### 第五阶段 (2周)
- ✅ 测试和优化
- ✅ 发布准备

## 技术难点与解决方案

### 难点1: 实况照片格式处理
**问题**: 实况照片包含静态图片和视频，需要保持同步
**解决方案**: 
- 使用MediaMetadataRetriever提取视频信息
- 使用MediaCodec处理视频帧
- 保持时间轴同步

### 难点2: 小米相机API调用
**问题**: 小米相机API可能不公开
**解决方案**:
- 通过Intent调用系统相机功能
- 准备降级方案（应用内抠图）
- 使用开源抠图库（如MediaPipe）

### 难点3: 性能优化
**问题**: 图片和视频处理耗时长
**解决方案**:
- 使用GPU加速
- 后台线程处理
- 进度提示和取消机制

## 发布计划

### 应用商店
- 小米应用商店（主要）
- Google Play
- 华为应用市场
- 应用宝

### 推广策略
- 小米社区推广
- 社交媒体分享功能
- 用户推荐奖励

## 附录

### 参考资源
- Android Developer Documentation
- OpenCV Android SDK
- MediaPipe Solutions
- 小米开放平台

### 开源库
- Glide: 图片加载
- ExoPlayer: 视频播放
- GPUImage: GPU图片处理
- OpenCV: 计算机视觉
- MediaPipe: 机器学习解决方案
