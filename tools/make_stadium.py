"""정차 몰입 화면의 경기장 배경(1600x1000)을 그린다.

좌표계는 앱의 StadiumGeometry와 같다. 홈플레이트(800, 820), 펜스 반지름 600,
베이스 간격 190. 관중석 불빛은 흐리게(보케) 처리해 사진 같은 질감을 낸다.
실행: tools/.venv/bin/python -I tools/make_stadium.py
"""
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1600, 1000
HOME = (800, 820)
R = 600
D = 190
OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "stadium.webp"
rng = np.random.default_rng(3)


def polar(a_deg, r):
    a = math.radians(a_deg)
    return HOME[0] + r * math.cos(a), HOME[1] - r * math.sin(a)


def fan(r, a0=45, a1=135, steps=90):
    pts = [HOME]
    for i in range(steps + 1):
        pts.append(polar(a0 + (a1 - a0) * i / steps, r))
    return pts


def radial(size, center, radius, color, alpha):
    """부드러운 원형 빛 레이어(RGBA)."""
    yy, xx = np.mgrid[0:size[1], 0:size[0]]
    d = np.sqrt((xx - center[0]) ** 2 + (yy - center[1]) ** 2) / radius
    a = np.clip(1 - d, 0, 1) ** 2.2 * alpha
    layer = np.zeros((size[1], size[0], 4), dtype=np.float32)
    layer[..., 0], layer[..., 1], layer[..., 2] = color
    layer[..., 3] = a * 255
    return Image.fromarray(layer.astype(np.uint8), "RGBA")


def main():
    img = Image.new("RGBA", (W, H), (9, 12, 19, 255))
    # 경기장 바깥 어둠에 아주 약한 푸른 기운.
    img.alpha_composite(radial((W, H), (800, 520), 900, (30, 44, 70), 0.55))

    # 관중석: 어두운 계단식 띠 + 흐린 불빛 점.
    stands = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stands)
    for k, r in enumerate(range(R + 200, R + 20, -12)):
        shade = 18 + (k % 2) * 5
        sd.pieslice([HOME[0] - r, HOME[1] - r, HOME[0] + r, HOME[1] + r], 180 + 32, 360 - 32, fill=(shade, shade + 4, shade + 12, 255))
    img.alpha_composite(stands)

    dots = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dots)
    palette = [(235, 240, 255), (170, 195, 255), (90, 130, 255), (255, 120, 120), (255, 220, 150), (210, 210, 220)]
    for _ in range(4600):
        a = rng.uniform(34, 146)
        r = rng.uniform(R + 28, R + 205)
        x, y = polar(a, r)
        if not (0 <= x < W and 0 <= y < H):
            continue
        c = palette[rng.integers(len(palette))]
        rad = rng.uniform(1.2, 3.4)
        alpha = int(rng.uniform(70, 230))
        dd.ellipse([x - rad, y - rad, x + rad, y + rad], fill=(*c, alpha))
    img.alpha_composite(dots.filter(ImageFilter.GaussianBlur(1.1)))
    img.alpha_composite(dots.filter(ImageFilter.GaussianBlur(6)))

    # 그라운드.
    ground = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(ground)
    gd.polygon(fan(R), fill=(120, 78, 50, 255))  # 워닝 트랙
    gd.polygon(fan(R - 24), fill=(30, 92, 58, 255))
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).polygon(fan(R - 24), fill=255)
    stripes = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    st = ImageDraw.Draw(stripes)
    for i in range(-40, 60):
        # 45도 사선 잔디 결.
        x0 = i * 44
        st.polygon([(x0, 0), (x0 + 22, 0), (x0 + 22 - H, H), (x0 - H, H)], fill=(255, 255, 255, 14))
    ground.paste(stripes, (0, 0), Image.composite(stripes, Image.new("RGBA", (W, H)), mask))
    gd = ImageDraw.Draw(ground)
    # 내야 흙: 홈 주변 원 + 베이스 라인을 따른 흙길 + 내야 잔디.
    b1, b2, b3 = polar(45, D), polar(90, D * math.sqrt(2)), polar(135, D)
    gd.polygon([HOME, polar(45, D + 26), polar(90, D * math.sqrt(2) + 30), polar(135, D + 26)], fill=(138, 92, 58, 255))
    inner = [polar(90, 18), polar(45, D - 18), polar(90, D * math.sqrt(2) - 24), polar(135, D - 18)]
    inner = [(x, y) for x, y in inner]
    gd.polygon(inner, fill=(38, 112, 70, 255))
    gd.ellipse([HOME[0] - 34, HOME[1] - 34, HOME[0] + 34, HOME[1] + 34], fill=(138, 92, 58, 255))
    mound = polar(90, D * 0.707 * 1.0)
    gd.ellipse([mound[0] - 17, mound[1] - 17, mound[0] + 17, mound[1] + 17], fill=(150, 100, 64, 255))
    gd.rectangle([mound[0] - 6, mound[1] - 2, mound[0] + 6, mound[1] + 2], fill=(235, 235, 235, 255))
    # 파울 라인·베이스 라인.
    line = (240, 244, 250, 235)
    gd.line([HOME, polar(45, R - 24)], fill=line, width=3)
    gd.line([HOME, polar(135, R - 24)], fill=line, width=3)
    gd.line([b1, b2, b3], fill=(240, 244, 250, 120), width=2)
    for b in (b1, b2, b3):
        gd.polygon([(b[0], b[1] - 9), (b[0] + 9, b[1]), (b[0], b[1] + 9), (b[0] - 9, b[1])], fill=(250, 250, 250, 255))
    gd.polygon([(HOME[0] - 8, HOME[1] - 6), (HOME[0] + 8, HOME[1] - 6), (HOME[0] + 8, HOME[1] + 2), (HOME[0], HOME[1] + 9), (HOME[0] - 8, HOME[1] + 2)], fill=(250, 250, 250, 255))
    img.alpha_composite(ground)

    # 조명: 그라운드를 위에서 비추는 빛 + 조명탑 번짐.
    light = radial((W, H), (800, 430), 700, (255, 250, 230), 0.20)
    img.alpha_composite(light)
    for x, y, rad in ((270, 30, 260), (590, -20, 230), (1010, -20, 230), (1330, 30, 260)):
        img.alpha_composite(radial((W, H), (x, y), rad, (255, 250, 235), 0.85))
        img.alpha_composite(radial((W, H), (x, y), rad * 0.25, (255, 255, 255), 1.0))
    # 가장자리 비네트.
    img.alpha_composite(radial((W, H), (800, 1500), 1500, (0, 0, 0), 0.0))
    yy, xx = np.mgrid[0:H, 0:W]
    v = np.clip((np.sqrt(((xx - 800) / 900) ** 2 + ((yy - 560) / 640) ** 2) - 0.75) / 0.6, 0, 1) * 200
    vig = np.zeros((H, W, 4), dtype=np.uint8)
    vig[..., :3] = (8, 11, 17)
    vig[..., 3] = v.astype(np.uint8)
    img.alpha_composite(Image.fromarray(vig, "RGBA"))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT, "WEBP", quality=88, method=6)
    print(OUT, OUT.stat().st_size)


if __name__ == "__main__":
    main()
