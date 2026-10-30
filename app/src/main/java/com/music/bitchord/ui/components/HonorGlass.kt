package com.music.bitchord.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hihonor.uikit.transparency.api.HnGlassEffect
import com.hihonor.uikit.transparency.api.HnMaterialType
import com.hihonor.uikit.transparency.api.HnSceneType
import com.hihonor.uikit.transparency.api.HnVisualEffectLayout
import com.hihonor.uikit.transparency.api.HnWidgetSize
import com.hihonor.uikit.transparency.api.IColorChangedReceiver

private const val TAG = "HonorGlass"

/**
 * Whether this device renders HONOR's native translucent widgets.
 *
 * `launcher_transparency_mode` is a framework resource only HONOR's system has;
 * 3 means the translucent material is available. Anywhere else the lookup finds
 * nothing and the app keeps its own liquid glass / Haze.
 */
fun isHonorGlassSupported(context: Context): Boolean = try {
    val id = context.resources.getIdentifier("launcher_transparency_mode", "integer", "androidhnext")
    id > 0 && context.resources.getInteger(id) == 3
} catch (e: Throwable) {
    false
}

/**
 * HONOR's liquid glass as a layer behind Compose content: size it with
 * `matchParentSize()` inside the surface and draw the content over it.
 *
 * The glass is a [HnVisualEffectLayout] View, so it cannot be a Modifier — it is
 * hosted in an [AndroidView]. [onDarkChanged] reports whether the system judged
 * the backdrop dark, so the caller can pick a legible icon and label colour.
 */
@Composable
fun HonorGlassLayer(
    modifier: Modifier = Modifier,
    /** Corner radius in px; [PILL] makes it half the measured height, so a bar's ends are exactly round. */
    radiusPx: Int = PILL,
    small: Boolean = true,
    onDarkChanged: (Boolean) -> Unit = {},
) {
    val onDark = rememberUpdatedState(onDarkChanged)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val radius = if (radiusPx == PILL) minOf(size.width, size.height) / 2 else radiusPx
    Box(modifier = modifier.onSizeChanged { size = it }) {
        // The radius is only known after the first measure. From then on the view is
        // built once and the radius is updated in place: a bar that folds changes size
        // every frame, and rebuilding the glass view each time is what made it judder.
        if (radius > 0) key(small) {
            val initialRadius = remember { radius }
            val effect = remember {
                HnGlassEffect().apply {
                    setMaterialType(HnMaterialType.LIQUID_GLASS)
                    setWidgetSize(if (small) HnWidgetSize.SMALL else HnWidgetSize.BIG)
                    setSceneType(HnSceneType.GENERAL)
                    setRadius(initialRadius)
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // The glass is only a backdrop: it must not take touches, or a
                    // tap on the bar never reaches the Compose clickable above it.
                    TouchPassthroughLayout(ctx).apply {
                        addView(
                            HnVisualEffectLayout(ctx).also { layout ->
                                try {
                                    layout.setRadius(initialRadius)
                                    effect.addColorChangedReceiver(
                                        IColorChangedReceiver { _, _, isDarkMode, _ ->
                                            layout.post { onDark.value(isDarkMode) }
                                        },
                                    )
                                    layout.setVisualEffect(effect)
                                } catch (e: Throwable) {
                                    Log.w(TAG, "HONOR glass failed to start", e)
                                }
                            },
                            android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
                update = { host ->
                    (host.getChildAt(0) as? HnVisualEffectLayout)?.let { layout ->
                        try {
                            layout.setRadius(radius)
                        } catch (e: Throwable) {
                            Log.w(TAG, "HONOR glass radius update failed", e)
                        }
                    }
                },
            )
        }
    }
}

/** [HonorGlassLayer]'s `radiusPx` for a fully rounded pill or circle. */
const val PILL = -1

/** Whether to draw HONOR glass here: supported by the device and not switched off by "reduce dynamic blur". */
@Composable
fun rememberHonorGlass(): Boolean {
    val context = LocalContext.current
    val reduce by com.music.bitchord.data.settings.AppSettings.reduceDynamicBlur
        .collectAsStateWithLifecycle()
    return remember(context) { isHonorGlassSupported(context) } && !reduce
}

/**
 * A [Column] that sits on HONOR glass when [honor] is set, and is a plain Column
 * otherwise. [modifier] carries size, clip and gestures for either path; the
 * caller leaves the fill out of it on HONOR, since the glass is the fill.
 */
@Composable
fun GlassColumn(
    honor: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!honor) {
        Column(modifier = modifier, content = content)
        return
    }
    var dark by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        HonorGlassLayer(
            radiusPx = with(LocalDensity.current) { radius.roundToPx() },
            modifier = Modifier.matchParentSize(),
            small = false,
            onDarkChanged = { dark = it },
        )
        HonorContentTheme(dark) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/**
 * Light text on a dark glass. The glass takes its brightness from whatever is
 * behind it (a dialog's scrim darkens it even in a light theme), so the theme's
 * dark-on-light text can land on dark grey; once the glass says it is dark, the
 * content under it reads with light colours.
 */
@Composable
fun HonorContentTheme(dark: Boolean, content: @Composable () -> Unit) {
    if (!dark) {
        content()
        return
    }
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme.copy(
        onSurface = androidx.compose.ui.graphics.Color.White,
        onBackground = androidx.compose.ui.graphics.Color.White,
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = scheme,
        typography = androidx.compose.material3.MaterialTheme.typography,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides androidx.compose.ui.graphics.Color.White,
            content = content,
        )
    }
}

/** A frame that reports every touch as unhandled, so the pointer falls through to Compose. */
private class TouchPassthroughLayout(context: Context) : android.widget.FrameLayout(context) {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?) = false
}

/** True while the nav bar's surfaces sit on HONOR glass rather than the app's own backdrop glass. */
val LocalHonorGlass = androidx.compose.runtime.staticCompositionLocalOf { false }
