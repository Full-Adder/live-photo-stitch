package com.example.livephotostitch.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build

/**
 * 小米手机集成工具
 */
object XiaomiIntegration {
    
    private const val XIAOMI_MANUFACTURER = "Xiaomi"
    private const val XIAOMI_BRAND = "Xiaomi"
    private const val REDMI_BRAND = "Redmi"
    private const val POCO_BRAND = "POCO"
    
    /**
     * 检查是否为小米手机
     */
    fun isXiaomiDevice(): Boolean {
        return Build.MANUFACTURER.equals(XIAOMI_MANUFACTURER, ignoreCase = true) ||
               Build.BRAND.equals(XIAOMI_BRAND, ignoreCase = true) ||
               Build.BRAND.equals(REDMI_BRAND, ignoreCase = true) ||
               Build.BRAND.equals(POCO_BRAND, ignoreCase = true)
    }
    
    /**
     * 检查小米相机是否可用
     */
    fun isXiaomiCameraAvailable(context: Context): Boolean {
        return try {
            val intent = Intent("com.miui.camera.action.PORTRAIT_CUTOUT")
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 调用小米相机抠图功能
     */
    fun callXiaomiPortraitCutout(
        context: Context,
        imageUri: Uri,
        callback: (Result<Bitmap>) -> Unit
    ) {
        if (!isXiaomiDevice() || !isXiaomiCameraAvailable(context)) {
            callback(Result.failure(UnsupportedOperationException("Xiaomi camera not available")))
            return
        }
        
        try {
            val intent = Intent("com.miui.camera.action.PORTRAIT_CUTOUT").apply {
                setData(imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // 启动小米相机抠图
            // 注意：这里需要Activity来启动，实际使用时需要在Activity中调用
            callback(Result.failure(UnsupportedOperationException("Requires Activity context")))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    
    /**
     * 获取设备信息
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT
        )
    }
    
    /**
     * 优化设置（针对小米手机）
     */
    fun getOptimizationSettings(): OptimizationSettings {
        return if (isXiaomiDevice()) {
            OptimizationSettings(
                useHardwareAcceleration = true,
                enableGpuTurbo = true,
                highRefreshRate = true,
                memoryOptimization = true,
                backgroundProcessLimit = true
            )
        } else {
            OptimizationSettings(
                useHardwareAcceleration = true,
                enableGpuTurbo = false,
                highRefreshRate = false,
                memoryOptimization = true,
                backgroundProcessLimit = false
            )
        }
    }
}

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkVersion: Int
) {
    fun isXiaomi(): Boolean {
        return manufacturer.equals("Xiaomi", ignoreCase = true) ||
               brand.equals("Xiaomi", ignoreCase = true) ||
               brand.equals("Redmi", ignoreCase = true) ||
               brand.equals("POCO", ignoreCase = true)
    }
}

data class OptimizationSettings(
    val useHardwareAcceleration: Boolean,
    val enableGpuTurbo: Boolean,
    val highRefreshRate: Boolean,
    val memoryOptimization: Boolean,
    val backgroundProcessLimit: Boolean
)
