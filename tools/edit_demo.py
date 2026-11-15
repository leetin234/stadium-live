"""녹화본(demo/raw.webm)을 시연 영상(demo/STADIUM_LIVE_실동작_시연.mp4)으로 편집한다.

화면·소리는 에뮬레이터 녹화 그대로 쓰고, 그 위에 시연 영상 시안과 같은 구간 제목과
기능 설명 칩만 얹는다. 시각(초)은 tools/record_demo.sh의 진행 순서에 맞춘 값이다.
실행: tools/.venv/bin/python -I tools/edit_demo.py
"""
import json
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
DEMO = ROOT / "demo"
WORK = DEMO / "edit"
FONT = ROOT / "app" / "src" / "main" / "res" / "font"
W, H = 1920, 1080
DEV = (200, 182, 1520, 855)  # 녹화 화면(2560x1440)을 놓을 위치와 크기
INTRO, OUTRO = 3.0, 3.5
# 배경 음악: 시연 영상 시안(사용자 제작)의 오프닝 음악. 처음(중계 시작 전)과 마지막 멘트 뒤에만 깐다.
BGM_SRC = Path.home() / "Downloads" / "STADIUM_LIVE_시연영상_v2.mp4"
BGM_RANGE = (0.3, 36.0)  # 시안에서 글자만 나오는 오프닝 구간
BGM_GAIN = 0.55
# 앱 화면의 '차량 실내 연출' 카드(Vehicle SDK 응답값) 위치와 확대 인셋 표시 구간.
INSET = (906, 986, 428, 144)
INSET_K = 1.12
INSET_XY = (212, 752)
INSET_T = (36.0, 85.0)

TEAL = (82, 200, 240)
WHITE = (244, 246, 250)
SUB = (180, 194, 220)
DIM = (120, 138, 170)


def font(weight, size):
    return ImageFont.truetype(str(FONT / f"pretendard_{weight}.otf"), size)


def background():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        k = y / H
        d.line([(0, y), (W, y)], fill=(int(8 + 6 * (1 - k)), int(32 + 20 * (1 - k)), int(70 + 40 * (1 - k))))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([-300, -500, 1100, 500], fill=(40, 110, 200, 70))
    img.paste(glow.filter(ImageFilter.GaussianBlur(160)), (0, 0), glow.filter(ImageFilter.GaussianBlur(160)))
    # 녹화 화면 뒤 그림자와 테두리.
    x, y, w, h = DEV
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([x - 6, y + 4, x + w + 6, y + h + 18], 34, fill=(0, 0, 0, 150))
    img.paste(sh.filter(ImageFilter.GaussianBlur(18)), (0, 0), sh.filter(ImageFilter.GaussianBlur(18)))
    ImageDraw.Draw(img).rounded_rectangle([x - 10, y - 10, x + w + 10, y + h + 10], 30, fill=(20, 24, 34), outline=(52, 60, 80), width=2)
    d = ImageDraw.Draw(img)
    d.text((x, 1052), "STADIUM LIVE · 현대자동차그룹 차량용 앱 개발 공모전 시연 영상 · Pleos Connect Emulator 2.1.0 실제 화면·소리 녹화 · 가상 경기(실제 팀·선수 아님) · 해설은 시연용 TTS",
           font=font("medium", 15), fill=DIM)
    img.save(WORK / "bg.png")


def mask():
    x, y, w, h = DEV
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], 22, fill=255)
    m.save(WORK / "mask.png")


