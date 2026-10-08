package com.fastvpnn.app.util

import android.content.Context
import android.content.res.Configuration
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatDelegate
import com.fastvpnn.app.R
import com.fastvpnn.app.data.AppSettings

/** Light/dark switch used by the sun/moon button in each screen's top-right corner. */
object ThemeUtil {
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Shows a sun while dark (tap -> light) and a moon while light (tap -> dark). */
    fun bind(button: ImageButton) {
        val ctx = button.context
        val night = isNight(ctx)
        button.setImageResource(if (night) R.drawable.ic_theme_light else R.drawable.ic_theme_dark)
        button.contentDescription = if (night) "Switch to light mode" else "Switch to dark mode"
        button.setOnClickListener {
            val toDark = !isNight(ctx)
            AppSettings(ctx).themeMode = if (toDark) "dark" else "light"
            // AppCompat recreates the visible activities with the new colors.
            AppCompatDelegate.setDefaultNightMode(
                if (toDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}
