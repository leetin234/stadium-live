package kr.stadiumlive

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** 화면 전용 시계(ms). 그리기 단계에서만 읽어 재구성 없이 매 프레임 다시 그린다. */
@Composable
fun rememberFrameClock(): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t.longValue = it - start }
    }
    return t
}

/** tools/make_stadium.py와 같은 좌표계(1600x1000). */
object Field {
    const val W = 1600f
    const val H = 1000f
    val home = Offset(800f, 820f)
    const val R = 600f
    const val D = 190f
    private fun polar(deg: Float, r: Float) = Offset(home.x + r * kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat(), home.y - r * kotlin.math.sin(Math.toRadians(deg.toDouble())).toFloat())
    val first = polar(45f, D)
    val second = polar(90f, D * 1.4142f)
    val third = polar(135f, D)
    /** 우중간을 가르는 타구의 낙구 지점. */
    val landing = polar(66f, R * 0.84f)
    val ctrl = Offset((home.x + landing.x) / 2 - 40f, landing.y - 230f)
    fun ball(t: Float) = Offset(
        (1 - t) * (1 - t) * home.x + 2 * (1 - t) * t * ctrl.x + t * t * landing.x,
        (1 - t) * (1 - t) * home.y + 2 * (1 - t) * t * ctrl.y + t * t * landing.y,
    )
}

/** 경기장 장면에 필요한 값. 전부 중계 재생 위치에서 계산한다. */
class SceneInput(
    val ms: Long,
    val board: Board,
    val flight: Float?,
    val hitAt: Long?,
    val scores: List<Cue>,
    val cheer: Cue?,
    val myTeamColor: Color,
    val homeColor: Color,
    val scoringColor: Color,
    val lastBeat: Long?,
    val bpm: Int,
    val effects: Boolean,
)

/**
 * 사전 렌더링한 경기장 이미지 위에 주자·타구·조명·관중 불빛·꽃가루를 얹는다.
 * 모든 움직임은 중계 시각(ms) 함수라 탐색해도 장면이 정확히 따라온다.
 */
