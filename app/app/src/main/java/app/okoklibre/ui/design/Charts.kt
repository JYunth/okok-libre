package app.okoklibre.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.okoklibre.insights.SmoothedDay
import app.okoklibre.ui.theme.AppColors
import app.okoklibre.ui.theme.LocalAppColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Turns a fitted trend into chart days, with an 80% band and at most [MAX_CHART_DAYS] points. */
fun chartDays(days: List<SmoothedDay>): List<ChartDay> {
    val step = max(1, ceil(days.size / MAX_CHART_DAYS.toDouble()).toInt())
    val kept = days.filterIndexed { i, _ -> i % step == 0 || i == days.lastIndex }
    return kept.map { ChartDay(it.epochDay, it.level, it.level - BAND_Z * it.levelSd, it.level + BAND_Z * it.levelSd, it.observed) }
}

private const val MAX_CHART_DAYS = 300
private const val BAND_Z = 1.28

/** One day of a trend: the estimated level, its uncertainty, and what was actually read. */
data class ChartDay(
    val epochDay: Long,
    val level: Double,
    val low: Double,
    val high: Double,
    val observed: Double,
)

/** Plot-area mapping shared by the line charts. */
private class Plot(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    fun x(day: Double): Float = left + ((day - minX) / (maxX - minX).coerceAtLeast(1.0)).toFloat() * width
    fun y(value: Double): Float =
        top + height - ((value - minY) / (maxY - minY).coerceAtLeast(1e-6)).toFloat() * height
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    alignEnd: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(if (alignEnd) x - layout.size.width else x, y))
}

/**
 * The main chart. The line is the estimated true level and the dots are what the scale
 * actually read, so the gap between them shows the noise at a glance. Drag to read any day.
 */
