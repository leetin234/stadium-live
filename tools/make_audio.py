"""STADIUM LIVE 시연용 음원·이벤트 생성기.

모든 소리는 이 스크립트가 직접 합성한다(외부 음원 없음). 해설은 macOS `say`(유나)로
읽은 뒤 중계 톤으로 다듬는다. 이벤트 시각은 해설 문장의 실제 길이에서 계산하므로
중계 음원과 연출 큐가 항상 일치한다.

실행: tools/.venv/bin/python -I tools/make_audio.py
결과: app/src/main/assets/game1/{broadcast.m4a, game.json}, app/src/main/assets/fx/*.wav
"""
import json
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

SR = 44100
ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "tools" / ".work"
GAME = ROOT / "app" / "src" / "main" / "assets" / "game1"
FX = ROOT / "app" / "src" / "main" / "assets" / "fx"
rng = np.random.default_rng(7)


# ---------- 기본 도구 ----------
def t(sec):
    return np.arange(int(sec * SR)) / SR


def noise(sec):
    return rng.standard_normal(int(sec * SR))


def band(x, lo, hi, soft=0.15):
    """FFT 마스크 대역 통과(가장자리 부드럽게)."""
    n = len(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    X = np.fft.rfft(x)
    m = np.ones_like(f)
    if lo > 0:
        m *= 1 / (1 + np.exp(np.clip(-(f - lo) / max(lo * soft, 1), -60, 60)))
    if hi < SR / 2:
        m *= 1 / (1 + np.exp(np.clip((f - hi) / max(hi * soft, 1), -60, 60)))
    return np.fft.irfft(X * m, n)


def pink(sec):
    x = noise(sec)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X = np.fft.rfft(x)
    X[1:] /= np.sqrt(f[1:])
    X[0] = 0
    return np.fft.irfft(X, len(x))


def env_adsr(n, a, d, s, r, sustain=0.7):
    a, d, r = int(a * SR), int(d * SR), int(r * SR)
    hold = max(n - a - d - r, 0)
    e = np.concatenate([
        np.linspace(0, 1, a, endpoint=False),
        np.linspace(1, sustain, d, endpoint=False),
        np.full(hold, sustain),
        np.linspace(sustain, 0, r),
    ])
    return np.pad(e, (0, max(n - len(e), 0)))[:n]


def norm(x, peak=0.9):
    m = np.max(np.abs(x)) or 1
    return x / m * peak


def fade(x, fi=0.01, fo=0.02):
    x = x.copy()
    a, b = int(fi * SR), int(fo * SR)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def write_wav(path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def to_fx(x, name):
    """짧은 효과음은 SoundPool이 바로 읽도록 16비트 WAV로 둔다."""
    write_wav(FX / f"{name}.wav", x)


def to_aac(x, out):
    tmp = WORK / (out.stem + ".wav")
    write_wav(tmp, x)
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(tmp), "-c:a", "aac", "-b:a", "128k", str(out)], check=True)


