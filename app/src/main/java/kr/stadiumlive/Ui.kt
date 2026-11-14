package kr.stadiumlive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

// ---- 디자인 토큰(시연 영상 기준) ----
private val Bg = Color(0xFF0B0E14)
private val Card = Color(0xFF151922)
private val CardHi = Color(0xFF1C212C)
private val Line = Color(0xFF262C3A)
private val TextHi = Color(0xFFF4F6FA)
private val TextMid = Color(0xFFC3C9D6)
private val TextLo = Color(0xFF858EA3)
private val Mint = Color(0xFF2ED3A0)
private val MintBg = Color(0xFF10291F)
private val Blue = Color(0xFF2F6BFF)
private val Purple = Color(0xFF7752E0)
private val Orange = Color(0xFFE8763C)
private val Red = Color(0xFFF0424F)
private val Amber = Color(0xFFF5B83D)
private val Yellow = Color(0xFFF6C443)

private val Pretendard = FontFamily(
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold),
)

@Composable
fun App(game: Game, engine: CueEngine, ui: UiState, act: Actions) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density * 1.3f, d.fontScale),
        LocalTextStyle provides TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Medium, color = TextHi),
    ) {
        val idle = rememberFrameClock()
        val scene = sceneFor(game, engine, ui)
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111622), Bg))).padding(horizontal = 22.dp, vertical = 16.dp)) {
            Header(ui, act)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f)) {
                when (ui.screen) {
                    Screen.SETUP -> Setup(game, ui, act)
                    Screen.PLAYER ->
                        if (ui.mode == Mode.PARKED && ui.prefs.immersiveOnPark) Immersive(game, engine, ui, act, scene, idle)
                        else DrivingView(game, ui, act, idle)
                    Screen.FEEDBACK -> Feedback(ui, act)
                }
            }
        }
    }
}

private fun sceneFor(game: Game, engine: CueEngine, ui: UiState): SceneInput {
    val ms = ui.position
    val scores = game.cues.filter { it.type == "SCORE" && it.t <= ms }
    val tension = engine.tensionAt(ms)
    return SceneInput(
        ms = ms,
        board = game.boardAt(ms),
        flight = engine.flightAt(ms),
        hitAt = game.cues.firstOrNull { it.type == "HIT" && it.t <= ms }?.t,
        scores = scores,
        cheer = game.cues.firstOrNull { it.type == "CHEER" && it.t <= ms && ms < it.t + it.durationMs },
        myTeamColor = Color(game.team(ui.prefs.myTeam).color),
        homeColor = Color(game.home.color),
        scoringColor = scores.lastOrNull()?.let { Color(game.team(it.team).color) } ?: Color(game.home.color),
        lastBeat = if (tension != null) engine.beats(ms - 2_000, ms).lastOrNull() else null,
        bpm = tension?.bpm ?: 0,
        effects = ui.prefs.effectsOn,
    )
}

