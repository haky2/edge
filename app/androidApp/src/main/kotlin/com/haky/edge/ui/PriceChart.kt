package com.haky.edge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haky.edge.model.DailyBar
import com.haky.edge.ui.theme.ChangeDown
import com.haky.edge.ui.theme.ChangeUp
import com.haky.edge.ui.theme.OrangeAccent
import kotlin.math.max

// 가격 차트 기간 토글 — iOS ChartPeriod와 1:1.
enum class ChartPeriod(val label: String, val barCount: Int) {
    TODAY("오늘", 0),
    WEEK("1주", 5),
    M1("1개월", 22),
    M3("3개월", 66),
    ALL("전체", 999),
}

// y축 가격 레이블: 1만 이상은 "N만", 그 외는 천단위 쉼표.
internal fun priceYLabel(v: Double): String {
    val n = v.toLong()
    return if (n >= 10_000) "${n / 10_000}만" else n.fmt()
}

// 세그먼트 토글 (iOS .segmented Picker 대응). API 불확실성 회피 위해 직접 구현.
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(2.dp),
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// "내 기준선 차트": 종가 라인 + 일별 고저 밴드 + 20일 추세선 위에
// 내 평단·목표·손절을 가로 기준선으로 얹는다. (iOS PriceLineChart 대응)
private class ChartPt(
    val close: Double,
    val high: Double,
    val low: Double,
    val ma20: Double?,
)

