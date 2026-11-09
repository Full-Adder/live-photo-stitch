# 抠图+放大功能 - 实现文档

## 功能概述

在拼接3张照片时，可以选择其中一张照片，对其应用"抠图+放大"效果：
- **背景保持不变**（不超出原图边界）
- **人物放大并超出边界**（覆盖到相邻照片上）
- **保持实况照片效果**（抠图+放大同步到视频的每一帧）

## 使用流程

```
1. 选择3张照片 → 选择模板 → 进入编辑页
2. 点击中间的照片（选中）
3. 点击底部"抠图"工具
4. 启用抠图效果开关
5. 调整放大倍数（100%-300%）
6. 微调人物位置（水平/垂直）
7. 可选：调整背景虚化程度
8. 点击"预览" → "保存"
```

## 技术实现

### 1. 数据模型

```kotlin
// 单张照片的效果配置
data class PhotoEffect(
    val cutoutEnabled: Boolean = false,    // 是否启用抠图
    val zoomLevel: Float = 1.0f,           // 放大倍数
    val personCenterX: Float = 0.5f,       // 人物水平位置
    val personCenterY: Float = 0.5f,       // 人物垂直位置
    val backgroundBlur: Float = 0f         // 背景虚化
)
```

### 2. 图层结构

```
┌─────────────────────────────────────────┐
│           最终拼接结果                    │
│  ┌───────────────────────────────────┐  │
│  │        前景层（人物）               │  │
│  │    ┌───────────────────────┐      │  │
│  │    │  放大后的人物           │      │  │
│  │    │  （可超出原图边界）     │      │  │
│  │    └───────────────────────┘      │  │
│  └───────────────────────────────────┘  │
│  ┌───────────────────────────────────┐  │
│  │        背景层（3张照片）            │  │
│  │  ┌─────┐ ┌─────┐ ┌─────┐         │  │
│  │  │照片1│ │照片2│ │照片3│         │  │
│  │  └─────┘ └─────┘ └─────┘         │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

### 3. 核心算法

#### 3.1 静态图片处理

```kotlin
// 1. 绘制背景层（所有照片按模板拼接，不超出边界）
val backgroundLayer = composeBackground(bitmaps, template, outputWidth, outputHeight)

// 2. 处理前景层（对选中的照片抠图+放大）
val foregroundLayer = processForeground(
    bitmaps = bitmaps,
    template = template,
    effects = effects,           // 每张照片的效果配置
    cutoutProcessor = processor  // ML Kit 或小米AI
)

// 3. 合并图层
val stitchedBitmap = mergeLayers(backgroundLayer, foregroundLayer)
```

#### 3.2 人物放大算法

```kotlin
fun zoomPerson(
    personBitmap: Bitmap,   // 抠出的人物
    maskBitmap: Bitmap,     // 人物mask
    zoomLevel: Float,       // 放大倍数
    centerX: Float,         // 水平中心
    centerY: Float          // 垂直中心
): Bitmap {
    // 1. 放大人物和mask
    val scaledPerson = Bitmap.createScaledBitmap(personBitmap, 
        (width * zoomLevel).toInt(), (height * zoomLevel).toInt(), true)
    val scaledMask = Bitmap.createScaledBitmap(maskBitmap, 
        (width * zoomLevel).toInt(), (height * zoomLevel).toInt(), true)
    
    // 2. 计算偏移，使放大后的人物居中
    val offsetX = (width - scaledPerson.width) * centerX
    val offsetY = (height - scaledPerson.height) * centerY
    
    // 3. 绘制放大后的人物
    canvas.drawBitmap(scaledPerson, offsetX, offsetY, paint)
    
    // 4. 用mask裁剪（DST_IN）
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    canvas.drawBitmap(scaledMask, offsetX, offsetY, paint)
}
```

#### 3.3 视频帧处理（保持实况照片效果）

```kotlin
// 提取视频的所有帧
val frames = mutableListOf<Bitmap>()
var timeUs = 0L
while (timeUs < durationMs * 1000) {
    val frame = retriever.getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)
    if (frame != null) frames.add(frame)
    timeUs += 33_000  // 约30fps
}

// 对每一帧应用同样的效果
val processedFrames = frames.map { frame ->
    applyEffectToFrame(frame, effect, template, photoIndex, cutoutProcessor)
}

// 编码为新视频
encodeFramesToVideo(processedFrames, outputFile)
```

### 4. 小米手机优化

```kotlin
// 在小米手机上使用小米相机AI引擎
if (XiaomiIntegration.isXiaomiDevice()) {
    // 调用小米相机抠图API
    XiaomiIntegration.callXiaomiPortraitCutout(context, imageUri) { result ->
        // 使用小米AI抠图结果
    }
} else {
    // 使用ML Kit
    MlKitPortraitCutoutProcessor(context)
}
```

## 关键代码文件

| 文件 | 说明 |
|------|------|
| `LivePhotoStitchEngine.kt` | 拼接引擎，支持图层合并 |
| `PortraitCutoutProcessor.kt` | 抠图处理器接口和ML Kit实现 |
| `EditScreen.kt` | 编辑页，支持选择照片和配置效果 |
| `PreviewScreen.kt` | 预览页，执行拼接处理 |
| `StitchProjectState.kt` | 全局状态，传递效果配置 |

## 效果示例

### 原始拼接
```
┌─────┬─────┬─────┐
│照片1│照片2│照片3│
│     │ 👤  │     │
└─────┴─────┴─────┘
```

### 抠图+放大后
```
┌─────┬─────┬─────┐
│照片1│照片2│照片3│
│     │  👤 │     │  ← 人物放大，超出边界
│     │ /│\ │     │
└─────┴─────┴─────┘
```

## 性能优化

1. **异步处理** - 所有耗时操作在 `Dispatchers.IO` 中执行
2. **帧率控制** - 视频处理约30fps，平衡质量和性能
3. **内存管理** - 及时回收中间Bitmap，避免OOM
4. **GPU加速** - 小米手机启用GPU Turbo

## 注意事项

1. **视频处理时间** - 逐帧处理视频可能较慢，建议显示进度
2. **内存占用** - 高分辨率照片处理时注意内存管理
3. **兼容性** - ML Kit需要Google Play服务，小米手机有专属优化
4. **输出质量** - JPEG质量95%，平衡文件大小和画质

## 未来优化方向

1. **更精细的mask** - 使用更高级的分割模型
2. **背景智能填充** - 人物移走后自动填充背景
3. **实时预览** - 编辑时实时显示效果
4. **更多效果** - 支持阴影、发光等特效