@Composable
fun StadiumView(modifier: Modifier, s: SceneInput, idle: State<Long>) {
    val ctx = LocalContext.current
    val img: ImageBitmap = remember { ctx.assets.open("stadium.webp").use { BitmapFactory.decodeStream(it) }.asImageBitmap() }
    Canvas(modifier) {
        val sc = max(size.width / Field.W, size.height / Field.H)
        val dx = (size.width - Field.W * sc) / 2
        val dy = (size.height - Field.H * sc) / 2 - (Field.H * sc - size.height).coerceAtLeast(0f) * 0.15f
        fun p(o: Offset) = Offset(dx + o.x * sc, dy + o.y * sc)
        fun len(v: Float) = v * sc

        drawImage(img, dstOffset = IntOffset(dx.toInt(), dy.toInt()), dstSize = IntSize((Field.W * sc).toInt(), (Field.H * sc).toInt()))
        // Quiet the stands so runners and the ball remain the visual focus.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF08121E).copy(alpha = 0.42f), Color.Transparent, Color(0xFF070C14).copy(alpha = 0.25f))))
        val now = idle.value / 1000f

        // 조명이 아주 느리게 숨 쉬듯.
        listOf(270f to 30f, 590f to -20f, 1010f to -20f, 1330f to 30f).forEachIndexed { i, (x, y) ->
            val c = p(Offset(x, y))
            val a = 0.05f + 0.025f * sin(now * 1.3f + i)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), center = c, radius = len(220f)), len(220f), c, blendMode = BlendMode.Plus)
        }

        // 응원가: 관중석 띠가 팀색으로 박자에 맞춰 일렁인다.
        if (s.cheer != null && s.effects) {
            val beat = ((s.ms - s.cheer.t) % 469L) / 469f
            val pulse = 0.35f + 0.65f * exp(-beat * 4f)
            val c = p(Field.home)
            val r0 = len(Field.R + 30f)
            val r1 = len(Field.R + 205f)
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Transparent, (r0 / r1) to Color.Transparent, ((r0 + r1) / 2 / r1) to s.myTeamColor.copy(alpha = 0.42f * pulse), 1f to Color.Transparent,
                    center = c, radius = r1,
                ),
                r1, c, blendMode = BlendMode.Plus,
            )
            // 휴대폰 불빛 파도.
            val rnd = Random(5)
            repeat(260) {
                val a = 34f + rnd.nextFloat() * 112f
                val rr = Field.R + 30f + rnd.nextFloat() * 170f
                val wave = sin(Math.toRadians(a.toDouble()).toFloat() * 9f - s.ms / 240f)
                if (wave > 0.55f) {
                    val o = p(Offset(Field.home.x + rr * kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat(), Field.home.y - rr * kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat()))
                    drawCircle(Color.White.copy(alpha = 0.5f * wave), len(3.2f), o)
                }
            }
        }

        // 주자와 타자(팀색 점 + 흰 테두리).
        val runner = s.homeColor
        listOf(Field.first, Field.second, Field.third).forEachIndexed { i, b ->
            if (s.board.bases.getOrElse(i) { 0 } == 1) dot(p(b + Offset(0f, -24f)), len(13f), runner)
        }
        if (!s.board.final && s.flight == null && !s.board.hit) dot(p(Field.home + Offset(-26f, -10f)), len(13f), runner)

        // 타격 충격파.
        val hitDt = s.hitAt?.let { s.ms - it } ?: -1L
        if (hitDt in 0..650 && s.effects) {
            val k = hitDt / 650f
            val c = p(Field.home)
            drawCircle(Color.White.copy(alpha = 0.85f * (1 - k)), len(30f + 260f * k), c, style = Stroke(len(8f) * (1 - k) + 1f))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f * (1 - k)), Color.Transparent), center = c, radius = len(150f)), len(150f), c)
        }

        // 타구: 그림자(지면) + 궤적 + 공.
        val fp = s.flight ?: if (s.board.hit) 1f else null
        if (fp != null) {
            val steps = 60
            val n = max(1, (steps * fp).toInt())
            val trail = Path().apply {
                moveTo(p(Field.home).x, p(Field.home).y)
                for (i in 1..n) { val o = p(Field.ball(i / steps.toFloat())); lineTo(o.x, o.y) }
            }
            drawPath(trail, Color(0xFF71C9FF).copy(alpha = 0.18f), style = Stroke(len(12f), cap = StrokeCap.Round))
            drawPath(trail, Color.White.copy(alpha = 0.92f), style = Stroke(len(4.5f), cap = StrokeCap.Round))
            val ground = p(Field.home + (Field.landing - Field.home) * fp)
            val height = sin(PI.toFloat() * fp)
            drawOval(Color.Black.copy(alpha = 0.35f * (1 - 0.5f * height)), Offset(ground.x - len(14f), ground.y - len(6f)), Size(len(28f), len(12f)))
            val ball = p(Field.ball(fp))
            val r = len(16f + 12f * height)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = ball, radius = r * 2.4f), r * 2.4f, ball)
            drawCircle(Color.White, r, ball)
            if (s.flight == null) drawCircle(Color.White.copy(alpha = 0.45f), len(26f), p(Field.landing), style = Stroke(len(3f)))
        }

        // 득점: 팀색 섬광 + 꽃가루.
        s.scores.forEach { c ->
            if (!s.effects) return@forEach
            val dt = s.ms - c.t
            if (dt in 0..500) drawRect(Brush.radialGradient(listOf(s.scoringColor.copy(alpha = 0.45f * (1 - dt / 500f)), Color.Transparent), center = p(Field.second), radius = size.maxDimension * 0.7f))
            if (dt in 0..4500) confetti(c.t, dt, s.scoringColor)
        }

        // 긴장: 심박에 맞춰 붉게 맥동하는 가장자리.
        if (s.bpm > 0 && s.lastBeat != null && s.effects) {
            val pulse = exp(-(s.ms - s.lastBeat) / 170f)
            val a = (0.08f + 0.26f * pulse) * ((s.bpm - 90) / 45f).coerceIn(0.15f, 1f)
            drawRect(Brush.radialGradient(0.5f to Color.Transparent, 1f to Color(0xFFFF2D55).copy(alpha = a), center = center, radius = size.maxDimension * 0.6f))
        }
    }
}