def header(name, no, tag, chip, title_a, title_b, title_c, sub):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    x = DEV[0]
    f_no, f_tag = font("extrabold", 20), font("bold", 21)
    d.rounded_rectangle([x, 26, x + 44, 56], 7, fill=(28, 150, 210))
    d.text((x + 22, 41), no, font=f_no, fill=WHITE, anchor="mm")
    d.text((x + 56, 41), tag, font=f_tag, fill=TEAL, anchor="lm")
    tw = d.textlength(tag, font=f_tag)
    cx = x + 56 + tw + 14
    cw = d.textlength(chip, font=font("medium", 17)) + 26
    d.rounded_rectangle([cx, 26, cx + cw, 56], 15, outline=(90, 120, 170), width=2, fill=(20, 40, 75))
    d.text((cx + cw / 2, 41), chip, font=font("medium", 17), fill=SUB, anchor="mm")
    f_title = font("extrabold", 46)
    tx = x
    for part, color in ((title_a, WHITE), (title_b, TEAL), (title_c, WHITE)):
        d.text((tx, 70), part, font=f_title, fill=color)
        tx += d.textlength(part, font=f_title)
    d.text((x, 130), sub, font=font("medium", 21), fill=SUB)
    img.save(WORK / f"{name}.png")


def callout(name, text, accent=TEAL):
    """화면 오른쪽 위에 뜨는 기능 설명 칩."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = font("bold", 22)
    tw = d.textlength(text, font=f)
    x2 = DEV[0] + DEV[2]
    x1 = x2 - tw - 64
    d.rounded_rectangle([x1, 92, x2, 140], 24, fill=(10, 22, 44, 235), outline=accent, width=2)
    d.ellipse([x1 + 20, 110, x1 + 32, 122], fill=accent)
    d.text((x1 + 44, 116), text, font=f, fill=WHITE, anchor="lm")
    img.save(WORK / f"{name}.png")


def card(name, lines):
    img = Image.open(WORK / "bg.png").convert("RGB")
    # 카드 화면은 녹화 테두리 대신 가운데 정렬 문구만.
    base = Image.new("RGB", (W, H))
    d0 = ImageDraw.Draw(base)
    for y in range(H):
        k = y / H
        d0.line([(0, y), (W, y)], fill=(int(8 + 6 * (1 - k)), int(32 + 20 * (1 - k)), int(70 + 40 * (1 - k))))
    d = ImageDraw.Draw(base)
    # 로고.
    cx, cy = W // 2, 360
    d.rounded_rectangle([cx - 56, cy - 56, cx + 56, cy + 56], 30, fill=(40, 150, 220))
    d.polygon([(cx, cy - 22), (cx + 22, cy), (cx, cy + 22), (cx - 22, cy)], outline=WHITE, width=7)
    for k in (1, 2):
        r = 18 + k * 13
        d.arc([cx - r, cy - r - 6, cx + r, cy + r - 6], 215, 325, fill=WHITE, width=6)
    y = 470
    for text, size, weight, color in lines:
        d.text((W // 2, y), text, font=font(weight, size), fill=color, anchor="mt")
        y += size + 22
    base.save(WORK / f"{name}.png")


def run(cmd):
    print(" ".join(map(str, cmd))[:200])
    subprocess.run(cmd, check=True)


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    background()
    mask()
    header("h1", "01", "출발 전 준비", "정차 상태", "출발 전 한 번, ", "내 응원 취향", "을 정해요", "응원 팀 · 연출 강도를 정해 두면 출발 후 자동 적용")
    header("h2", "02", "주행 중 청취", "해설 중심 모드", "달리는 동안엔 ", "해설 중심", ", 화면은 최소로", "점수·이닝만 크게 · 몰입 연출 잠금 · 내비 안내와 통화가 오면 중계가 먼저 비켜요")
    header("h3", "03", "정차 후 몰입", "Park 감지", "멈췄을 때, ", "한 타석의 긴장감", "까지", "소리뿐 아니라 실내 앰비언트 조명·공조 송풍까지 경기 흐름에 맞춰 움직여요 (Pleos Vehicle SDK)")
    header("h4", "04", "종료 및 피드백", "정차 상태", "언제든 ", "기본 중계", "로, 듣고 난 뒤엔 짧게 평가", "몰입감 · 해설 명료도 · 음량 변화 불편을 정차 상태에서만 입력")
    callouts = [
        ("c1", "현장감 '강' 선택 → 이 경기 듣기", 6.8, 11.5, TEAL),
        ("c2", "기어 D → 주행 제한 감지 · 해설 중심 화면", 15.0, 19.6, (245, 184, 61)),
        ("c3", "내비 안내 → 중계·효과 음량만 낮춤(오디오 포커스)", 19.8, 24.6, (245, 184, 61)),
        ("c4", "전화 수신 → 중계·효과 일시정지, 통화 후 자동 재개", 24.8, 33.8, (245, 184, 61)),
        ("c5", "기어 P → 몰입 모드로 자동 전환", 34.2, 39.0, (46, 211, 160)),
        ("c6", "심박 96 → 132bpm · 실내 조명이 박자마다 붉게 맥동", 39.5, 60.6, (240, 66, 79)),
        ("c7", "타격 '딱!' 순간 실내 흰 섬광 + 송풍 최대 → 서서히 잦아듦", 61.0, 64.2, (111, 162, 255)),
        ("c8", "끝내기 → 팀 색 조명 점멸 · 응원가 박자에 맞춘 조명과 함성", 64.4, 85.0, (232, 118, 60)),
        ("c9", "평가 저장 → 다음 연출 강도 추천에 반영", 92.0, 101.0, TEAL),
    ]
    for name, text, *_ in callouts:
        callout(name, text, _[2])
    card("intro", [("STADIUM LIVE", 64, "extrabold", WHITE), ("소리 · 조명 · 바람으로 차 안을 야구장으로", 28, "medium", SUB),
                   ("실동작 시연 · Pleos Connect Emulator 2.1.0", 22, "semibold", TEAL)])
    card("outro", [("STADIUM LIVE", 56, "extrabold", WHITE), ("미디어 재생 위치 하나로 효과를 맞추고, 주행·정차와 오디오 포커스에 따라 비켜서는 연출", 26, "medium", SUB),
                   ("Pleos Vehicle SDK(앰비언트 조명·공조) · Media3 · CarUxRestrictions · 자체 제작 음원", 21, "semibold", TEAL)])

    sections = [("h1", 0, 12.2), ("h2", 12.2, 34.2), ("h3", 34.2, 85.6), ("h4", 85.6, 101.5)]
    x, y, w, h = DEV
    inputs = ["-i", str(DEMO / "raw.webm"), "-loop", "1", "-i", str(WORK / "bg.png"), "-loop", "1", "-i", str(WORK / "mask.png")]
    overlays = [s[0] for s in sections] + [c[0] for c in callouts]
    for o in overlays:
        inputs += ["-loop", "1", "-i", str(WORK / f"{o}.png")]
    fc = ["[0:v]split[raw0][raw1]", f"[raw0]scale={w}:{h}:flags=lanczos,format=rgba[dv]", "[2:v]format=gray[m]", "[dv][m]alphamerge[dm]", f"[1:v][dm]overlay={x}:{y}:shortest=1[v0]"]
    cur = "v0"
    times = {s[0]: (s[1], s[2]) for s in sections} | {c[0]: (c[2], c[3]) for c in callouts}
    for i, o in enumerate(overlays):
        a, b = times[o]
        nxt = f"v{i + 1}"
        fc.append(f"[{cur}][{3 + i}:v]overlay=0:0:enable='between(t,{a},{b})'[{nxt}]")
        cur = nxt
    ia, ib = INSET_T
    fc.append(f"[raw1]crop={INSET[2]}:{INSET[3]}:{INSET[0]}:{INSET[1]},scale=iw*{INSET_K}:ih*{INSET_K}:flags=lanczos,pad=iw+8:ih+8:4:4:color=0x2ED3A0[ins]")
    fc.append(f"[{cur}][ins]overlay={INSET_XY[0]}:{INSET_XY[1]}:enable='between(t,{ia},{ib})'[vin]")
    cur = "vin"
    fc.append(f"[{cur}]format=yuv420p[vout]")
    # 에뮬레이터 녹화기는 시작 직후 무음 구간을 건너뛰어 소리가 화면보다 앞당겨진다. 재서 되돌린다.
    from check_sync import measure
    video_off, audio_off, *_ = measure(DEMO / "raw.webm")
    delay = max(0, round((video_off - audio_off) * 1000))
    print(f"audio delay {delay}ms")
    # 녹화 소리의 시각 공백은 무음으로 채워 끊김 없는 소리로 만든다(재생기마다 공백 처리가 달라 중간부터 소리가 밀리거나 끊긴다).
    # 주행 구간은 해설만 나와 정차 몰입 구간보다 20dB 이상 작다. 시연에서 들리도록 이 구간만 약 +10dB 올린다.
    rlog = (DEMO / "record.log").read_text()
    rclock = lambda tag: sum(int(x) * k for x, k in zip(re.search(rf"\[(\d+):(\d+):(\d+)\] {tag}", rlog).groups(), (3600, 60, 1)))
    d0, d1 = rclock("playback start") - rclock("record start"), rclock("park") - rclock("record start") + 1.0
    boost = f"volume='1+2.2*clip((t-{d0 - 1:.1f})/0.8,0,1)*clip(({d1:.1f}-t)/1.5,0,1)':eval=frame"
    print(f"drive boost {d0 - 1:.1f}~{d1:.1f}s")
    fc.append(f"[0:a]aresample=async=1:first_pts=0,adelay={delay}:all=1,{boost},loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]")
    main_mp4 = WORK / "main.mp4"
    run(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", ";".join(fc), "-map", "[vout]", "-map", "[aout]",
         "-r", "30", "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-c:a", "aac", "-b:a", "192k", "-t", "101", str(main_mp4)])
    for name, dur in (("intro", INTRO), ("outro", OUTRO)):
        run(["ffmpeg", "-v", "error", "-y", "-loop", "1", "-i", str(WORK / f"{name}.png"), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
             "-t", str(dur), "-vf", f"fade=in:0:12,fade=out:st={dur - 0.4}:d=0.4,format=yuv420p", "-r", "30", "-c:v", "libx264", "-crf", "19", "-c:a", "aac", "-b:a", "192k", str(WORK / f"{name}.mp4")])
    out = DEMO / "STADIUM_LIVE_실동작_시연.mp4"
    # 배경 음악 구간(최종 영상 시각). 앞: 시작~중계 재생 직전, 뒤: 마지막 해설이 끝난 뒤~끝.
    log = (DEMO / "record.log").read_text()
    clock = lambda tag: sum(int(x) * k for x, k in zip(re.search(rf"\[(\d+):(\d+):(\d+)\] {tag}", log).groups(), (3600, 60, 1)))
    pb = clock("playback start") - clock("record start") - 0.3
    game = json.loads((ROOT / "app/src/main/assets/game1/game.json").read_text())
    last = max(c["end"] for c in game["captions"]) / 1000 + video_off + 0.4
    total = INTRO + 101 + OUTRO
    a_end, b_start = INTRO + pb, INTRO + last
    print(f"bgm 0~{a_end:.1f}s, {b_start:.1f}~{total:.1f}s")
    b_len = total - b_start
    fc = [f"[0:v][0:a][1:v][1:a][2:v][2:a]concat=n=3:v=1:a=1[v][a0]",
          f"[3:a]atrim={BGM_RANGE[0]}:{BGM_RANGE[1]},asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,loudnorm=I=-18:TP=-2,volume={BGM_GAIN},asplit[m1][m2]",
          f"[m1]atrim=0:{a_end:.2f},afade=t=in:d=0.8,afade=t=out:st={a_end - 2.2:.2f}:d=2.2[ba]",
          f"[m2]atrim=0:{b_len:.2f},afade=t=in:d=2.5,afade=t=out:st={b_len - 2.5:.2f}:d=2.5,adelay={round(b_start * 1000)}:all=1[bb]",
          "[a0][ba][bb]amix=inputs=3:normalize=0:duration=first,alimiter=limit=0.95,aresample=async=1:first_pts=0[a]"]
    run(["ffmpeg", "-v", "error", "-y", "-i", str(WORK / "intro.mp4"), "-i", str(main_mp4), "-i", str(WORK / "outro.mp4"), "-i", str(BGM_SRC),
         "-filter_complex", ";".join(fc), "-map", "[v]", "-map", "[a]",
         "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(out)])
    print(out, out.stat().st_size)


if __name__ == "__main__":
    main()