def read_audio(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def time_varying_band(x, centers, width=0.6, hop=0.025):
    """짧은 창마다 중심 주파수를 바꿔 겹쳐 더한다(공 비행음의 도플러 느낌)."""
    win = int(hop * 2 * SR)
    step = win // 2
    out = np.zeros(len(x) + win)
    w = np.hanning(win)
    for i, start in enumerate(range(0, len(x), step)):
        seg = x[start:start + win]
        if len(seg) < win:
            seg = np.pad(seg, (0, win - len(seg)))
        c = centers(start / SR)
        out[start:start + win] += band(seg, c * (1 - width / 2), c * (1 + width / 2)) * w
    return out[:len(x)]


# ---------- 효과음 ----------
def fx_heartbeat():
    """심장 한 박(쿵-쿵). 앱이 bpm에 맞춰 반복 재생한다."""
    out = np.zeros(int(0.55 * SR))
    for off, amp, f0 in ((0.0, 1.0, 62), (0.17, 0.72, 52)):
        tt = t(0.22)
        f = f0 * (1 + 0.6 * np.exp(-tt * 40))
        ph = 2 * np.pi * np.cumsum(f) / SR
        s = np.sin(ph) * np.exp(-tt * 18) * (1 - np.exp(-tt * 300))
        s += band(noise(0.22), 30, 160) * 0.25 * np.exp(-tt * 30)
        i = int(off * SR)
        out[i:i + len(s)] += amp * s
    return fade(norm(band(out, 20, 300), 0.95), 0.002, 0.05)


def fx_breath():
    """들숨·날숨 한 주기(3.2초), 반복 재생."""
    inh = band(noise(1.3), 500, 2600) * env_adsr(int(1.3 * SR), 0.9, 0.1, 0, 0.3, 0.9)
    gap = np.zeros(int(0.2 * SR))
    exh = band(noise(1.5), 280, 1700) * env_adsr(int(1.5 * SR), 0.12, 0.4, 0, 1.0, 0.6)
    x = np.concatenate([inh * 0.7, gap, exh, np.zeros(int(0.2 * SR))])
    return fade(norm(x, 0.8), 0.05, 0.1)


def fx_crack(light=False):
    """배트 타격음 '딱'. light=True는 파울용."""
    tt = t(0.6)
    x = band(noise(0.6), 2500, 12000) * np.exp(-tt * 260) * 1.4
    for f, d, a in ((1180, 38, 1.0), (2350, 55, 0.6), (3420, 70, 0.4), (640, 22, 0.5)):
        x += a * np.sin(2 * np.pi * f * tt) * np.exp(-tt * d)
    x += 0.5 * np.sin(2 * np.pi * 190 * tt) * np.exp(-tt * 14)
    if light:
        x = band(x, 600, 9000) * np.exp(-tt * 8)
    return fade(norm(x, 0.7 if light else 0.98), 0.0005, 0.1)


def fx_glove():
    """포수 미트 소리."""
    tt = t(0.35)
    x = band(noise(0.35), 80, 900) * np.exp(-tt * 45)
    x += 0.8 * np.sin(2 * np.pi * 115 * tt) * np.exp(-tt * 28)
    return fade(norm(x, 0.85), 0.0005, 0.05)


def fx_whoosh(sec=2.6):
    """타구 비행음. 중심 주파수가 오르내리고 앱이 좌→우로 이동시킨다."""
    x = noise(sec)
    y = time_varying_band(x, lambda s: 900 + 1900 * np.sin(np.pi * min(s / sec, 1)) ** 1.5, 0.7)
    tt = t(sec)
    e = np.sin(np.pi * np.clip(tt / sec, 0, 1)) ** 1.2
    whistle = 0.18 * np.sin(2 * np.pi * np.cumsum(2600 - 900 * tt / sec) / SR) * e
    return fade(norm(y * e + whistle, 0.85), 0.02, 0.2)


def crowd_bed(sec, lo=250, hi=3200):
    """수천 명의 웅성거림: 분홍 잡음 + 무작위 진폭 변조 + 짧은 목소리 조각."""
    x = band(pink(sec), lo, hi)
    tt = t(sec)
    am = 1 + 0.15 * np.sin(2 * np.pi * 0.31 * tt) + 0.1 * np.sin(2 * np.pi * 0.83 * tt + 1)
    x = norm(x) * am
    for _ in range(int(sec * 25)):
        d = rng.uniform(0.08, 0.35)
        f0 = rng.uniform(140, 320)
        i = int(rng.uniform(0, max(sec - d, 0.01)) * SR)
        v = t(d)
        voice = sum(np.sin(2 * np.pi * f0 * k * v) / k for k in range(1, 7))
        voice = band(voice * np.hanning(len(v)), 400, 2500)
        x[i:i + len(v)] += voice * rng.uniform(0.03, 0.09)
    return x


def fx_roar(sec=5.5):
    """득점 함성: 순간적으로 커졌다가 길게 유지된다. 휘파람 포함."""
    x = norm(crowd_bed(sec, 300, 4500))
    x += 0.6 * norm(band(noise(sec), 500, 3500))
    for _ in range(9):
        d = rng.uniform(0.3, 0.8)
        i = int(rng.uniform(0.3, sec - d) * SR)
        v = t(d)
        f = rng.uniform(2200, 3200) + 500 * np.sin(np.pi * v / d)
        x[i:i + len(v)] += 0.12 * np.sin(2 * np.pi * np.cumsum(f) / SR) * np.hanning(len(v))
    e = env_adsr(len(x), 0.35, 0.8, 0, 2.0, 0.75)
    return fade(norm(x * e, 0.95), 0.01, 0.4)


def fx_groan(sec=2.6):
    """상대 팀 득점 때의 아쉬운 탄성 '아~'."""
    tt = t(sec)
    f0 = 230 - 70 * tt / sec
    voice = np.zeros_like(tt)
    for k in range(1, 12):
        voice += np.sin(2 * np.pi * k * np.cumsum(f0 * (1 + rng.uniform(-0.03, 0.03))) / SR) / k
    voice = band(voice, 500, 1400)
    x = norm(voice) * 0.6 + norm(crowd_bed(sec, 250, 2000)) * 0.5
    e = env_adsr(len(x), 0.2, 0.5, 0, 1.6, 0.7)
    return fade(norm(x * e, 0.8), 0.01, 0.3)


def clap(n_people=45):
    out = np.zeros(int(0.12 * SR))
    for _ in range(n_people):
        d = int(rng.uniform(0, 0.025) * SR)
        c = band(noise(0.06), 900, 5500) * np.exp(-t(0.06) * 90)
        out[d:d + len(c)] += c * rng.uniform(0.5, 1)
    return norm(out)


def drum():
    tt = t(0.4)
    f = 70 * (1 + 1.5 * np.exp(-tt * 30))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 9)


