package com.fastvpnn.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads [root] by the system bars' insets, ADDED ON TOP of whatever padding it
 * already has -- captured once, before any insets are applied -- so a layout
 * that sets its own android:padding for visual spacing (e.g. a form with
 * 32dp all around) keeps that spacing instead of having it overwritten down
 * to just the inset amount.
 *
 * Android 15 (API 35) started forcing edge-to-edge for apps targeting it, but
 * still allowed opting back out via R.attr#windowOptOutEdgeToEdgeEnforcement.
 * Android 16 (API 36) removes that opt-out entirely -- every app targeting 36
 * draws edge-to-edge with no way back, so every screen needs to consume the
 * system bar insets itself or its content can end up hidden behind them.
 */
fun applyEdgeToEdgeInsets(root: View) {
    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom
        )
        insets
    }
}
