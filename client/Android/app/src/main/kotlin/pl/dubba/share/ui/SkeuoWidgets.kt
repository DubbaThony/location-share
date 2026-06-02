package pl.dubba.share.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Status indicator color for the LED beneath a [PanelToggle]. */
enum class LedState { Off, Pending, Ok }

/**
 * Industrial-panel bat-handle toggle. Stick always points straight up from the
 * nut; **stick length scales** between ON and OFF, like the toggle is being
 * pushed up out of the bezel (ON) or pulled down into it (OFF). No rotation -
 * the previous 180° sweep made it look like a rotating knob, which a real
 * bat-handle toggle doesn't do.
 *
 * LED below reflects subsystem state (off / acquiring / locked) independent
 * of the toggle position, which only reflects user intent.
 */
@Composable
fun PanelToggle(
    checked: Boolean,
    ledState: LedState,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    // -1.0 = handle fully DOWN (OFF), +1.0 = handle fully UP (ON). Passing through
    // zero the stick is briefly retracted - matches a real bat-handle moving
    // through the bezel plane.
    val stickRatio by animateFloatAsState(
        targetValue = if (checked) 1.0f else -1.0f,
        animationSpec = tween(220),
        label = "toggle-stick-pos",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .size(64.dp, 120.dp)
                // Switch must draw OVER the LED - the LED sits in the
                // canvas's bottom-padding zone via offset, and the OFF-state
                // stick visually overlaps that zone too. Painting order in a
                // Column follows declaration unless overridden, and zIndex
                // is the override.
                .zIndex(1f)
                .clickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(!checked)
                },
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val nutY = h / 2f

            // Chrome bezel centered around the nut
            val bezelH = h * 0.38f
            val bezelTop = nutY - bezelH / 2
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFC0C0C0), Color(0xFF606060), Color(0xFF969696)),
                    startY = bezelTop,
                    endY = bezelTop + bezelH,
                ),
                topLeft = Offset(w * 0.08f, bezelTop),
                size = Size(w * 0.84f, bezelH),
                cornerRadius = CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = Color(0xFF2A2A2A),
                topLeft = Offset(w * 0.08f, bezelTop),
                size = Size(w * 0.84f, bezelH),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 1.dp.toPx()),
            )

            // Hex nut at the socket
            val nutR = w * 0.22f
            drawCircle(color = Color(0xFF1A1A1A), radius = nutR, center = Offset(cx, nutY))
            drawCircle(color = Color(0xFF464646), radius = nutR * 0.72f, center = Offset(cx, nutY))
            drawCircle(color = Color(0xFF1A1A1A), radius = nutR * 0.36f, center = Offset(cx, nutY))

            // Bat handle - vertical, but extends EITHER UP (ON) OR DOWN (OFF) from
            // the nut. Length and direction both encoded by stickRatio.
            val stickW = w * 0.14f
            val maxStickLen = h * 0.35f
            val signedLen = maxStickLen * stickRatio
            val tipY = nutY - signedLen
            // Shaft goes between nut and tip, regardless of direction.
            val shaftTop = minOf(tipY, nutY)
            val shaftHeight = kotlin.math.abs(signedLen)

            if (shaftHeight > 0.5f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF2A2A2A), Color(0xFF464646)),
                        startY = shaftTop,
                        endY = shaftTop + shaftHeight,
                    ),
                    topLeft = Offset(cx - stickW / 2, shaftTop),
                    size = Size(stickW, shaftHeight),
                    cornerRadius = CornerRadius(stickW / 2, stickW / 2),
                )

                // Ball at the tip - offset slightly toward the nut so it sits
                // inset in the shaft cap rather than centered at the very end.
                val ballOffset = if (signedLen >= 0f) stickW * 0.2f else -stickW * 0.2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF808080), Color(0xFF2A2A2A)),
                        center = Offset(cx - stickW * 0.2f, tipY + ballOffset + stickW * 0.2f),
                        radius = stickW * 1.2f,
                    ),
                    radius = stickW * 0.9f,
                    center = Offset(cx, tipY + ballOffset),
                )
            }
        }

        // LED tucked into the canvas's natural bottom-padding area instead of
        // sitting below it. Negative-spacing isn't a thing in Compose, so we
        // use Modifier.offset on both the LED and the label so they slide up
        // together - the column's *layout* height is unchanged (preserving
        // the toggle's top padding from the parent), only the visual position
        // of the LED+label pair moves into the toggle's housing area.
        Led(state = ledState, modifier = Modifier.offset(y = (-18).dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = HoloColors.OnSurfaceMuted,
            fontWeight = FontWeight.Light,
            modifier = Modifier.offset(y = (-18).dp),
        )
    }
}