def fx_chant(pattern, bpm, vowel_hz, bars=2):
    """응원 박수·북 리듬 + 관중 합창 모음. 반복 재생용 루프."""
    beat = 60 / bpm
    sec = beat * 4 * bars
    x = np.zeros(int(sec * SR) + SR)
    c = clap()
    d = drum()
    for bar in range(bars):
        for pos in pattern:
            i = int((bar * 4 + pos) * beat * SR)
            x[i:i + len(c)] += c * 0.8
        for b in range(4):
            i = int((bar * 4 + b) * beat * SR)
            x[i:i + len(d)] += d * (0.9 if b % 2 == 0 else 0.6)
    tt = t(sec + 1)
    choir = np.zeros_like(tt)
    for _ in range(14):
        f = vowel_hz * rng.uniform(0.97, 1.03)
        for k in range(1, 8):
            choir += np.sin(2 * np.pi * f * k * tt + rng.uniform(0, 6)) / k
    choir = band(choir, 350, 1600) * (0.75 + 0.25 * np.sin(2 * np.pi * tt / (beat * 2)))
    x += norm(choir) * 0.35 + norm(crowd_bed(sec + 1)) * 0.25
    x = x[:int(sec * SR)]
    # 루프 경계에서 튀지 않게 끝 10ms만 교차.
    k = int(0.01 * SR)
    x[:k] = x[:k] * np.linspace(0, 1, k) + x[-k:] * np.linspace(1, 0, k)
    return norm(x[:-k], 0.9)


# ---------- 경기 대본 ----------
HOME = {"id": "W", "name": "블루윙스", "color": "#3A7BFF"}
AWAY = {"id": "F", "name": "레드폭스", "color": "#FF4D4D"}