@Composable
fun TrendChart(
    days: List<ChartDay>,
    valueLabel: (Double) -> String,
    dateLabel: (Long) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    goal: Double? = null,
    forecast: Pair<Long, Double>? = null,
    onScrub: (ChartDay?) -> Unit = {},
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.muted)
    val haptics = LocalHapticFeedback.current
    val scrubCallback by rememberUpdatedState(onScrub)
    var scrubIndex by remember(days) { mutableIntStateOf(-1) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(days.firstOrNull()?.epochDay, days.size) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(650))
    }
    if (days.size < 2) return

    fun select(xPosition: Float, width: Float) {
        val minX = days.first().epochDay.toDouble()
        val maxX = max(days.last().epochDay.toDouble(), forecast?.first?.toDouble() ?: 0.0)
        val target = minX + (xPosition / width).coerceIn(0f, 1f) * (maxX - minX)
        val index = days.indices.minBy { abs(days[it].epochDay - target) }
        if (index != scrubIndex) {
            scrubIndex = index
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scrubCallback(days[index])
        }
    }

    fun release() {
        scrubIndex = -1
        scrubCallback(null)
    }

    Canvas(
        modifier
            .semantics { contentDescription = summary }
            .pointerInput(days) {
                detectHorizontalDragGestures(
                    onDragStart = { select(it.x, size.width.toFloat()) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, _ -> select(change.position.x, size.width.toFloat()) }
            }
            .pointerInput(days) {
                detectTapGestures(onPress = {
                    select(it.x, size.width.toFloat())
                    tryAwaitRelease()
                    release()
                })
            },
    ) {
        val bottomInset = 22.dp.toPx()
        var low = days.minOf { minOf(it.low, it.observed, it.level) }
        var high = days.maxOf { maxOf(it.high, it.observed, it.level) }
        goal?.let { low = minOf(low, it); high = maxOf(high, it) }
        forecast?.let { low = minOf(low, it.second); high = maxOf(high, it.second) }
        val pad = max((high - low) * 0.12, 0.1)
        val plot = Plot(
            left = 0f,
            top = 16.dp.toPx(),
            width = size.width - 6.dp.toPx(),
            height = size.height - bottomInset - 16.dp.toPx(),
            minX = days.first().epochDay.toDouble(),
            maxX = max(days.last().epochDay.toDouble(), forecast?.first?.toDouble() ?: 0.0),
            minY = low - pad,
            maxY = high + pad,
        )
        val hairline = 1.dp.toPx()

        listOf(high, low).forEach { value ->
            val y = plot.y(value)
            drawLine(colors.hairline, Offset(0f, y), Offset(size.width, y), hairline)
        }
        label(measurer, valueLabel(high), labelStyle, size.width, plot.y(high) - 15.dp.toPx(), alignEnd = true)
        label(measurer, valueLabel(low), labelStyle, size.width, plot.y(low) + 2.dp.toPx(), alignEnd = true)
        label(measurer, dateLabel(days.first().epochDay), labelStyle, 0f, size.height - 15.dp.toPx())
        label(
            measurer,
            dateLabel(forecast?.first ?: days.last().epochDay),
            labelStyle,
            size.width,
            size.height - 15.dp.toPx(),
            alignEnd = true,
        )

        goal?.let {
            val y = plot.y(it)
            drawLine(
                colors.ink.copy(alpha = 0.5f),
                Offset(0f, y),
                Offset(size.width, y),
                hairline,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }

        clipRect(right = size.width * reveal.value) {
            val band = Path()
            days.forEachIndexed { i, d ->
                val x = plot.x(d.epochDay.toDouble())
                if (i == 0) band.moveTo(x, plot.y(d.high)) else band.lineTo(x, plot.y(d.high))
            }
            days.asReversed().forEach { d -> band.lineTo(plot.x(d.epochDay.toDouble()), plot.y(d.low)) }
            band.close()
            drawPath(band, colors.accent.copy(alpha = 0.09f))

            val dotRadius = if (days.size > 120) 1.dp.toPx() else 1.75.dp.toPx()
            days.forEach { d ->
                drawCircle(
                    colors.muted.copy(alpha = 0.55f),
                    dotRadius,
                    Offset(plot.x(d.epochDay.toDouble()), plot.y(d.observed)),
                )
            }

            val line = Path()
            days.forEachIndexed { i, d ->
                val x = plot.x(d.epochDay.toDouble())
                val y = plot.y(d.level)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(
                line,
                colors.accent,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            forecast?.let { (day, value) ->
                val end = days.last()
                drawLine(
                    colors.accent,
                    Offset(plot.x(end.epochDay.toDouble()), plot.y(end.level)),
                    Offset(plot.x(day.toDouble()), plot.y(value)),
                    1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx())),
                    cap = StrokeCap.Round,
                )
            }
        }

        val marked = days.getOrNull(scrubIndex) ?: days.last().takeIf { reveal.value >= 1f }
        marked?.let { d ->
            val point = Offset(plot.x(d.epochDay.toDouble()), plot.y(d.level))
            if (scrubIndex >= 0) {
                drawLine(colors.ink.copy(alpha = 0.3f), Offset(point.x, plot.top), Offset(point.x, plot.top + plot.height), hairline)
            }
            drawCircle(colors.ground, 6.dp.toPx(), point)
            drawCircle(colors.accent, 4.dp.toPx(), point)
        }
    }
}

/** One line of a [ChangeChart]: kg moved since the left edge. */
data class ChangeSeries(val color: Color, val points: List<Pair<Long, Double>>)

/**
 * Two tissues on one zero line. Muscle and fat live on very different scales, so
 * plotting their change from the same start is what makes both readable.
 */
@Composable
fun ChangeChart(
    series: List<ChangeSeries>,
    dateLabel: (Long) -> String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.muted)
    val all = series.flatMap { it.points }
    if (all.size < 2) return
    Canvas(modifier.semantics { contentDescription = summary }) {
        val bottomInset = 22.dp.toPx()
        val low = minOf(0.0, all.minOf { it.second })
        val high = maxOf(0.0, all.maxOf { it.second })
        val pad = max((high - low) * 0.1, 0.1)
        val plot = Plot(
            0f, 6.dp.toPx(), size.width - 6.dp.toPx(), size.height - bottomInset - 6.dp.toPx(),
            all.minOf { it.first }.toDouble(), all.maxOf { it.first }.toDouble(), low - pad, high + pad,
        )
        val zero = plot.y(0.0)
        drawLine(colors.faint, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
        series.forEach { s ->
            val path = Path()
            s.points.forEachIndexed { i, (day, value) ->
                val x = plot.x(day.toDouble())
                val y = plot.y(value)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            s.points.lastOrNull()?.let { (day, value) ->
                drawCircle(colors.ground, 5.5.dp.toPx(), Offset(plot.x(day.toDouble()), plot.y(value)))
                drawCircle(s.color, 3.5.dp.toPx(), Offset(plot.x(day.toDouble()), plot.y(value)))
            }
        }
        label(measurer, dateLabel(plot.minX.toLong()), labelStyle, 0f, size.height - 15.dp.toPx())
        label(measurer, dateLabel(plot.maxX.toLong()), labelStyle, size.width, size.height - 15.dp.toPx(), alignEnd = true)
    }
}

/** A person's position on the fat and lean axes over time. Diagonals are lines of equal BMI. */
@Composable
fun CompositionMap(
    points: List<Pair<Double, Double>>,
    xAxis: String,
    yAxis: String,
    bmiLabel: (Int) -> String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.muted)
    if (points.size < 2) return
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(900))
    }
    Canvas(modifier.semantics { contentDescription = summary }) {
        val bottomInset = 20.dp.toPx()
        val xs = points.map { it.first }
        val ys = points.map { it.second }
        val spanX = max(xs.max() - xs.min(), 1.5)
        val spanY = max(ys.max() - ys.min(), 1.5)
        val midX = (xs.max() + xs.min()) / 2
        val midY = (ys.max() + ys.min()) / 2
        val minX = midX - spanX * 0.75
        val maxX = midX + spanX * 0.75
        val minY = midY - spanY * 0.75
        val maxY = midY + spanY * 0.75
        val plot = Plot(0f, 0f, size.width, size.height - bottomInset, minX, maxX, minY, maxY)

        val firstBmi = ceil(minX + minY).toInt()
        val lastBmi = floor(maxX + maxY).toInt()
        val step = max(1, (lastBmi - firstBmi) / 4)
        clipRect(bottom = plot.height) {
            for (bmi in firstBmi..lastBmi step step) {
                drawLine(
                    colors.hairline,
                    Offset(plot.x(bmi - maxY), plot.y(maxY)),
                    Offset(plot.x(bmi - minY), plot.y(minY)),
                    1.dp.toPx(),
                )
                val atX = plot.x(bmi - (minY + spanY * 0.12))
                if (atX in 0f..(size.width - 44.dp.toPx())) {
                    label(measurer, bmiLabel(bmi), labelStyle, atX + 3.dp.toPx(), plot.y(minY + spanY * 0.12))
                }
            }
        }
        drawLine(colors.faint, Offset(0f, plot.height), Offset(size.width, plot.height), 1.dp.toPx())
        label(measurer, xAxis, labelStyle, size.width, size.height - 15.dp.toPx(), alignEnd = true)
        label(measurer, yAxis, labelStyle, 0f, 0f)

        val visible = max(2, ceil(points.size * reveal.value).toInt())
        val path = Path()
        points.take(visible).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(plot.x(x), plot.y(y)) else path.lineTo(plot.x(x), plot.y(y))
        }
        drawPath(path, colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val (startX, startY) = points.first()
        drawCircle(colors.ground, 4.dp.toPx(), Offset(plot.x(startX), plot.y(startY)))
        drawCircle(colors.accent, 4.dp.toPx(), Offset(plot.x(startX), plot.y(startY)), style = Stroke(1.5.dp.toPx()))
        val (endX, endY) = points[visible - 1]
        drawCircle(colors.ground, 7.dp.toPx(), Offset(plot.x(endX), plot.y(endY)))
        drawCircle(colors.accent, 5.dp.toPx(), Offset(plot.x(endX), plot.y(endY)))
    }
}