@Composable
private fun Led(state: LedState, modifier: Modifier = Modifier) {
    val gradient = when (state) {
        LedState.Off -> Brush.radialGradient(listOf(Color(0xFF1A2A1A), Color(0xFF0A1A0A)))
        LedState.Pending -> Brush.radialGradient(listOf(Color(0xFFFFD060), Color(0xFFB05A00)))
        LedState.Ok -> Brush.radialGradient(listOf(Color(0xFFB0FF80), Color(0xFF1E8000)))
    }
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(gradient),
    )
}

/**
 * CNC handwheel-style encoder.
 *
 * **Gesture model**: on touch-down we record the angle of the finger from the
 * dial center (clock-face: 0° = top, 90° = right, 180° = bottom, 270° = left).
 * On move we compute the angular delta with wrap-around normalisation
 * (`((delta + 540) % 360) - 180`) and accumulate. **Boundary clamping**:
 * accumulated rotation is bounded by what would move us to detent 0 (negative
 * limit) or detent count-1 (positive limit) - past those, both visual rotation
 * and haptic ticks halt. On release we snap to the nearest detent.
 *
 * **Live label**: the displayed [valueLabel] reads the *in-progress* detent
 * during a drag, so the user sees what value the dial will commit to.
 *
 * **Sizing**: the widget fills [modifier]'s constraints, picking the largest
 * square that fits. Caller controls the size by what they pass - typically
 * `Modifier.weight(1f).fillMaxWidth()` to claim available space.
 *
 * **Detent indicators**: short radial marks on the outer housing at each
 * detent angle, so it's visually obvious where the snap points are.
 *
 * **Double-tap** fires [onDoubleTap] - host typically opens a discrete value
 * picker for "set exactly this value" cases.
 */