// ---------------- 공통 머리글 ----------------
@Composable
private fun Header(ui: UiState, act: Actions) {
    val page = when (ui.screen) {
        Screen.SETUP -> "청취 설정"
        Screen.PLAYER -> if (ui.mode == Mode.PARKED && ui.prefs.immersiveOnPark) "몰입 모드" else "해설 중심"
        Screen.FEEDBACK -> "청취 평가"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Text("STADIUM LIVE", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Text("   ›   ", fontSize = 18.sp, color = TextLo)
        Text(page, fontSize = 19.sp, color = TextMid, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        val sim = ui.simulation
        when {
            sim != null -> Tag("● $sim", Amber)
            ui.focus == Focus.DUCKED -> Tag("● 내비 안내 · 음량 낮춤", Amber)
            ui.focus == Focus.PAUSED_BY_OTHER -> Tag("● 통화 중 · 일시정지", Amber)
        }
        Spacer(Modifier.width(8.dp))
        when {
            !ui.modeKnown -> Tag("● 주행 상태 확인 불가", Amber)
            ui.mode == Mode.DRIVING -> Tag("● 주행 중 · 해설 중심", Amber)
            ui.screen == Screen.SETUP -> Row(
                Modifier.clip(RoundedCornerShape(50)).background(CardHi).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("P", color = Bg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text("  정차 중에만 변경할 수 있어요", fontSize = 15.sp, color = TextMid)
            }
            else -> Tag("● 정차 중", Mint)
        }
        if (ui.screen == Screen.PLAYER) {
            Spacer(Modifier.width(8.dp))
            GhostButton("설정", enabled = ui.mode == Mode.PARKED, onClick = act.openSetup)
        }
    }
}

// ---------------- 01 출발 전 설정 ----------------
@Composable
private fun Setup(game: Game, ui: UiState, act: Actions) {
    val parked = ui.mode == Mode.PARKED
    var p by remember(ui.prefs) { mutableStateOf(ui.prefs) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                Label("오늘의 경기")
                SelectCard(selected = true, enabled = true, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallTag("녹화 샘플", Color(0xFF3A2A0E), Amber)
                        Text("  오후 6:30 · 한강 스타디움", fontSize = 16.sp, color = TextMid)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Shield(game.home, Modifier.size(42.dp, 48.dp), 20f)
                        Text("  ${game.home.name}", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("  vs  ", fontSize = 17.sp, color = TextLo)
                        Text("${game.away.name}  ", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Shield(game.away, Modifier.size(42.dp, 48.dp), 20f)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("9회말 2사 만루 · 1분 7초 하이라이트", fontSize = 15.sp, color = TextLo)
                }
                Spacer(Modifier.height(14.dp))
                SelectCard(selected = false, enabled = false, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallTag("준비 중", CardHi, TextLo)
                        Text("  다음 녹화 샘플", fontSize = 16.sp, color = TextLo)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("새 경기 주석이 추가되면 여기에 표시돼요", fontSize = 17.sp, color = TextLo)
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Label("응원 팀")
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(game.home to "홈 · 응원 리듬 A", game.away to "원정 · 응원 리듬 B").forEach { (t, sub) ->
                        SelectCard(
                            selected = p.myTeam == t.id, enabled = parked,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { p = p.copy(myTeam = t.id) },
                        ) {
                            Spacer(Modifier.weight(1f))
                            Shield(t, Modifier.align(Alignment.CenterHorizontally).size(96.dp, 108.dp), 46f)
                            Spacer(Modifier.height(20.dp))
                            Text(t.name, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Spacer(Modifier.height(6.dp))
                            Text(sub, fontSize = 15.sp, color = TextLo, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Column(Modifier.weight(1.1f).fillMaxHeight()) {
                val levels = listOf("끄기" to 0f, "약" to 0.3f, "중" to 0.7f, "강" to 1f)
                val idx = if (!p.effectsOn) 0 else levels.drop(1).indexOfFirst { abs(it.second - p.intensity) < 0.16f }.let { if (it < 0) 2 else it + 1 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("연출 강도", Modifier.weight(1f))
                    Text(listOf("효과 없이", "약하게", "보통으로", "강하게")[idx], color = Mint, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 22.dp, vertical = 20.dp)) {
                    StopSlider(levels.size, idx, parked) { i ->
                        p = if (i == 0) p.copy(effectsOn = false) else p.copy(effectsOn = true, intensity = levels[i].second)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        levels.forEachIndexed { i, (l, _) ->
                            Text(l, fontSize = 15.sp, color = if (i == idx) TextHi else TextLo, modifier = Modifier.weight(1f),
                                textAlign = if (i == 0) TextAlign.Start else if (i == levels.lastIndex) TextAlign.End else TextAlign.Center)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Label("상태별 모드")
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ToggleRow("주행 중 · 해설 중심", "효과 약하게 · 점수와 이닝만 표시", true, false) {}
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    ToggleRow("정차 시 · 몰입 모드 제안", "정차 확인 후 강한 연출 · 상세 화면", p.immersiveOnPark, parked) { p = p.copy(immersiveOnPark = it) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Label("정차 몰입 모드에서 이렇게 들려요")
        FlowStrip()
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfoIcon()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("관중·응원 음원은 직접 제작했거나 이용 권한을 확보한 음원만 사용해요.", fontSize = 15.sp, color = TextMid)
                Text("실제 선수의 생체 정보는 수집·사용하지 않아요.", fontSize = 15.sp, color = TextMid)
            }
            SecondaryButton(if (ui.playing && ui.screen == Screen.SETUP) "■  미리 듣기 중" else "◀  미리 듣기") { act.savePrefs(p); act.preview() }
            Spacer(Modifier.width(14.dp))
            PrimaryButton(if (parked) "설정 저장 · 출발 시 자동 적용" else "저장된 설정으로 듣기", Modifier.width(380.dp)) {
                if (parked) act.savePrefs(p)
                act.start()
            }
        }
    }
}

@Composable
private fun SelectCard(selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Brush.verticalGradient(listOf(Color(0xFF123227), Color(0xFF0F1D1B))) else Brush.verticalGradient(listOf(Card, Card)))
            .border(if (selected) 2.dp else 1.dp, if (selected) Mint else Line, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(22.dp), content = content)
        Box(Modifier.align(Alignment.TopEnd).padding(16.dp)) { CheckCircle(selected) }
    }
}

@Composable
private fun CheckCircle(on: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        if (on) {
            drawCircle(Mint)
            val p = Path().apply { moveTo(size.width * 0.28f, size.height * 0.52f); lineTo(size.width * 0.44f, size.height * 0.68f); lineTo(size.width * 0.74f, size.height * 0.36f) }
            drawPath(p, Bg, style = Stroke(size.width * 0.11f, cap = StrokeCap.Round))
        } else drawCircle(TextLo, style = Stroke(size.width * 0.07f))
    }
}

@Composable
private fun StopSlider(stops: Int, index: Int, enabled: Boolean, set: (Int) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(enabled) { if (enabled) detectTapGestures { set(((it.x / size.width) * (stops - 1) + 0.5f).toInt().coerceIn(0, stops - 1)) } }
            .pointerInput(enabled) { if (enabled) detectHorizontalDragGestures { c, _ -> set(((c.position.x / size.width) * (stops - 1) + 0.5f).toInt().coerceIn(0, stops - 1)) } },
    ) {
        val y = size.height / 2
        val pad = 16f
        val x = { i: Int -> pad + (size.width - pad * 2) * i / (stops - 1) }
        drawLine(Line, Offset(pad, y), Offset(size.width - pad, y), 8f, StrokeCap.Round)
        drawLine(Mint, Offset(pad, y), Offset(x(index), y), 8f, StrokeCap.Round)
        for (i in 0 until stops) drawCircle(if (i <= index) Mint else Line, 9f, Offset(x(i), y))
        drawCircle(Mint.copy(alpha = 0.25f), 34f, Offset(x(index), y))
        drawCircle(Color.White, 22f, Offset(x(index), y))
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { set(!on) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(sub, fontSize = 14.sp, color = TextLo)
        }
        Box(Modifier.size(60.dp, 34.dp).clip(RoundedCornerShape(50)).background(if (on) Mint else Color(0xFF2B3140))) {
            Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(4.dp).size(26.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// ---------------- 02 주행 중: 해설 중심 ----------------
@Composable
private fun DrivingView(game: Game, ui: UiState, act: Actions, idle: State<Long>) {
    val b = game.boardAt(ui.position)
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBig(game.away)
            Spacer(Modifier.width(48.dp))
            ScoreNumber(b.away, 132.sp)
            Text("  :  ", fontSize = 90.sp, color = TextLo, fontWeight = FontWeight.Bold)
            ScoreNumber(b.home, 132.sp)
            Spacer(Modifier.width(48.dp))
            TeamBig(game.home)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${b.inning} · ${b.outs}아웃 · B${b.balls} S${b.strikes}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Bases(b.bases, 40.dp)
        }
        Spacer(Modifier.weight(0.5f))
        val status = when {
            ui.focus == Focus.PAUSED_BY_OTHER -> "통화 중 · 일시정지"
            ui.focus == Focus.DUCKED -> "내비 안내 · 중계 음량 낮춤"
            ui.playing -> "해설 재생 중"
            else -> "일시정지"
        }
        val pct = (ui.position * 100 / game.durationMs).toInt().coerceIn(0, 100)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardHi).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 26.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(110.dp, 26.dp)) {
                val t = idle.value / 1000f
                for (i in 0 until 11) {
                    val h = if (ui.playing && ui.focus != Focus.PAUSED_BY_OTHER) size.height * (0.25f + 0.75f * abs(sin(t * 3f + i * 0.7f))) else size.height * 0.2f
                    drawRoundRect(TextLo, Offset(i * size.width / 11, (size.height - h) / 2), Size(size.width / 11 - 5f, h), CornerRadius(4f))
                }
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(status, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (ui.focus == Focus.PAUSED_BY_OTHER || ui.focus == Focus.DUCKED) Amber else TextHi)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Line)) {
                    Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().clip(RoundedCornerShape(50)).background(Blue))
                }
            }
            Text("  $pct%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMid)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            BigTile(Modifier.weight(1f), onClick = act.toggle) { PlayIcon(ui.playing, 40.dp) }
            BigTile(Modifier.weight(1f), onClick = { act.savePrefs(ui.prefs.copy(effectsOn = !ui.prefs.effectsOn)) }) {
                Text("효과", fontSize = 16.sp, color = TextLo)
                Text(if (ui.prefs.effectsOn) "약하게" else "끔", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Mint)
            }
            Box(
                Modifier.weight(1f).fillMaxHeight().drawBehind {
                    drawRoundRect(Line, style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))), cornerRadius = CornerRadius(26.dp.toPx()))
                },
                contentAlignment = Alignment.Center,
            ) { Text("🔒  몰입 모드 · 정차 후", fontSize = 22.sp, color = TextLo, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun TeamBig(t: Team) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Shield(t, Modifier.size(92.dp, 104.dp), 44f)
        Spacer(Modifier.height(10.dp))
        Text(t.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextMid)
    }
}

@Composable
private fun BigTile(modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(CardHi).border(1.dp, Line, RoundedCornerShape(20.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content,
    )
}

// ---------------- 03 정차 후 몰입 ----------------
@Composable
private fun Immersive(game: Game, engine: CueEngine, ui: UiState, act: Actions, s: SceneInput, idle: State<Long>) {
    val ms = ui.position
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(430.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreCard(game, s.board, ui.prefs)
            BatterCard(engine, s.board, ms, s, Modifier)
            MixerCard(engine, ui, s, Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF090C12))) {
                StadiumView(Modifier.fillMaxSize().shake(s), s, idle)
                HitPop(s, Modifier.align(Alignment.Center).offset(x = (-150).dp, y = 40.dp))
                WalkoffBanner(game, s, Modifier.align(Alignment.TopCenter).padding(top = 34.dp))
                game.captionAt(ms)?.let {
                    Text(
                        "“${it.text}”", fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp, start = 24.dp, end = 24.dp)
                            .widthIn(max = 760.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE60B0E14)).padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            EventQueue(game, ui, act)
            Spacer(Modifier.height(12.dp))
            Controls(ui, act)
        }
    }
}

/** 타격 순간 흔들림(재생 위치 기준). */
private fun Modifier.shake(s: SceneInput): Modifier {
    val dt = s.hitAt?.let { s.ms - it } ?: return this
    if (dt !in 0..450 || !s.effects) return this
    val a = 12f * exp(-dt / 110f)
    return graphicsLayer {
        translationX = a * sin(dt / 11f)
        translationY = a * 0.6f * sin(dt / 7f)
        scaleX = 1f + 0.02f * exp(-dt / 160f)
        scaleY = scaleX
    }
}

@Composable
private fun ScoreCard(game: Game, b: Board, prefs: Prefs) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.final) SmallTag("경기 종료", Color(0xFF0E2E40), Color(0xFF6FC3FF)) else SmallTag("REPLAY", Red, Color.White)
            Text("  ${b.inning} · ${if (b.outs == 2 && b.bases.all { it == 1 }) "2사 만루" else "${b.outs}아웃"}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val level = if (!prefs.effectsOn) "끔" else listOf("약", "중", "강")[when { prefs.intensity < 0.5f -> 0; prefs.intensity < 0.85f -> 1; else -> 2 }]
            SmallTag("몰입 모드 · $level", MintBg, Mint)
        }
        Spacer(Modifier.height(14.dp))
        listOf(game.away to b.away, game.home to b.home).forEach { (t, sc) ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Shield(t, Modifier.size(32.dp, 36.dp), 15f)
                Text("  ${t.name}", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                if (t.id == game.home.id && !b.final) Text("  ● 공격", fontSize = 13.sp, color = Mint, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ScoreNumber(sc, 38.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dots("B", b.balls, 3, Color(0xFF2ED6A0)); Spacer(Modifier.width(22.dp))
            Dots("S", b.strikes, 2, Yellow); Spacer(Modifier.width(22.dp))
            Dots("O", b.outs, 2, Red)
        }
    }
}

@Composable
private fun BatterCard(engine: CueEngine, b: Board, ms: Long, s: SceneInput, modifier: Modifier) {
    val beatDt = s.lastBeat?.let { ms - it }
    val heartScale = if (beatDt != null && s.effects) 1f + 0.22f * exp(-beatDt / 110f) else 1f
    val tension = engine.tensionAt(ms)
    val breath = if (tension != null && tension.breath > 0 && s.effects) { if (((ms - tension.t) % 3_400) < 1_400) "들숨" else "날숨" } else "—"
    val afterHit = s.hitAt != null
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) { Text("4", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
            Text("  ${b.batter}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("   블루윙스 · 타율 .318", fontSize = 15.sp, color = TextLo)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (afterHit) CrowdIcon(ms) else Canvas(Modifier.size(64.dp).scale(heartScale)) {
                val w = size.width
                val p = Path().apply {
                    moveTo(w * 0.5f, w * 0.92f)
                    cubicTo(w * 0.04f, w * 0.6f, w * 0.0f, w * 0.14f, w * 0.27f, w * 0.12f)
                    cubicTo(w * 0.4f, w * 0.1f, w * 0.48f, w * 0.2f, w * 0.5f, w * 0.29f)
                    cubicTo(w * 0.52f, w * 0.2f, w * 0.6f, w * 0.1f, w * 0.73f, w * 0.12f)
                    cubicTo(w * 1.0f, w * 0.14f, w * 0.96f, w * 0.6f, w * 0.5f, w * 0.92f)
                    close()
                }
                drawPath(p, Brush.verticalGradient(listOf(Color(0xFFFF6B78), Color(0xFFE5323F))))
                drawOval(Color.White.copy(alpha = 0.25f), Offset(w * 0.2f, w * 0.2f), Size(w * 0.2f, w * 0.12f))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(if (afterHit) "관중 함성" else "연출 심박", fontSize = 15.sp, color = TextMid)
                Row(verticalAlignment = Alignment.Bottom) {
                    val value = if (afterHit) "${(92 + 13 * s.scores.size + if (s.cheer != null) 4 else 0).coerceAtMost(122)}" else if (s.bpm > 0) "${s.bpm}" else "—"
                    Text(value, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text(if (afterHit) "  dB" else "  bpm", fontSize = 20.sp, color = TextMid, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (afterHit) "응원가" else "숨소리", fontSize = 15.sp, color = TextMid)
                Text(if (afterHit) (if (s.cheer != null) "재생" else "대기") else breath, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(130.dp).padding(vertical = 12.dp)) {
            for (i in 0..24) drawLine(Color.White.copy(alpha = 0.04f), Offset(size.width * i / 24, 0f), Offset(size.width * i / 24, size.height), 1f)
            val mid = size.height * 0.55f
            if (afterHit) {
                val n = 26
                val bw = size.width / n
                val level = (0.45f + 0.25f * s.scores.size + if (s.cheer != null) 0.2f else 0f).coerceAtMost(1f)
                for (i in 0 until n) {
                    val wave = 0.5f + 0.5f * sin(ms / 85f + i * 0.9f) * sin(ms / 230f + i * 0.37f)
                    val h = size.height * 0.85f * level * wave + 6f
                    drawRoundRect(Brush.verticalGradient(listOf(Orange, Orange.copy(alpha = 0.3f)), startY = size.height - h, endY = size.height), Offset(i * bw + 3f, size.height - h), Size(bw - 6f, h), CornerRadius(5f))
                }
            } else {
                val window = 3_600L
                val beats = engine.beats(ms - window, ms)
                val path = Path().apply { moveTo(0f, mid) }
                val xOf = { t: Long -> size.width * (1f - (ms - t).toFloat() / window) }
                beats.forEach { t ->
                    val x = xOf(t)
                    path.lineTo(x - 22f, mid); path.lineTo(x - 15f, mid - size.height * 0.06f); path.lineTo(x - 9f, mid)
                    path.lineTo(x - 4f, mid + size.height * 0.08f); path.lineTo(x, mid - size.height * 0.42f); path.lineTo(x + 6f, mid + size.height * 0.2f)
                    path.lineTo(x + 12f, mid); path.lineTo(x + 26f, mid - size.height * 0.06f); path.lineTo(x + 34f, mid)
                }
                path.lineTo(size.width, mid)
                drawPath(path, Red.copy(alpha = 0.22f), style = Stroke(10f, cap = StrokeCap.Round))
                drawPath(path, Color(0xFFFF4A58), style = Stroke(3.5f, cap = StrokeCap.Round))
            }
        }
        Text("사전 제작 연출 음원 · 실제 선수 생체 정보 미사용", fontSize = 13.sp, color = TextLo)
    }
}

/** 지금 울리는 연출 레이어(중계 시각 기준 계산). 동기화 엔진이 실제로 내는 소리를 그대로 보여 준다. */
@Composable
private fun MixerCard(engine: CueEngine, ui: UiState, s: SceneInput, modifier: Modifier) {
    val ms = s.ms
    val mix = engine.mix(ms, Mode.PARKED, ui.prefs)
    fun decay(t: Long?, len: Float) = t?.let { (ms - it).takeIf { d -> d >= 0 }?.let { d -> exp(-d / len).toFloat() } } ?: 0f
    val on = ui.prefs.effectsOn
    val k = if (on) ui.prefs.intensity else 0f
    val rows = listOf(
        Triple("숨소리", Purple, if (on) mix.breathGain / 0.55f else 0f),
        Triple("심박", Red, if (on) decay(s.lastBeat, 160f) * k else 0f),
        Triple("타구음 · 비행음", Blue, if (on) maxOf(decay(s.hitAt, 260f), s.flight?.let { 0.6f * sin(Math.PI.toFloat() * it) } ?: 0f) else 0f),
        Triple("함성", Orange, if (on) (s.scores.maxOfOrNull { decay(it.t, 1600f) } ?: 0f) else 0f),
        Triple("응원가", Mint, if (on) mix.chantGain / 0.8f else 0f),
    )
    PanelCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("연출 레이어", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (on) "중계 위치에 맞춰 자동 믹싱" else "효과 꺼짐 · 기본 중계", fontSize = 13.sp, color = TextLo)
        }
        Spacer(Modifier.weight(1f))
        rows.forEach { (name, color, level) ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 15.sp, color = if (level > 0.05f) TextHi else TextLo, modifier = Modifier.width(120.dp))
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    drawRoundRect(Line, cornerRadius = CornerRadius(size.height / 2))
                    val w = size.width * level.coerceIn(0f, 1f)
                    if (w > 1f) drawRoundRect(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color)), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CrowdIcon(ms: Long) {
    Canvas(Modifier.size(64.dp)) {
        val c = Offset(size.width * 0.32f, size.height / 2)
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFA463), Orange)), size.width * 0.2f, c)
        for (k in 1..3) {
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(ms / 120f - k))
            drawArc(Orange.copy(alpha = a), -50f, 100f, false, topLeft = Offset(c.x - k * size.width * 0.16f, c.y - k * size.width * 0.16f), size = Size(k * size.width * 0.32f, k * size.width * 0.32f), style = Stroke(size.width * 0.06f, cap = StrokeCap.Round))
        }
    }
}

/** 설정 화면 하단: 정차 몰입 모드의 연출 흐름 미리보기. */
@Composable
private fun FlowStrip() {
    val steps = listOf(
        Triple("숨소리 · 심박", "타석의 긴장이 커질수록 빨라져요", Purple),
        Triple("딱! · 공 비행음", "타격 순간 공이 왼쪽에서 오른쪽으로", Blue),
        Triple("득점 함성 · 응원가", "내 팀이면 함성, 상대면 탄성", Orange),
    )
    Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, (t, sub, c) ->
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp, 54.dp).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("0${i + 1}  $t", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(sub, fontSize = 14.sp, color = TextLo)
                }
            }
            if (i < steps.lastIndex) Text("→", fontSize = 22.sp, color = TextLo)
        }
    }
}

