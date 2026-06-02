package pl.dubba.share.ui

import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Native Holo widgets, embedded via Compose's AndroidView interop.
 *
 * The Android framework still ships every Holo-era widget drawable + style
 * resource under values-v14/. Our Activity uses @android:style/Theme.Holo.NoActionBar,
 * so any platform View created with a child context picks up the genuine
 * Holo chrome (the 2011 metallic-edged buttons, rectangular Switch thumb,
 * the glow on focus). We wrap the context in ContextThemeWrapper as a belt-
 * and-suspenders guarantee in case Compose hands us a non-themed context.
 *
 * Trade-off vs hand-drawn Canvas widgets: less flexible (native Button only
 * accepts a string label, no composable content) but visually authentic.
 */

private fun holoContext(base: android.content.Context): android.content.Context =
    @Suppress("DEPRECATION") // Holo is the point - see file header.
    ContextThemeWrapper(base, android.R.style.Theme_Holo)

@Composable
fun HoloButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.Button(holoContext(ctx)).apply {
                this.text = text
                isEnabled = enabled
                setOnClickListener { onClick() }
            }
        },
        update = { view ->
            view.text = text
            view.isEnabled = enabled
            view.setOnClickListener { onClick() }
        },
    )
}

@Composable
fun HoloSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            @Suppress("DEPRECATION") // platform Holo Switch; the modern replacement loses the look
            android.widget.Switch(holoContext(ctx)).apply {
                this.isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, value -> onCheckedChange(value) }
            }
        },
        update = { view ->
            // Avoid recompose-loop: detach listener before programmatic state change.
            view.setOnCheckedChangeListener(null)
            view.isChecked = checked
            view.isEnabled = enabled
            view.setOnCheckedChangeListener { _, value -> onCheckedChange(value) }
        },
    )
}