private fun DrawScope.dot(c: Offset, r: Float, color: Color) {
    drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.25f, c + Offset(0f, r * 0.25f))
    drawCircle(Color.White, r, c)
    drawCircle(color, r * 0.72f, c)
}

fun DrawScope.confetti(seed: Long, dt: Long, team: Color) {
    val r = Random(seed)
    val t = dt / 1000f
    val palette = listOf(team, Color.White, Color(0xFFFFD34D), team.lighten(0.35f))
    repeat(90) { i ->
        val x0 = r.nextFloat() * size.width
        val vx = (r.nextFloat() - 0.5f) * 160f
        val vy = -320f - r.nextFloat() * 520f
        val spin = (r.nextFloat() - 0.5f) * 720f
        val w = 9f + r.nextFloat() * 10f
        val x = x0 + vx * t + 30f * sin(t * 3f + i)
        val y = size.height * 0.8f + vy * (1 - exp(-1.4f * t)) / 1.4f + 270f * t * t
        val alpha = (1f - t / 4.5f).coerceIn(0f, 1f)
        if (y > -40f && y < size.height + 40f) rotate(spin * t, Offset(x, y)) {
            drawRect(palette[i % palette.size].copy(alpha = alpha), Offset(x - w / 2, y - w / 4), Size(w, w / 2))
        }
    }
}

/** 시연 영상과 같은 글자 방패 엠블럼. */
@Composable
fun Shield(team: Team, modifier: Modifier, letterSize: Float) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c = Color(team.color)
            val path = Path().apply {
                moveTo(w * 0.5f, 0f)
                cubicTo(w * 0.7f, h * 0.08f, w * 0.88f, h * 0.1f, w, h * 0.12f)
                lineTo(w, h * 0.5f)
                cubicTo(w * 0.98f, h * 0.78f, w * 0.72f, h * 0.92f, w * 0.5f, h)
                cubicTo(w * 0.28f, h * 0.92f, w * 0.02f, h * 0.78f, 0f, h * 0.5f)
                lineTo(0f, h * 0.12f)
                cubicTo(w * 0.12f, h * 0.1f, w * 0.3f, h * 0.08f, w * 0.5f, 0f)
                close()
            }
            drawPath(path, c.darken(0.2f))
            drawPath(path, c.lighten(0.3f).copy(alpha = 0.65f), style = Stroke(w * 0.025f))

        }
        Text(team.id, color = Color.White, fontSize = letterSize.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier)
    }
}

/** 앱 로고: 다이아몬드 + 퍼지는 음파. */
@Composable
fun Logo(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF1FC8B8), Color(0xFF2F6BFF))), cornerRadius = CornerRadius(w * 0.26f))
        val c = center
        val dia = Path().apply { moveTo(c.x, c.y - w * 0.15f); lineTo(c.x + w * 0.15f, c.y); lineTo(c.x, c.y + w * 0.15f); lineTo(c.x - w * 0.15f, c.y); close() }
        drawPath(dia, Color.White, style = Stroke(w * 0.07f))
        drawCircle(Color.White, w * 0.045f, c)
        for (k in 1..2) drawArc(
            Color.White, 215f, 110f, false,
            topLeft = Offset(c.x - w * (0.14f + k * 0.1f), c.y - w * (0.14f + k * 0.1f)),
            size = Size(w * (0.28f + k * 0.2f), w * (0.28f + k * 0.2f)),
            style = Stroke(w * 0.06f, cap = StrokeCap.Round),
        )
    }
}

fun Color.lighten(k: Float) = Color(red + (1 - red) * k, green + (1 - green) * k, blue + (1 - blue) * k, alpha)
fun Color.darken(k: Float) = Color(red * (1 - k), green * (1 - k), blue * (1 - k), alpha)
