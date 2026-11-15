"""녹화본의 해설 음성이 화면과 맞는지 검사한다.

에뮬레이터 녹화기는 가끔 소리 일부를 빠뜨려 이후 소리가 화면보다 앞당겨진다. 해설 문장마다
원본 중계 음원(broadcast.m4a)과 녹화 소리를 맞대어 '녹화 시각 - 미디어 시각'을 구하고,
타격 순간 화면 섬광(시각 기준)과 비교한다. 정차 후 구간은 모두 같은 값이어야 한다.
실행: tools/.venv/bin/python -I tools/check_sync.py demo/raw.webm [섬광 찾기 시작 초=45]
"""
import json, subprocess, sys
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
FS = 4000

def pcm(path, *args):
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), *args, "-ac", "1", "-ar", str(FS), "-f", "s16le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(out, np.int16).astype(float)

def measure(raw, after=45.0):
    """(화면 오프셋, 정차 구간 음성 오프셋 중앙값, 문장별 결과)를 돌려준다."""
    g = json.loads((ROOT / "app/src/main/assets/game1/game.json").read_text())
    b = pcm(ROOT / "app/src/main/assets/game1/broadcast.m4a")
    r = pcm(raw)
    offs = []
    for c in g["captions"]:
        m = c["t"] / 1000
        seg = b[int(m * FS):int((m + 1.5) * FS)]; seg = seg - seg.mean()
        win = np.lib.stride_tricks.sliding_window_view(r, len(seg))[::8]
        win = win - win.mean(axis=1, keepdims=True)
        cor = win @ seg / np.sqrt((win ** 2).sum(axis=1) * (seg ** 2).sum() + 1e-9)
        i = int(cor.argmax()); offs.append((m, i * 8 / FS - m, cor[i], c["text"][:16]))
    hit = next(c["t"] for c in g["cues"] if c["type"] == "HIT") / 1000
    # 타격 섬광: 홈 플레이트 주변 밝기가 급격히 오르는 첫 프레임.
    frames = subprocess.run(["ffmpeg", "-v", "error", "-i", str(raw), "-vf", "fps=60,crop=200:200:1885:780,scale=20:20,format=gray", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    v = np.frombuffer(frames, np.uint8).reshape(-1, 400).mean(axis=1)
    flash = next(i for i in range(int(after * 60), len(v)) if v[i] - v[i - 1] > 15) / 60
    video_off = flash - hit
    late = [off for m, off, c, _ in offs if m >= hit - 5 and c > 0.25]
    return video_off, float(np.median(late)), offs, hit, flash


def main(raw, after=45.0):
    video_off, audio_off, offs, hit, flash = measure(raw, after)
    print(f"화면 기준 오프셋 {video_off:.3f}s (타격 섬광 {flash:.2f}s), 음성 {audio_off:.3f}s")
    bad = 0
    for m, off, c, t in offs:
        late = m >= hit - 5
        d = off - video_off
        flag = "" if not late or abs(d) < 0.2 else "  <-- 어긋남"
        bad += bool(flag)
        print(f"{m:6.2f}s 음성 오프셋 {off:7.3f} (화면 대비 {d:+.2f}) corr {c:.2f} {t}{flag}")
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main(sys.argv[1], *map(float, sys.argv[2:]))