# ("say", 문장, 뒤 공백초) / ("cue", {...}) / ("state", {...}) / ("gap", 초)
SCRIPT = [
    ("state", {"inning": "9회말", "outs": 2, "bases": [1, 1, 1], "balls": 0, "strikes": 0,
               "home": 3, "away": 4, "batter": "한도윤", "batterLine": "4타수 2안타", "bpm": 0}),
    ("gap", 0.6),
    ("say", "자, 9회말 블루윙스의 마지막 공격입니다. 투아웃 주자 만루, 점수는 레드폭스가 4대 3으로 앞서 있습니다.", 0.5),
    ("say", "한 방이면 경기가 끝나는 상황이에요. 타석에는 오늘 안타 두 개를 친 한도윤 선수입니다.", 0.3),
    ("cue", {"type": "AT_BAT", "label": "타석 시작"}),
    ("cue", {"type": "TENSION", "bpm": 96, "breath": 0.5, "label": "긴장 시작"}),
    ("state", {"bpm": 96}),
    ("gap", 1.6),
    ("say", "초구 던집니다.", 0.35),
    ("cue", {"type": "PITCH", "label": "초구"}),
    ("gap", 0.55),
    ("cue", {"type": "BALL", "label": "볼"}),
    ("state", {"balls": 1}),
    ("say", "볼. 바깥쪽으로 빠지는 공, 잘 골라냈습니다.", 0.9),
    ("cue", {"type": "TENSION", "bpm": 108, "breath": 0.6}),
    ("state", {"bpm": 108}),
    ("say", "2구, 몸쪽 빠른 공.", 0.2),
    ("cue", {"type": "PITCH", "label": "2구"}),
    ("gap", 0.5),
    ("cue", {"type": "STRIKE", "label": "스트라이크"}),
    ("state", {"strikes": 1}),
    ("say", "스트라이크!", 0.8),
    ("cue", {"type": "TENSION", "bpm": 118, "breath": 0.75, "label": "긴장 고조"}),
    ("state", {"bpm": 118}),
    ("say", "관중석이 조용해집니다. 타자도 숨을 고르고 있어요.", 1.4),
    ("say", "3구 던졌습니다.", 0.15),
    ("cue", {"type": "PITCH", "label": "3구"}),
    ("gap", 0.45),
    ("cue", {"type": "FOUL", "label": "파울"}),
    ("state", {"strikes": 2}),
    ("say", "파울! 뒤로 넘어갑니다.", 0.7),
    ("cue", {"type": "TENSION", "bpm": 132, "breath": 1.0, "label": "투 스트라이크"}),
    ("state", {"bpm": 132}),
    ("say", "이제 투 스트라이크. 한 개만 더 잡으면 레드폭스가 이깁니다.", 2.0),
    ("say", "투수, 세트 포지션. 던졌습니다!", 0.1),
    ("cue", {"type": "PITCH", "label": "4구"}),
    ("gap", 0.45),
    ("cue", {"type": "HIT", "label": "타격", "flightMs": 2600, "direction": "RIGHT_CENTER"}),
    ("cue", {"type": "TENSION", "bpm": 0, "breath": 0}),
    ("state", {"bpm": 0, "hit": True}),
    # 해설이 타격음과 거의 동시에 터져야 화면의 공 출발과 맞게 들린다.
    ("gap", 0.05),
    ("say", "쳤습니다! 우중간! 우중간을 가릅니다!", 0.2),
    ("cue", {"type": "SCORE", "team": "W", "label": "3루 주자 홈인"}),
    ("state", {"home": 4, "bases": [1, 1, 0]}),
    ("say", "3루 주자 홈인, 동점!", 0.1),
    ("cue", {"type": "SCORE", "team": "W", "walkoff": True, "label": "끝내기"}),
    ("state", {"home": 5, "bases": [0, 0, 0], "final": True}),
    ("say", "2루 주자도 들어옵니다! 끝내기! 블루윙스가 5대 4로 경기를 뒤집습니다!", 0.4),
    ("cue", {"type": "CHEER", "team": "W", "durationMs": 12000, "label": "응원가"}),
    ("gap", 1.2),
    ("say", "믿을 수 없는 역전입니다. 한도윤 선수가 오늘 경기의 주인공이네요!", 2.5),
    ("say", "지금까지 블루윙스와 레드폭스의 경기였습니다.", 2.0),
]


def tts(text, idx):
    aiff = WORK / f"line{idx:02d}.aiff"
    subprocess.run(["say", "-v", "Yuna", "-r", "205", "-o", str(aiff), text], check=True)
    x = read_audio(aiff)
    nz = np.nonzero(np.abs(x) > 0.004)[0]
    return x[nz[0]:nz[-1] + 1] if len(nz) else x


