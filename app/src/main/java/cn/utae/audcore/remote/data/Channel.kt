package cn.utae.audcore.remote.data

import com.google.gson.JsonElement

/**
 * 用户通道（GET /channels 元素）。
 *
 * index 是内部标识，不是视觉位置；识别通道一律用稳定字符串 id。
 * visual_index 表示显示位置，缺失时回退 index。
 * inputs / sends 字段形状保留为原始 JSON，避免丢字段。
 */
data class Channel(
    val id: String,
    val index: Int,
    val name: String? = null,
    val gain_db: Float? = null,
    val mute: Boolean? = null,
    val color: String? = null,
    val visual_index: Int? = null,
    val align_right: Boolean? = null,
    val inputs: JsonElement? = null,
    val sends: JsonElement? = null,
) {
    /** 显示顺序：普通通道在前、右侧常驻通道在后，由服务端 visual_order 决定。 */
    val displayOrder: Int
        get() = visual_index ?: index
}