@Composable
fun HandwheelDial(
    detentIndex: Int,
    detentCount: Int,
    onDetentChange: (Int) -> Unit,
    label: String,
    valueLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDoubleTap: () -> Unit = {},
    valueColor: (Int) -> Color = { HoloColors.OnSurface },
) {
    val haptic = LocalHapticFeedback.current
    val currentIdx by rememberUpdatedState(detentIndex)
    val onChange by rememberUpdatedState(onDetentChange)
    val onDouble by rememberUpdatedState(onDoubleTap)
    val labelFn by rememberUpdatedState(valueLabel)
    val colorFn by rememberUpdatedState(valueColor)

    // Derived from the visual layout: the detent indicator marks span 270°
    // total (see the indicator-draw loop below: `i/(count-1) * 270 - 135`).
    // Matching that here means a finger drag that reaches the last visible
    // tick lands the value on the last detent - no wrap-around past 360°.
    val degPerDetent = if (detentCount > 1) 270f / (detentCount - 1) else 45f
    var dragAngleOffset by remember { mutableFloatStateOf(0f) }

    // Bridges the one-frame gap between gesture release and the parent's
    // detentIndex update. When release commits a new value we stamp the
    // target angle here and read it instead of (baseAngle + dragAngleOffset).
    // Cleared as soon as detentIndex changes - see the LaunchedEffect.
    var pendingSnapAngle by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(detentIndex) {
        pendingSnapAngle = null
    }

    val displayIdx = (currentIdx + (dragAngleOffset / degPerDetent).roundToInt())
        .coerceIn(0, detentCount - 1)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            Canvas(
                modifier = Modifier
                    .size(side)
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures(onDoubleTap = { onDouble() })
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Any in-flight snap is obsolete the moment a new
                            // gesture starts.
                            pendingSnapAngle = null
                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            var prevAngle = angleFromCenter(down.position, center)
                            var accumulated = 0f
                            var lastStep = 0

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.changedToUp() || !change.pressed) break

                                val curr = angleFromCenter(change.position, center)
                                var delta = curr - prevAngle
                                delta = ((delta + 540f) % 360f) - 180f

                                // Hard limits: clamp accumulated rotation so the
                                // dial physically stops at the ends and we don't
                                // emit haptic ticks past the boundary.
                                val minDelta = -currentIdx.toFloat() * degPerDetent
                                val maxDelta = (detentCount - 1 - currentIdx).toFloat() * degPerDetent
                                accumulated = (accumulated + delta).coerceIn(minDelta, maxDelta)
                                prevAngle = curr
                                dragAngleOffset = accumulated

                                val stepNow = (accumulated / degPerDetent).roundToInt()
                                if (stepNow != lastStep) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    lastStep = stepNow
                                }
                                change.consume()
                            }

                            val steps = (accumulated / degPerDetent).roundToInt()
                            val newIndex = (currentIdx + steps).coerceIn(0, detentCount - 1)
                            if (newIndex != currentIdx) {
                                pendingSnapAngle = if (detentCount > 1) {
                                    (newIndex.toFloat() / (detentCount - 1).toFloat()) * 270f - 135f
                                } else 0f
                                onChange(newIndex)
                            }
                            dragAngleOffset = 0f
                        }
                    },
            ) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2, h / 2)
            val outerR = minOf(w, h) / 2 - 2.dp.toPx()

            // Outer dark housing
            drawCircle(color = Color(0xFF1A1A1A), radius = outerR, center = center)
            drawCircle(
                color = Color(0xFF000000),
                radius = outerR,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Subtle texture ticks for the high-resolution-encoder look
            val textureCount = 48
            for (i in 0 until textureCount) {
                val a = (i * 360f / textureCount - 90f) * PI.toFloat() / 180f
                drawLine(
                    color = Color(0xFF666666),
                    start = Offset(center.x + cos(a) * outerR * 0.85f, center.y + sin(a) * outerR * 0.85f),
                    end = Offset(center.x + cos(a) * outerR * 0.92f, center.y + sin(a) * outerR * 0.92f),
                    strokeWidth = 1f,
                )
            }

            // Major detent indicators - bright marks at each detent position, so
            // it's visually obvious where the snap-points are.
            if (detentCount > 1) {
                for (i in 0 until detentCount) {
                    val detentAng = (i.toFloat() / (detentCount - 1).toFloat()) * 270f - 135f
                    val r = (detentAng - 90f) * PI.toFloat() / 180f
                    drawLine(
                        color = Color(0xFFEEEEEE),
                        start = Offset(center.x + cos(r) * outerR * 0.80f, center.y + sin(r) * outerR * 0.80f),
                        end = Offset(center.x + cos(r) * outerR * 0.95f, center.y + sin(r) * outerR * 0.95f),
                        strokeWidth = 2.6f.dp.toPx(),
                    )
                }
            }

            // Silver metallic knob
            val knobR = outerR * 0.78f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFF8F8F8), Color(0xFFCCCCCC), Color(0xFF888888)),
                    center = Offset(center.x - knobR * 0.3f, center.y - knobR * 0.4f),
                    radius = knobR * 1.6f,
                ),
                radius = knobR,
                center = center,
            )
            drawCircle(
                color = Color(0xFF555555),
                radius = knobR,
                center = center,
                style = Stroke(width = 1.5f.dp.toPx()),
            )

            // Center screw with slot
            val screwR = outerR * 0.12f
            drawCircle(color = Color(0xFF333333), radius = screwR, center = center)
            drawCircle(
                color = Color(0xFF111111),
                radius = screwR,
                center = center,
                style = Stroke(width = 0.8f.dp.toPx()),
            )
            drawLine(
                color = Color(0xFF111111),
                start = Offset(center.x - screwR * 0.7f, center.y),
                end = Offset(center.x + screwR * 0.7f, center.y),
                strokeWidth = 1.4f.dp.toPx(),
            )

            // Position pin - base angle from detent + live drag offset, OR
            // the pending snap angle while we're waiting for the parent's
            // detentIndex update to land (prevents a one-frame jump back to
            // the pre-release detent at the moment of release).
            val baseAngle = if (detentCount > 1) {
                (detentIndex.toFloat() / (detentCount - 1).toFloat()) * 270f - 135f
            } else 0f
            val handleAngle = pendingSnapAngle ?: (baseAngle + dragAngleOffset)
            val handleRad = (handleAngle - 90f) * PI.toFloat() / 180f
            val handleCenter = Offset(
                center.x + cos(handleRad) * knobR * 0.65f,
                center.y + sin(handleRad) * knobR * 0.65f,
            )
            drawCircle(color = Color(0xFF2A2A2A), radius = knobR * 0.13f, center = handleCenter)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFDDDDDD), Color(0xFF555555)),
                    center = Offset(handleCenter.x - knobR * 0.04f, handleCenter.y - knobR * 0.04f),
                    radius = knobR * 0.18f,
                ),
                radius = knobR * 0.10f,
                center = handleCenter,
            )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = HoloColors.OnSurfaceMuted,
            fontWeight = FontWeight.Light,
        )
        Text(
            text = labelFn(displayIdx),
            fontSize = 16.sp,
            color = colorFn(displayIdx),
            fontWeight = FontWeight.Normal,
        )
    }
}

