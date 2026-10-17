package com.richardgg.weartimer

import android.Manifest
import android.app.RemoteInput
import androidx.wear.input.RemoteInputIntentHelper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFF4FD1C5)
private val Dim = Color(0xFF8A8F98)
private val Danger = Color(0xFFFF6B6B)
private val Card = Color(0xFF1B1B1F)

private enum class Mode { Idle, Running, Paused, Finished }

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { App(requestName = ::requestName) }
    }

    private var nameCallback: ((String) -> Unit)? = null
    private val nameInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                RemoteInput.getResultsFromIntent(data)?.getCharSequence("name")?.toString()
                    ?.let { nameCallback?.invoke(it) }
            }
        }

    /** Opens the watch's system text entry (keyboard / voice / handwriting). */
    private fun requestName(initial: String, onResult: (String) -> Unit) {
        nameCallback = onResult
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder("name").setLabel(initial).build()),
        )
        nameInput.launch(intent)
    }

    // Touching the app silences a ringing alarm.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) Alerts.stopRinging(this)
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        Alerts.stopRinging(this)
    }
}

/** Max value of each digit: H H : M M : S S */
private val DigitMax = intArrayOf(9, 9, 5, 9, 5, 9)

/** A single timer / stopwatch page. */
@Stable
private class TimerState(val id: Int) {
    /** The shared timer list, used to keep default names unique. */
    var siblings: List<TimerState> = emptyList()
    val letter = letterFor(id)
    val digits = mutableStateListOf(0, 0, 0, 0, 0, 0)
    var customName by mutableStateOf<String?>(null)
    var lockedName by mutableStateOf("")
    var mode by mutableStateOf(Mode.Idle)
    var isTimer by mutableStateOf(false)
    var totalMs by mutableLongStateOf(0L)
    /** Elapsed time accumulated before the current run segment started. */
    var baseMs by mutableLongStateOf(0L)
    var segmentStart by mutableLongStateOf(0L)

    val setSeconds: Long
        get() = digits[0] * 36000L + digits[1] * 3600L + digits[2] * 600L +
            digits[3] * 60L + digits[4] * 10L + digits[5]

    /** "20m timer" / "Stopwatch", with " (2)", " (3)"... if an earlier timer already has that name. */
    val defaultName: String
        get() {
            val base = if (setSeconds > 0) "${durationLabel(setSeconds)} timer" else "Stopwatch"
            val taken = siblings.takeWhile { it !== this }.filter { it.touched }.map { it.name }
            var n = 1
            while ((if (n == 1) base else "$base ($n)") in taken) n++
            return if (n == 1) base else "$base ($n)"
        }

    val name: String
        get() = customName ?: if (mode == Mode.Idle) defaultName else lockedName

    /** True once the page has been used, so a fresh blank page should be offered after it. */
    val touched: Boolean
        get() = mode != Mode.Idle || setSeconds > 0 || customName != null

    fun elapsedMs(nowE: Long): Long =
        if (mode == Mode.Running) baseMs + (nowE - segmentStart) else baseMs

    /** Time left for a timer, time elapsed for a stopwatch. */
    fun displayMs(nowE: Long): Long {
        val e = elapsedMs(nowE)
        return if (isTimer) (totalMs - e).coerceAtLeast(0) else e
    }

    fun shownSeconds(nowE: Long): Long {
        val ms = displayMs(nowE)
        // Round up for countdowns so 00:00:00 only shows at the end.
        return if (isTimer) (ms + 999) / 1000 else ms / 1000
    }

    fun start(nowE: Long) {
        lockedName = defaultName
        isTimer = setSeconds > 0
        totalMs = setSeconds * 1000L
        baseMs = 0L
        segmentStart = nowE
        mode = Mode.Running
    }

    fun pause(nowE: Long) {
        baseMs += nowE - segmentStart
        mode = Mode.Paused
    }

    fun resume(nowE: Long) {
        segmentStart = nowE
        mode = Mode.Running
    }

    fun finish() {
        baseMs = totalMs
        mode = Mode.Finished
    }

    /** Stop and return to the setup screen, keeping the digits that were set. */
    fun toIdle() {
        mode = Mode.Idle
        baseMs = 0L
        lockedName = ""
    }

    /** Clear the setup screen. */
    fun clearSetup() {
        for (i in digits.indices) digits[i] = 0
        customName = null
    }
}

