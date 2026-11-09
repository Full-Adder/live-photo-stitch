package com.example.livephotostitch.data

import android.net.Uri
import com.example.livephotostitch.data.model.LivePhoto
import com.example.livephotostitch.data.model.StitchTemplate
import com.example.livephotostitch.engine.PhotoEffect
import com.example.livephotostitch.engine.StitchResult

/**
 * 拼接项目的全局状态
 */
object StitchProjectState {
    /** 已选择的照片 */
    var photos: List<LivePhoto> = emptyList()

    /** 已选择的模板 */
    var template: StitchTemplate? = null

    /** 每张照片的效果配置 */
    var effects: List<PhotoEffect> = List(3) { PhotoEffect() }

    /** 拼接结果 */
    var result: StitchResult? = null

    fun reset() {
        photos = emptyList()
        template = null
        effects = List(3) { PhotoEffect() }
        result = null
    }

    fun updateEffect(index: Int, effect: PhotoEffect) {
        if (index in effects.indices) {
            effects = effects.toMutableList().apply { set(index, effect) }
        }
    }
}
