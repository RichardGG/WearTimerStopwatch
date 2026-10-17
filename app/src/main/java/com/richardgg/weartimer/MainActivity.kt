package com.richardgg.weartimer

import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Accent = Color(0xFF4FD1C5)
private val Dim = Color(0xFF8A8F98)
private val Danger = Color(0xFFFF6B6B)

private enum class Mode { Idle, Running, Paused, Finished }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { TimerScreen(onFinished = ::buzz) }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }
}

/** Max value of each digit: H H : M M : S S */
private val DigitMax = intArrayOf(9, 9, 5, 9, 5, 9)

@Composable
private fun TimerScreen(onFinished: () -> Unit) {
    val digits = remember { mutableStateListOf(0, 0, 0, 0, 0, 0) }
    var mode by remember { mutableStateOf(Mode.Idle) }
    var isTimer by remember { mutableStateOf(false) }
    var totalMs by remember { mutableStateOf(0L) }
    // Elapsed time accumulated before the current run segment started.
    var baseMs by remember { mutableStateOf(0L) }
    var segmentStart by remember { mutableStateOf(0L) }
    var shownMs by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val setSeconds = digits[0] * 36000L + digits[1] * 3600L + digits[2] * 600L +
        digits[3] * 60L + digits[4] * 10L + digits[5]

    LaunchedEffect(mode) {
        if (mode == Mode.Running) {
            while (true) {
                val elapsed = baseMs + (SystemClock.elapsedRealtime() - segmentStart)
                shownMs = elapsed
                if (isTimer && elapsed >= totalMs) {
                    shownMs = totalMs
                    mode = Mode.Finished
                    onFinished()
                    break
                }
                delay(50)
            }
        }
    }
    // Keep "ends at" clock fresh while setting.
    LaunchedEffect(mode) {
        while (mode == Mode.Idle) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun start() {
        isTimer = setSeconds > 0
        totalMs = setSeconds * 1000L
        baseMs = 0L
        shownMs = 0L
        segmentStart = SystemClock.elapsedRealtime()
        mode = Mode.Running
    }

    fun reset() {
        mode = Mode.Idle
        shownMs = 0L
        baseMs = 0L
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            Mode.Idle -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 0 until 6) {
                        DigitPicker(digits[i], DigitMax[i]) { digits[i] = it }
                        if (i == 1 || i == 3) Colon()
                    }
                }
                Spacer(8)
                Text(
                    text = if (setSeconds > 0) {
                        "Ends " + DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(now + setSeconds * 1000))
                    } else "Swipe digits to set timer",
                    color = if (setSeconds > 0) Accent else Dim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(10)
                PillButton(
                    label = if (setSeconds > 0) "Start timer" else "Stopwatch",
                    color = Accent,
                ) { start() }
            }

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val displayMs = if (isTimer) (totalMs - shownMs).coerceAtLeast(0) else shownMs
                // Round up for countdowns so 00:00:00 only shows at the end.
                val secs = if (isTimer) (displayMs + 999) / 1000 else displayMs / 1000
                Text(
                    text = formatHms(secs),
                    color = if (mode == Mode.Finished) Danger else Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!isTimer) {
                    Text(
                        text = ".%02d".format((displayMs % 1000) / 10),
                        color = Dim,
                        fontSize = 16.sp,
                    )
                } else if (mode == Mode.Finished) {
                    Text("Time's up", color = Danger, fontSize = 14.sp)
                } else {
                    val endsAt = System.currentTimeMillis() + displayMs
                    Text(
                        "Ends " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endsAt)),
                        color = Accent,
                        fontSize = 13.sp,
                    )
                }
                Spacer(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode != Mode.Finished) {
                        PillButton(
                            label = if (mode == Mode.Running) "Pause" else "Resume",
                            color = Accent,
                        ) {
                            if (mode == Mode.Running) {
                                baseMs += SystemClock.elapsedRealtime() - segmentStart
                                mode = Mode.Paused
                            } else {
                                segmentStart = SystemClock.elapsedRealtime()
                                mode = Mode.Running
                            }
                        }
                    }
                    PillButton(
                        label = if (mode == Mode.Finished) "Done" else "Reset",
                        color = Danger,
                    ) { reset() }
                }
            }
        }
    }
}

@Composable
private fun Spacer(h: Int) = Box(Modifier.height(h.dp))

@Composable
private fun Colon() {
    Text(":", color = Dim, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 1.dp))
}

@Composable
private fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** A single digit: swipe up to increase, swipe down to decrease (wraps around). */
@Composable
private fun DigitPicker(value: Int, max: Int, onChange: (Int) -> Unit) {
    val stepPx = with(LocalDensity.current) { 24.dp.toPx() }
    var accum by remember { mutableStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    val current by rememberUpdatedValue(value)

    Column(
        Modifier
            .width(22.dp)
            .height(64.dp)
            .pointerInput(max) {
                detectVerticalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false; accum = 0f },
                    onDragCancel = { active = false; accum = 0f },
                ) { change, drag ->
                    change.consume()
                    accum += drag
                    while (accum <= -stepPx) {
                        accum += stepPx
                        onChange(if (current >= max) 0 else current + 1)
                    }
                    while (accum >= stepPx) {
                        accum -= stepPx
                        onChange(if (current <= 0) max else current - 1)
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = (if (value >= max) 0 else value + 1).toString(),
            color = Dim.copy(alpha = 0.4f),
            fontSize = 14.sp,
        )
        Text(
            text = value.toString(),
            color = if (active) Accent else Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.size(width = 22.dp, height = 32.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = (if (value <= 0) max else value - 1).toString(),
            color = Dim.copy(alpha = 0.4f),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun <T> rememberUpdatedValue(v: T) =
    androidx.compose.runtime.rememberUpdatedState(v)

private fun formatHms(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