/**
 * Computes the clock-face angle (0° = top, 90° = right, 180° = bottom,
 * 270° = left) of a touch [pos] relative to a dial [center]. Used by the
 * [HandwheelDial] rotary gesture to map finger position to a rotation
 * angle independent of where on the dial the user grabs it.
 */
private fun angleFromCenter(pos: Offset, center: Offset): Float {
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val deg = atan2(dy.toDouble(), dx.toDouble()).toFloat() * 180f / PI.toFloat()
    return (deg + 90f + 360f) % 360f
}

/** Big red mushroom emergency-stop button on a yellow base. */
@Composable
fun MushroomStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Canvas(
            modifier = Modifier
                .size(88.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            val released = tryAwaitRelease()
                            pressed = false
                            if (released) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            }
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2
            val cy = h / 2

            drawRoundRect(
                color = Color(0xFFE6C400),
                size = size,
                cornerRadius = CornerRadius(8f, 8f),
            )
            drawRoundRect(
                color = Color.Black,
                size = size,
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 2.dp.toPx()),
            )

            val ringR = minOf(w, h) * 0.4f
            drawCircle(color = Color(0xFF1A1A1A), radius = ringR, center = Offset(cx, cy))

            val domeR = ringR * 0.84f
            val domeShadeMid = if (pressed) 0xFFAA0000.toInt() else 0xFFE83030.toInt()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color(if (pressed) 0xFFC81818 else 0xFFFF6060),
                        Color(domeShadeMid),
                        Color(0xFF800000),
                    ),
                    center = Offset(cx - domeR * 0.25f, cy - domeR * 0.35f),
                    radius = domeR * 1.5f,
                ),
                radius = domeR,
                center = Offset(cx, cy),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "STOP",
            fontSize = 11.sp,
            color = HoloColors.OnSurfaceMuted,
            fontWeight = FontWeight.Normal,
        )
    }
}

/** Six-tooth gear outline button - opens settings. */
@Composable
fun GearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick),
    ) {
        val w = size.width
        val cx = w / 2
        val cy = size.height / 2
        val outerR = minOf(w, size.height) / 2 - 2.dp.toPx()
        val innerR = outerR * 0.62f
        val toothCount = 6
        val color = HoloColors.OnSurfaceMuted

        val path = Path()
        val total = toothCount * 4
        for (i in 0 until total) {
            val t = i.toFloat() / total
            val a = (t * 360f - 90f) * PI.toFloat() / 180f
            val r = if (i % 4 < 2) outerR else innerR
            val px = cx + cos(a) * r
            val py = cy + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
        drawCircle(
            color = color,
            radius = innerR * 0.35f,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Kept for any caller that still wants the outlined-pill version. Unused in MainScreen now. */
@Suppress("unused")
@Composable
fun OutlinedStartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = "START",
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    val outline = when {
        !enabled -> Color(0xFF333333)
        pressed -> Color(0xFF50FF80)
        else -> Color(0xFF1EBE3C)
    }
    val bg = when {
        !enabled -> Color(0xFF0A0A0A)
        pressed -> Color(0xFF1EBE3C).copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Color(0xFF555555)
        pressed -> Color(0xFFB0FF8C)
        else -> Color(0xFF1EBE3C)
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .border(2.dp, outline, RoundedCornerShape(28.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    },
                )
            }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
            style = LocalTextStyle.current.copy(letterSpacing = 3.sp),
        )
    }
}