@Composable
private fun HitPop(s: SceneInput, modifier: Modifier) {
    val dt = s.hitAt?.let { s.ms - it } ?: return
    if (dt !in 0..1000 || !s.effects) return
    val scale = 1f + 0.8f * exp(-dt / 70f) - 0.2f * exp(-dt / 160f) * sin(dt / 45f)
    val alpha = if (dt < 750) 1f else 1f - (dt - 750) / 250f
    Text(
        "딱!", color = Color.White.copy(alpha = alpha), fontSize = 96.sp, fontWeight = FontWeight.ExtraBold,
        style = LocalTextStyle.current.copy(shadow = Shadow(Color(0xFF6FA2FF), Offset.Zero, 36f)),
        modifier = modifier.scale(scale).graphicsLayer { rotationZ = -8f },
    )
}

@Composable
private fun WalkoffBanner(game: Game, s: SceneInput, modifier: Modifier) {
    val w = s.scores.lastOrNull { it.walkoff } ?: return
    val dt = s.ms - w.t
    if (dt < 0 || !s.effects) return
    val k = (dt / 450f).coerceIn(0f, 1f)
    val ease = 1 - (1 - k) * (1 - k) * (1 - k)
    val team = game.team(w.team)
    Column(
        modifier.graphicsLayer { alpha = k; translationY = (1 - ease) * -50f; scaleX = 0.92f + 0.08f * ease; scaleY = scaleX }
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(team.color).lighten(0.1f), Color(team.color).darken(0.2f))))
            .border(2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(horizontal = 40.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${game.home.name} 5 : 4 ${game.away.name} · 경기 종료", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
        Text("끝내기 역전 적시타", fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

/** 이벤트 큐: 타석(보라) → 타격(파랑) → 득점·응원(주황) 세 구간과 재생 위치. */
@Composable
private fun EventQueue(game: Game, ui: UiState, act: Actions) {
    val hit = game.cues.first { it.type == "HIT" }.t
    val score = game.cues.first { it.type == "SCORE" }.t
    val dur = game.durationMs
    val bounds = listOf(0L to hit, hit to score, score to dur)
    val weights = listOf(0.4f, 0.19f, 0.41f)
    val colors = listOf(Purple, Blue, Orange)
    val labels = listOf("타석 · 숨소리/심박", "타격 · 공 비행음", "득점 · 함성/응원가")
    val ms = ui.position
    val seg = bounds.indexOfLast { ms >= it.first }.coerceAtLeast(0)
    val gap = 14f
    fun toX(width: Float, t: Long): Float {
        val usable = width - gap * 2
        var x = 0f
        bounds.forEachIndexed { i, (a, b) ->
            val w = usable * weights[i]
            if (t < b || i == bounds.lastIndex) return x + w * ((t - a).toFloat() / (b - a)).coerceIn(0f, 1f)
            x += w + gap
        }
        return x
    }
    fun fromX(width: Float, x: Float): Long {
        val usable = width - gap * 2
        var start = 0f
        bounds.forEachIndexed { i, (a, b) ->
            val w = usable * weights[i]
            if (x <= start + w || i == bounds.lastIndex) return a + ((b - a) * ((x - start) / w).coerceIn(0f, 1f)).toLong()
            start += w + gap
        }
        return dur
    }
    PanelCard(padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append("이벤트 큐 · 미디어 기준 시각 ")
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = TextHi)) { append("%02d:%04.1f".format(ms / 60_000, (ms % 60_000) / 1000f)) }
            }, fontSize = 16.sp, color = TextMid)
            Spacer(Modifier.weight(1f))
            Text(labels[seg], fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors[seg].lighten(0.35f))
        }
        Spacer(Modifier.height(14.dp))
        Canvas(
            Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(Unit) { detectTapGestures { act.seek(fromX(size.width.toFloat(), it.x)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> act.seek(fromX(size.width.toFloat(), c.position.x)) } },
        ) {
            val h = 14.dp.toPx()
            val y = (size.height - h) / 2
            var x = 0f
            val usable = size.width - gap * 2
            bounds.forEachIndexed { i, _ ->
                val w = usable * weights[i]
                val alpha = when { i < seg -> 0.55f; i == seg -> 1f; else -> 0.28f }
                drawRoundRect(Brush.horizontalGradient(listOf(colors[i].copy(alpha = alpha), colors[i].lighten(0.15f).copy(alpha = alpha)), startX = x, endX = x + w), Offset(x, y), Size(w, h), CornerRadius(h / 2))
                x += w + gap
            }
            val px = toX(size.width, ms)
            drawLine(Color.White.copy(alpha = 0.3f), Offset(px, 0f), Offset(px, size.height), 10f, StrokeCap.Round)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 4f, StrokeCap.Round)
        }
    }
}