/** Where a value sits against its standard range. Position does the talking, colour only flags concern. */
@Composable
fun Ruler(
    value: Double,
    low: Double,
    high: Double,
    modifier: Modifier = Modifier,
    concern: Boolean = false,
    height: Dp = 16.dp,
) {
    val colors = LocalAppColors.current
    val span = max(high - low, 1e-6)
    val axisMin = low - span * 0.75
    val axisMax = high + span * 0.75
    val target = ((value.coerceIn(axisMin, axisMax) - axisMin) / (axisMax - axisMin)).toFloat()
    val position by animateFloatAsState(target, tween(500), label = "ruler")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val mid = size.height / 2
        val thin = 2.dp.toPx()
        drawLine(colors.hairline, Offset(0f, mid), Offset(size.width, mid), thin, StrokeCap.Round)
        val startX = ((low - axisMin) / (axisMax - axisMin)).toFloat() * size.width
        val endX = ((high - axisMin) / (axisMax - axisMin)).toFloat() * size.width
        drawLine(colors.muted, Offset(startX, mid), Offset(endX, mid), thin, StrokeCap.Round)
        val marker = Offset(position * size.width, mid)
        drawCircle(colors.ground, 7.dp.toPx(), marker)
        drawCircle(if (concern) colors.attention else colors.accent, 5.dp.toPx(), marker)
    }
}