def build_game():
    cursor = 0.0
    lines, cues, states, captions = [], [], [], []
    for i, item in enumerate(SCRIPT):
        kind = item[0]
        if kind == "gap":
            cursor += item[1]
        elif kind == "say":
            x = tts(item[1], i)
            lines.append((cursor, x))
            captions.append({"t": int(cursor * 1000), "end": int((cursor + len(x) / SR) * 1000), "text": item[1]})
            cursor += len(x) / SR + item[2]
        elif kind == "cue":
            cues.append({"t": int(cursor * 1000), **item[1]})
        elif kind == "state":
            states.append({"t": int(cursor * 1000), **item[1]})
    total = cursor + 1.0

    # 중계 음원: 해설 + 경기장 기본 웅성거림 + 실제 중계에서도 들리는 정도의 자연 반응.
    n = int(total * SR)
    mix = np.zeros(n)
    voice = np.zeros(n)
    for start, x in lines:
        i = int(start * SR)
        voice[i:i + len(x)] += x[: n - i]
    voice = band(voice, 120, 7500)
    voice = norm(voice, 0.75)
    bed = norm(crowd_bed(total)) * 0.07
    hit_t = next(c["t"] for c in cues if c["type"] == "HIT") / 1000
    tt = t(total)
    swell = 1 + 1.6 * np.clip((tt - hit_t) / 1.5, 0, 1) * np.exp(-np.clip(tt - hit_t - 4, 0, None) / 6)
    mix = voice + bed * swell[: len(bed)]
    mix = fade(norm(mix, 0.9), 0.3, 1.5)
    to_aac(mix, GAME / "broadcast.m4a")

    game = {
        "id": "game1",
        "title": "블루윙스 vs 레드폭스 · 9회말 2사 만루",
        "note": "자체 제작한 가상 경기 녹화 중계(실제 구단·선수 아님)",
        "durationMs": int(total * 1000),
        "home": HOME,
        "away": AWAY,
        "cues": cues,
        "states": states,
        "captions": captions,
    }
    GAME.mkdir(parents=True, exist_ok=True)
    (GAME / "game.json").write_text(json.dumps(game, ensure_ascii=False, indent=1))
    print(f"broadcast {total:.1f}s, {len(cues)} cues, {len(captions)} lines")


def build_fx():
    to_fx(fx_heartbeat(), "heartbeat")
    to_fx(fx_breath(), "breath")
    to_fx(fx_crack(), "crack")
    to_fx(fx_crack(light=True), "foul")
    to_fx(fx_glove(), "glove")
    to_fx(fx_whoosh(), "whoosh")
    to_fx(fx_roar(), "roar")
    to_fx(fx_groan(), "groan")
    # 팀별 응원 리듬: 블루윙스는 '짝짝 짝짝짝', 레드폭스는 엇박 위주.
    to_fx(fx_chant([0, 0.5, 2, 2.5, 3], 128, 196), "chant_W")
    to_fx(fx_chant([0.5, 1.5, 2, 3, 3.5], 118, 165), "chant_F")
    # 오디오 포커스 시연용: 내비 안내 음성과 전화 벨소리(앱 효과가 아니라 '다른 앱' 역할).
    nav = tts("300미터 앞에서 오른쪽 방향입니다.", 99)
    chime = np.concatenate([np.sin(2 * np.pi * f * t(0.18)) * np.exp(-t(0.18) * 12) for f in (880, 1175)])
    to_fx(norm(np.concatenate([chime * 0.5, np.zeros(int(0.15 * SR)), band(nav, 150, 7000)]), 0.9), "sim_nav")
    tt = t(0.4)
    ring = (np.sin(2 * np.pi * 440 * tt) + np.sin(2 * np.pi * 480 * tt)) * (np.sin(2 * np.pi * 20 * tt) > 0)
    burst = np.concatenate([ring, np.zeros(int(0.2 * SR)), ring, np.zeros(int(1.2 * SR))])
    to_fx(norm(np.tile(burst, 3), 0.6), "sim_ring")
    print("fx done")


if __name__ == "__main__":
    WORK.mkdir(parents=True, exist_ok=True)
    build_fx()
    build_game()
    sys.exit(0)