@Composable
private fun Controls(ui: UiState, act: Actions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("−10초") { act.seek((ui.position - 10_000).coerceAtLeast(0)) }
        Box(Modifier.size(56.dp).clip(CircleShape).background(Blue).clickable { act.toggle() }, contentAlignment = Alignment.Center) { PlayIcon(ui.playing, 22.dp) }
        GhostButton("+10초") { act.seek(ui.position + 10_000) }
        Spacer(Modifier.width(4.dp))
        GhostButton(if (ui.prefs.effectsOn) "효과 끄기 · 기본 중계" else "연출 다시 켜기", highlight = !ui.prefs.effectsOn) {
            act.savePrefs(ui.prefs.copy(effectsOn = !ui.prefs.effectsOn))
        }
        Spacer(Modifier.weight(1f))
        Text("시연", fontSize = 14.sp, color = TextLo)
        GhostButton("내비 안내", enabled = ui.simulation == null) { act.simulateNav() }
        GhostButton("전화 수신", enabled = ui.simulation == null) { act.simulateCall() }
    }
}

// ---------------- 04 종료 후 평가 ----------------
@Composable
private fun Feedback(ui: UiState, act: Actions) {
    val scores = remember { mutableStateMapOf<String, Int>() }
    val items = listOf("immersion" to "몰입감", "clarity" to "해설 명료도", "volume" to "음량 변화가 편했나요")
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(buildAnnotatedString {
            append("언제든 ")
            withStyle(SpanStyle(color = Color(0xFF52C8F0))) { append("기본 중계") }
            append("로, 듣고 난 뒤엔 짧게 평가")
        }, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("정차 상태에서 몰입감 · 해설 명료도 · 음량 변화 불편을 알려 주세요", fontSize = 17.sp, color = TextLo)
        Spacer(Modifier.height(30.dp))
        when {
            ui.mode != Mode.PARKED -> Text("정차 후 평가할 수 있어요", color = Amber, fontSize = 22.sp)
            ui.feedbackSaved -> Text("평가가 저장됐어요. 다음 연출 강도 추천에 반영돼요.", color = Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            else -> Column(Modifier.widthIn(max = 760.dp)) {
                items.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        (1..5).forEach { n ->
                            Text("★", fontSize = 44.sp, color = if ((scores[k] ?: 0) >= n) Yellow else Line, modifier = Modifier.padding(horizontal = 4.dp).clickable { scores[k] = n })
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                PrimaryButton("평가 보내기", Modifier.fillMaxWidth(), enabled = scores.size == items.size) { act.saveFeedback(scores.toMap()) }
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("↺  다시 듣기") { act.replay() }
            SecondaryButton("설정으로") { act.openSetup() }
        }
    }
}

// ---------------- 작은 구성요소 ----------------
@Composable
private fun PanelCard(modifier: Modifier = Modifier, padding: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(padding),
        content = content,
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 16.sp, color = TextMid, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(bottom = 10.dp))
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.13f)).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SmallTag(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (enabled) Color.White else TextLo,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(if (enabled) Blue else CardHi).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 28.dp, vertical = 20.dp),
    )
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(CardHi).border(1.dp, Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 20.dp),
    )
}

