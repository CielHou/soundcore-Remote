package cn.utae.audcore.remote.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Android 15（targetSdk 35）起强制 edge-to-edge，内容会绘制到状态栏和导航栏下面。
 * 这里不再给根布局一次性加 padding（会把抽屉滑出的定位也一起挤偏），
 * 而是按区域分别处理：
 * - 顶部栏：状态栏 + 刘海高度
 * - 内容区：导航栏高度（底部手势条）
 * - 抽屉面板：上下都让开
 */
object Insets {

    /** 顶部工具栏：让开状态栏/刘海。 */
    fun applyAppBar(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 主内容区：让开底部导航栏/手势条。 */
    fun applyContentInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** 抽屉面板：让开状态栏与导航栏。 */
    fun applyDrawerInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