@Composable
fun PriceLineChart(
    bars: List<DailyBar>,
    displayCount: Int,
    avg: Double?,
    target: Double?,
    stop: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    // pts: 최신일이 앞이라 reverse 후 displayCount 만큼 최근 구간. MA20은 전체 시계열로 계산.
    val all = bars.asReversed() // [0]=oldest
    if (all.isEmpty()) return
    val start = max(0, all.size - displayCount)
    val pts = (start until all.size).map { i ->
        val ma = if (i >= 19) {
            var s = 0.0
            for (j in (i - 19)..i) s += all[j].close.toDouble()
            s / 20
        } else null
        ChartPt(all[i].close.toDouble(), all[i].high.toDouble(), all[i].low.toDouble(), ma)
    }
    if (pts.isEmpty()) return

    val baselines = listOfNotNull(target, avg, stop)
    val ma20s = pts.mapNotNull { it.ma20 }
    val lo = ((pts.map { it.low } + baselines + ma20s).min()) * 0.99
    val hiRaw = ((pts.map { it.high } + baselines + ma20s).max()) * 1.01
    val hi = if (hiRaw > lo) hiRaw else lo + 1

    val labelStyle = TextStyle(fontSize = 9.sp, color = secondary)

    Canvas(modifier = modifier) {
        val leftPad = with(density) { 44.dp.toPx() }   // 좌측 기준선 컬러 레이블
        val rightPad = with(density) { 30.dp.toPx() }  // 우측 가격 눈금
        val topPad = with(density) { 6.dp.toPx() }
        val botPad = with(density) { 6.dp.toPx() }
        val plotLeft = leftPad
        val plotRight = size.width - rightPad
        val plotW = plotRight - plotLeft
        val plotTop = topPad
        val plotBot = size.height - botPad
        val plotH = plotBot - plotTop
        val n = pts.size

        fun xAt(i: Int): Float =
            if (n <= 1) plotLeft + plotW / 2f
            else plotLeft + plotW * i / (n - 1).toFloat()
        fun yAt(v: Double): Float =
            plotBot - (plotH * ((v - lo) / (hi - lo))).toFloat()

        // 우측 가격 눈금 3개 + 옅은 가로 그리드.
        val gridDash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2.0
            val y = yAt(v)
            drawLine(
                color = secondary.copy(alpha = 0.25f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 0.6f,
                pathEffect = gridDash,
            )
            val txt = measurer.measure(priceYLabel(v), labelStyle)
            drawText(
                txt,
                topLeft = Offset(
                    plotRight + with(density) { 2.dp.toPx() },
                    y - txt.size.height / 2f,
                ),
            )
        }

        // 일별 고저 밴드 — 고가 폴리라인 → 저가 역순 폴리라인 닫아서 채움.
        val band = Path()
        pts.forEachIndexed { i, p ->
            val x = xAt(i); val y = yAt(p.high)
            if (i == 0) band.moveTo(x, y) else band.lineTo(x, y)
        }
        for (i in pts.indices.reversed()) {
            band.lineTo(xAt(i), yAt(pts[i].low))
        }
        band.close()
        drawPath(band, color = onSurface.copy(alpha = 0.10f))

        // 20일 추세선(주황 점선).
        val maDash = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
        val maPath = Path()
        var maStarted = false
        pts.forEachIndexed { i, p ->
            val ma = p.ma20 ?: return@forEachIndexed
            val x = xAt(i); val y = yAt(ma)
            if (!maStarted) { maPath.moveTo(x, y); maStarted = true } else maPath.lineTo(x, y)
        }
        if (maStarted) {
            drawPath(
                maPath, color = OrangeAccent,
                style = Stroke(width = with(density) { 1.2.dp.toPx() }, pathEffect = maDash),
            )
        }

        // 종가 라인(굵게).
        val closePath = Path()
        pts.forEachIndexed { i, p ->
            val x = xAt(i); val y = yAt(p.close)
            if (i == 0) closePath.moveTo(x, y) else closePath.lineTo(x, y)
        }
        drawPath(closePath, color = onSurface, style = Stroke(width = with(density) { 2.2.dp.toPx() }))

        // 마지막 종가점 강조.
        val last = pts.last()
        drawCircle(
            color = onSurface,
            radius = with(density) { 3.5.dp.toPx() },
            center = Offset(xAt(n - 1), yAt(last.close)),
        )

        // 내 기준선들(목표 빨강 / 평단 초록 / 손절 파랑) + 좌측 컬러 레이블.
        val avgGreen = Color(0xFF34C759)
        val baseDash = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))
        fun drawBaseline(value: Double?, color: Color, label: String) {
            val v = value ?: return
            if (v < lo || v > hi) {
                // 범위 밖이면 라인 생략, 레이블만 가장자리에.
            }
            val y = yAt(v).coerceIn(plotTop, plotBot)
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(plotLeft, y), end = Offset(plotRight, y),
                strokeWidth = with(density) { 1.dp.toPx() }, pathEffect = baseDash,
            )
            val txt = measurer.measure(
                "$label ${priceYLabel(v)}",
                TextStyle(fontSize = 8.sp, color = color, fontWeight = FontWeight.Medium),
            )
            drawText(txt, topLeft = Offset(0f, y - txt.size.height / 2f))
        }
        drawBaseline(target, ChangeUp, "목표")
        drawBaseline(avg, avgGreen, "평단")
        drawBaseline(stop, ChangeDown, "손절")
    }
}

// 거래량 막대. 가격 차트와 같은 기간·개수로 잘라 x축 정렬. 급증일(구간 평균 2배↑) 빨강.
@Composable
fun VolumeBars(
    bars: List<DailyBar>,
    displayCount: Int,
    modifier: Modifier = Modifier,
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val all = bars.asReversed()
    if (all.isEmpty()) return
    val start = max(0, all.size - displayCount)
    val shown = all.subList(start, all.size)
    val avgVol = if (shown.isEmpty()) 0.0 else shown.sumOf { it.volume.toDouble() } / shown.size
    val maxVol = shown.maxOf { it.volume.toDouble() }.coerceAtLeast(1.0)

    Canvas(modifier = modifier) {
        val n = shown.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = slot * 0.6f
        shown.forEachIndexed { i, b ->
            val v = b.volume.toDouble()
            val h = (size.height * (v / maxVol)).toFloat()
            val hot = avgVol > 0 && v >= avgVol * 2
            val x = slot * i + (slot - barW) / 2f
            drawRect(
                color = if (hot) ChangeUp.copy(alpha = 0.6f) else secondary.copy(alpha = 0.35f),
                topLeft = Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}