@Composable
private fun GhostButton(text: String, highlight: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (!enabled) TextLo else if (highlight) Bg else TextHi,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (highlight) Mint else CardHi).border(1.dp, if (highlight) Mint else Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun InfoIcon() {
    Canvas(Modifier.size(24.dp)) {
        drawCircle(TextLo, style = Stroke(size.width * 0.08f))
        drawLine(TextLo, Offset(size.width / 2, size.height * 0.45f), Offset(size.width / 2, size.height * 0.72f), size.width * 0.09f, StrokeCap.Round)
        drawCircle(TextLo, size.width * 0.06f, Offset(size.width / 2, size.height * 0.3f))
    }
}

@Composable
private fun ScoreNumber(n: Int, size: TextUnit) {
    AnimatedContent(
        targetState = n,
        transitionSpec = { (slideInVertically(tween(380)) { it } + fadeIn(tween(380))) togetherWith (slideOutVertically(tween(380)) { -it } + fadeOut(tween(300))) },
        label = "score",
    ) { v -> Text("$v", fontSize = size, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun PlayIcon(playing: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (playing) {
            drawRoundRect(Color.White, Offset(w * 0.14f, 0f), Size(w * 0.26f, h), CornerRadius(w * 0.06f))
            drawRoundRect(Color.White, Offset(w * 0.6f, 0f), Size(w * 0.26f, h), CornerRadius(w * 0.06f))
        } else drawPath(Path().apply { moveTo(w * 0.2f, 0f); lineTo(w, h / 2); lineTo(w * 0.2f, h); close() }, Color.White)
    }
}

@Composable
private fun Bases(bases: List<Int>, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width * 0.26f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        listOf(Offset(c.x + s, c.y), Offset(c.x, c.y - s), Offset(c.x - s, c.y)).forEachIndexed { i, p ->
            rotate(45f, p) { drawRect(if (bases.getOrElse(i) { 0 } == 1) Yellow else Line, Offset(p.x - s * 0.5f, p.y - s * 0.5f), Size(s, s)) }
        }
    }
}

@Composable
private fun Dots(label: String, n: Int, max: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = TextMid, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(8.dp))
        repeat(max) { i -> Box(Modifier.padding(3.dp).size(15.dp).clip(CircleShape).background(if (i < n) color else Line)) }
    }
}