private fun letterFor(i: Int): String =
    ('A' + i % 26).toString() + if (i >= 26) (i / 26 + 1).toString() else ""

private fun durationLabel(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) append("${m}m")
        if (s > 0) append("${s}s")
    }
}

private fun formatTime(ms: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun formatHms(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun App(requestName: (String, (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    var counter by remember { mutableIntStateOf(1) }
    val timers = remember { mutableStateListOf<TimerState>().also { it.add(TimerState(0).also { t -> t.siblings = it }) } }
    var nowE by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedId by remember { mutableIntStateOf(0) }
    // Page 0 lists the timers; page 1 shows the selected one.
    val pagerState = rememberPagerState(initialPage = 1) { 2 }
    val scope = rememberCoroutineScope()
    val selected = timers.firstOrNull { it.id == selectedId } ?: timers.firstOrNull()

    fun select(t: TimerState, showTimer: Boolean = true) {
        selectedId = t.id
        if (showTimer) scope.launch { pagerState.animateScrollToPage(1) }
    }

    fun addTimer() {
        val t = TimerState(counter++).also { it.siblings = timers }
        timers.add(t)
        select(t)
    }

    fun removeTimer(t: TimerState) {
        val idx = timers.indexOf(t)
        timers.remove(t)
        if (timers.isEmpty()) {
            // Always keep one timer to show.
            val fresh = TimerState(counter++).also { it.siblings = timers }
            timers.add(fresh)
            selectedId = fresh.id
        } else if (t.id == selectedId) {
            selectedId = timers[minOf(idx, timers.size - 1)].id
        }
    }

    // Keep a wake-up alarm scheduled for every running countdown so it alerts in the background.
    LaunchedEffect(Unit) {
        val scheduled = mutableMapOf<Int, Triple<String, Long, Long>>()
        snapshotFlow {
            timers.filter { it.mode == Mode.Running && it.isTimer }
                .associate { it.id to Triple(it.name, it.segmentStart, it.totalMs - it.baseMs) }
        }.collect { running ->
            for ((id, spec) in running) {
                if (scheduled[id] == spec) continue
                val (name, segmentStart, remaining) = spec
                val left = remaining - (SystemClock.elapsedRealtime() - segmentStart)
                Alerts.schedule(context, id, name, System.currentTimeMillis() + left.coerceAtLeast(0))
                scheduled[id] = spec
            }
            for (id in scheduled.keys.filter { it !in running }) {
                Alerts.cancel(context, id)
                scheduled.remove(id)
            }
        }
    }

    // Show running timers on the watch face / recents as an ongoing activity.
    LaunchedEffect(Unit) {
        snapshotFlow {
            timers.filter { it.mode == Mode.Running }
                .map { listOf(it.name, it.isTimer, it.segmentStart, it.baseMs, it.totalMs) }
        }.collect {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val running = timers.filter { it.mode == Mode.Running }.map { t ->
                val elapsed = t.elapsedMs(nowElapsed)
                RunningInfo(
                    name = t.name,
                    isTimer = t.isTimer,
                    referenceMs = if (t.isTimer) nowWall + (t.totalMs - elapsed) else nowWall - elapsed,
                )
            }
            runCatching { OngoingTimers.update(context, running) }
        }
    }

    // One ticker drives every timer and the clock.
    LaunchedEffect(Unit) {
        while (true) {
            val n = SystemClock.elapsedRealtime()
            nowE = n
            clock = System.currentTimeMillis()
            var anyRunning = false
            for (t in timers) {
                if (t.mode != Mode.Running) continue
                anyRunning = true
                if (t.isTimer && t.elapsedMs(n) >= t.totalMs) {
                    t.finish()
                    Alerts.fire(context, t.id, t.name)
                }
            }
            delay(if (anyRunning) 50 else 250)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatTime(clock),
                color = Dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                key = { p -> if (p == 0) "list" else "timer" },
            ) { page ->
                if (page == 0) {
                    HomePage(
                        timers = timers,
                        selectedId = selected?.id,
                        nowE = nowE,
                        onOpen = { t -> select(t) },
                        onAdd = { addTimer() },
                        onDelete = { t -> removeTimer(t) },
                    )
                } else if (selected != null) {
                    key(selected.id) {
                        TimerPage(
                            selected, nowE, clock,
                            canDelete = timers.size > 1,
                            onDelete = { removeTimer(selected) },
                        ) {
                            requestName(selected.name) { text ->
                                val trimmed = text.trim()
                                selected.customName =
                                    if (trimmed.isEmpty() || trimmed == selected.defaultName) null else trimmed
                                if (selected.mode != Mode.Idle && selected.customName == null && trimmed.isNotEmpty()) {
                                    selected.lockedName = trimmed
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    timers: List<TimerState>,
    selectedId: Int?,
    nowE: Long,
    onOpen: (TimerState) -> Unit,
    onAdd: () -> Unit,
    onDelete: (TimerState) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PillButton("+ Add timer", Accent, onAdd)
        for (t in timers) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (t.id == selectedId) Color(0xFF26323A) else Card)
                    .clickable { onOpen(t) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.name,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatHms(if (t.mode == Mode.Idle) t.setSeconds else t.shownSeconds(nowE)),
                        color = when (t.mode) {
                            Mode.Finished -> Danger
                            Mode.Running -> Accent
                            else -> Dim
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (t.mode) {
                            Mode.Idle -> "Not started"
                            Mode.Finished -> "Time's up"
                            Mode.Paused -> "Paused"
                            else -> if (t.isTimer) {
                                "Ends " + formatTime(System.currentTimeMillis() + t.displayMs(nowE))
                            } else "Stopwatch"
                        },
                        color = Dim,
                        fontSize = 10.sp,
                    )
                }
                Text(
                    "×",
                    color = Dim,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onDelete(t) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TimerPage(
    t: TimerState,
    nowE: Long,
    clock: Long,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onEditName: () -> Unit,
) {
    val wheels = remember { List(6) { WheelState() } }
    Box(Modifier.fillMaxSize()) {
        // Behind the controls, so buttons and the name stay tappable but any other
        // touch on the page can swipe the wheels.
        if (t.mode == Mode.Idle) SwipeSurface(t, wheels)
        TimerPageContent(t, nowE, clock, canDelete, onDelete, onEditName, wheels)
    }
}

@Composable
private fun TimerPageContent(
    t: TimerState,
    nowE: Long,
    clock: Long,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onEditName: () -> Unit,
    wheels: List<WheelState>,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = t.name,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditName)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (canDelete) {
                Text(
                    "×",
                    color = Dim,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 8.dp),
                )
            }
        }
        Spacer(2)
        if (t.mode == Mode.Idle) {
            val swiping = wheels.any { it.active }
            val labelAlpha by animateFloatAsState(if (swiping) 0f else 1f, tween(150), label = "labels")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until 6) {
                    DigitWheel(t.digits[i], DigitMax[i], wheels[i], Modifier.weight(1f))
                    if (i == 1 || i == 3) Colon()
                }
            }
            UnitLabels(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePadding)
                    .graphicsLayer { alpha = labelAlpha },
            )
            Spacer(2)
            Text(
                text = if (t.setSeconds > 0) "Ends " + formatTime(clock + t.setSeconds * 1000)
                else "Swipe digits to set a timer",
                color = if (t.setSeconds > 0) Accent else Dim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(6)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Reset", Danger) { t.clearSetup() }
                PillButton(if (t.setSeconds > 0) "Start" else "Stopwatch", Accent) {
                    t.start(SystemClock.elapsedRealtime())
                }
            }
        } else {
            val displayMs = t.displayMs(nowE)
            Text(
                text = formatHms(t.shownSeconds(nowE)),
                color = if (t.mode == Mode.Finished) Danger else Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!t.isTimer) {
                Text(".%02d".format((displayMs % 1000) / 10), color = Dim, fontSize = 16.sp)
            } else if (t.mode == Mode.Finished) {
                Text("Time's up", color = Danger, fontSize = 14.sp)
            } else {
                Text(
                    "Ends " + formatTime(System.currentTimeMillis() + displayMs),
                    color = Accent,
                    fontSize = 13.sp,
                )
            }
            Spacer(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.mode != Mode.Finished) {
                    PillButton(if (t.mode == Mode.Running) "Pause" else "Resume", Accent) {
                        val n = SystemClock.elapsedRealtime()
                        if (t.mode == Mode.Running) t.pause(n) else t.resume(n)
                    }
                }
                PillButton(if (t.mode == Mode.Finished) "Done" else "Reset", Danger) { t.toIdle() }
            }
        }
    }
}

@Composable
private fun Spacer(h: Int) = Box(Modifier.height(h.dp))

@Composable
private fun Colon() {
    Box(Modifier.width(ColonWidth), contentAlignment = Alignment.Center) {
        Text(":", color = Dim, fontSize = 22.sp)
    }
}

private val ColonWidth = 8.dp

/** "hr  min  sec" captions under the wheels, lined up with each pair of digits. */
@Composable
private fun UnitLabels(modifier: Modifier = Modifier) {
    Row(modifier) {
        for ((i, label) in listOf("hr", "min", "sec").withIndex()) {
            if (i > 0) Box(Modifier.width(ColonWidth))
            Text(label, color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun PillButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Drag state of one digit wheel. */
@Stable
private class WheelState {
    var offset by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
    var settle: Job? = null
}

private val RowHeight = 30.dp
private val PagePadding = 6.dp

/**
 * Catches vertical swipes anywhere on the page and applies them to the digit under the
 * finger's column. Swiping up moves the next number into the centre; values wrap around.
 */
@Composable
private fun SwipeSurface(t: TimerState, wheels: List<WheelState>) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val rowPx = RowHeight.toPx()
                val pad = PagePadding.toPx()
                val colon = ColonWidth.toPx()
                var sel = 0

                // Which digit's column is x in? Digits share the width left after the colons.
                fun digitAt(x: Float): Int {
                    val digitW = (size.width - 2 * pad - 2 * colon) / 6f
                    val rel = x - pad
                    for (i in 0 until 6) {
                        val start = i * digitW + (i / 2) * colon
                        if (rel < start + digitW + colon / 2) return i
                    }
                    return 5
                }

                fun release(w: WheelState) {
                    w.active = false
                    w.settle?.cancel()
                    w.settle = scope.launch {
                        animate(w.offset, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { v, _ ->
                            w.offset = v
                        }
                    }
                }

                detectVerticalDragGestures(
                    onDragStart = { pos ->
                        sel = digitAt(pos.x)
                        wheels[sel].active = true
                        wheels[sel].settle?.cancel()
                    },
                    onDragEnd = { release(wheels[sel]) },
                    onDragCancel = { release(wheels[sel]) },
                ) { change, drag ->
                    change.consume()
                    val w = wheels[sel]
                    val max = DigitMax[sel]
                    val n = max + 1
                    w.offset += drag
                    while (w.offset <= -rowPx / 2) {
                        w.offset += rowPx
                        t.digits[sel] = (t.digits[sel] + 1) % n
                    }
                    while (w.offset >= rowPx / 2) {
                        w.offset -= rowPx
                        t.digits[sel] = (t.digits[sel] - 1 + n) % n
                    }
                }
            },
    )
}

/**
 * A single digit wheel. At rest only the current digit shows; the numbers above and below
 * appear while swiping. The wheel follows the finger and eases back into place on release.
 */
@Composable
private fun DigitWheel(value: Int, max: Int, wheel: WheelState, modifier: Modifier = Modifier) {
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val reveal by animateFloatAsState(if (wheel.active) 1f else 0f, tween(150), label = "reveal")
    val size = max + 1
    fun wrap(v: Int) = ((v % size) + size) % size

    // Not clipped, so the neighbouring numbers can fade in above and below while swiping.
    Box(modifier.height(40.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredHeight(RowHeight * 5)
                .fillMaxWidth()
                .offset { IntOffset(0, wheel.offset.roundToInt()) },
        ) {
            for (i in -2..2) {
                // 0 at the centre row, 1 or more once a row is a full step away.
                val dist = (abs(i * rowPx + wheel.offset) / rowPx).coerceAtMost(1f)
                val visibility = if (i == 0) 1f else reveal
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = RowHeight * (i + 2))
                        .fillMaxWidth()
                        .height(RowHeight)
                        .graphicsLayer {
                            val sc = 1f - 0.4f * dist
                            scaleX = sc
                            scaleY = sc
                            alpha = (1f - 0.7f * dist) * visibility
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = wrap(value + i).toString(),
                        color = if (wheel.active && dist < 0.5f) Accent else Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentHeight(unbounded = true),
                    )
                }
            }
        }
    }
}