/** A share of the whole, as a single quiet bar. */
data class BarPart(val fraction: Float, val color: Color)

@Composable
fun ShareBar(parts: List<BarPart>, modifier: Modifier = Modifier, thickness: Dp = 10.dp) {
    Row(modifier.fillMaxWidth().height(thickness), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.filter { it.fraction > 0f }.forEach { part ->
            Box(
                Modifier
                    .weight(part.fraction)
                    .height(thickness)
                    .clip(RoundedCornerShape(2.dp))
                    .background(part.color),
            )
        }
    }
}

/** A colour key entry beside a label and a figure. */
@Composable
fun KeyRow(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Offsets either side of a zero line, one per weekday. */
@Composable
fun OffsetBars(
    values: List<Pair<String, Double?>>,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.muted)
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = summary }) {
        val bottomInset = 18.dp.toPx()
        val plotHeight = size.height - bottomInset
        val mid = plotHeight / 2
        val limit = max(values.mapNotNull { it.second }.maxOfOrNull { abs(it) } ?: 0.0, 0.1)
        val cell = size.width / values.size
        val barWidth = 10.dp.toPx()
        drawLine(colors.hairline, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        values.forEachIndexed { i, (name, value) ->
            val cx = cell * i + cell / 2
            if (value != null) {
                val h = (abs(value) / limit).toFloat() * (mid - 4.dp.toPx())
                drawRoundRect(
                    if (value > 0) colors.ink else colors.accent,
                    Offset(cx - barWidth / 2, if (value > 0) mid - h else mid),
                    Size(barWidth, h.coerceAtLeast(1.dp.toPx())),
                    CornerRadius(2.dp.toPx()),
                )
            }
            val layout = measurer.measure(name, labelStyle)
            drawText(layout, topLeft = Offset(cx - layout.size.width / 2, size.height - 15.dp.toPx()))
        }
    }
}

/** Half a year of weigh-ins, one cell per day, weeks as columns. */
@Composable
fun DayGrid(
    weeks: List<List<Boolean?>>,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val rows = weeks.maxOfOrNull { it.size } ?: 7
    Canvas(
        modifier
            .fillMaxWidth()
            .height(((rows * 13) - 3).dp)
            .semantics { contentDescription = summary },
    ) {
        val gap = 3.dp.toPx()
        val cell = (size.height - gap * (rows - 1)) / rows
        val columnStep = if (weeks.size > 1) (size.width - cell) / (weeks.size - 1) else 0f
        weeks.forEachIndexed { w, days ->
            days.forEachIndexed { d, present ->
                if (present != null) {
                    drawRoundRect(
                        if (present) colors.accent else colors.hairline,
                        Offset(w * columnStep, d * (cell + gap)),
                        Size(cell, cell),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** A thin band showing which stretches of the period were which. */
@Composable
fun SegmentStrip(segments: List<Pair<Long, Color>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.first }.coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { (length, color) ->
            Box(
                Modifier
                    .weight((length.toFloat() / total).coerceAtLeast(0.01f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

/** Colour for a tissue, so the same thing wears the same colour on every screen. */
object Tissue {
    fun muscle(colors: AppColors): Color = colors.accent
    fun fat(colors: AppColors): Color = colors.ink
    fun bone(colors: AppColors): Color = colors.faint
}

/** A label stacked over its figure. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalAppColors.current.muted)
    }
}
