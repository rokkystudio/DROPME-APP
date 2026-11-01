package com.rokkystudio.dropme

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Размещает корневой экран внутри безопасной области системных панелей, вырезов
 * дисплея и экранной клавиатуры. Сохраняет исходные отступы представления.
 */
internal fun AppCompatActivity.applySystemBarInsets(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safeArea = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        view.setPadding(
            left + safeArea.left,
            top + safeArea.top,
            right + safeArea.right,
            bottom + safeArea.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
