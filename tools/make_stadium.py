"""Deterministic night stadium render. Matches Visuals.kt Field coordinates exactly.

Run tools/.venv/bin/python -I tools/make_stadium.py. No external assets.
"""
import math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1600, 1000
HOME = (800, 820)
R, D = 600, 190
OUT = Path(__file__).resolve().parent.parent / 'app/src/main/assets/stadium.webp'
rng = np.random.default_rng(31)


def polar(a, r):
    a = math.radians(a)
    return (HOME[0] + r * math.cos(a), HOME[1] - r * math.sin(a))


def arc(r, a=30, b=150):
    return [polar(t, r) for t in np.linspace(a, b, 320)]


def ring(inner, outer, a=30, b=150):
    return arc(outer, a, b) + arc(inner, a, b)[::-1]


def glow(img, point, radius, color, opacity):
    yy, xx = np.mgrid[:H, :W]
    d = ((xx-point[0])**2 + (yy-point[1])**2) / radius**2
    layer = np.zeros((H, W, 4), dtype=np.uint8)
    layer[..., :3] = color
    layer[..., 3] = (np.exp(-d * 4) * opacity).astype(np.uint8)
    img.alpha_composite(Image.fromarray(layer))


def main():
    img = Image.new('RGBA', (W, H), (5, 11, 19, 255))
    glow(img, (800, 350), 1000, (31, 57, 79), 110)
    dr = ImageDraw.Draw(img)
    # The bowl has three real tiers, separated by concourses and illuminated fascia.
    for inner, outer in [(638, 700), (724, 785), (810, 872)]:
        dr.polygon(ring(inner-5, outer+8, 24, 156), fill=(8, 17, 27))
        dr.polygon(ring(inner, outer, 25, 155), fill=(22, 37, 48))
        for radius in np.arange(inner + 3, outer, 6):
            dr.line(arc(radius, 25, 155), fill=(42, 56, 65), width=1)
            for angle in np.arange(26, 155, 0.48):
                if int((angle-25) % 10) == 0:
                    continue
                x, y = polar(angle, radius)
                if not 0 <= y < H:
                    continue
                shade = rng.uniform(.35, 1)
                c = [(79,101,116), (148,157,157), (61,90,113), (173,152,117)][rng.integers(4)]
                dr.rectangle((x-1.5, y-1, x+1.5, y+1.2), fill=tuple(int(v*shade) for v in c))
        for angle in range(25, 156, 10):
            dr.line([polar(angle, inner), polar(angle, outer)], fill=(74, 84, 86), width=3)
            for radius in range(inner, outer, 5):
                p = polar(angle, radius)
                dr.line([(p[0]-2,p[1]),(p[0]+2,p[1])], fill=(123,129,121), width=1)
        dr.polygon(ring(inner-9, inner-3, 25,155), fill=(12,33,48))
        dr.line(arc(inner-4,25,155), fill=(77,145,176), width=2)
        dr.line(arc(outer+4,25,155), fill=(58,72,82), width=2)
    # Sparse spectator lights, not multicoloured confetti.
    lights = Image.new('RGBA', (W,H))
    ld = ImageDraw.Draw(lights)
    for _ in range(420):
        x,y = polar(rng.uniform(27,153),rng.uniform(640,868))
        ld.ellipse((x-1,y-1,x+1,y+1),fill=(197,224,239,int(rng.uniform(50,185))))
    img.alpha_composite(lights.filter(ImageFilter.GaussianBlur(2)))
    img.alpha_composite(lights)
    dr = ImageDraw.Draw(img)
    # Foul territory, warning track and padded fence.
    dr.polygon([HOME]+arc(632,39,141), fill=(23,43,39))
    dr.polygon([HOME]+arc(600,45,135), fill=(107,74,53))
    dr.polygon(ring(600,618,45,135),fill=(12,35,40))
    dr.line(arc(618,45,135),fill=(101,155,161),width=2)
    dr.line(arc(601,45,135),fill=(214,185,114),width=3)
    for angle in range(46,135,3):
        dr.line([polar(angle,601),polar(angle,618)], fill=(40,64,65),width=1)
    # Pixel-level turf: alternating mowing direction, fine blades, floodlight falloff.
    yy,xx=np.mgrid[:H,:W]
    radius=np.sqrt((xx-800)**2+(yy-820)**2)
    stripe=np.where(((xx+yy)//38)%2==0,1.,.86)
    illumination=.70+.30*np.exp(-((xx-800)**2/450000+(yy-490)**2/250000))
    grain=rng.normal(0,1.7,(H,W))
    turf=np.zeros((H,W,3),dtype=np.uint8)
    for c,value in enumerate((43,104,69)):
        turf[...,c]=np.clip(value*stripe*illumination+grain,0,255)
    mask=Image.new('L',(W,H))
    ImageDraw.Draw(mask).polygon([HOME]+arc(576,45,135),fill=255)
    img.paste(Image.fromarray(turf),(0,0),mask)
    # Rounded infield skin with turf diamond, cutouts, batter boxes and chalk.
    dirt=Image.new('L',(W,H)); dd=ImageDraw.Draw(dirt)
    dd.polygon([HOME]+arc(310,45,135), fill=255)
    diamond=[polar(90,24),polar(45,D-24),polar(90,D*math.sqrt(2)-28),polar(135,D-24)]
    dd.polygon(diamond,fill=0)
    dd.ellipse((766,786,834,854),fill=255)
    mound=polar(90,D*.707)
    dd.ellipse((mound[0]-19,mound[1]-19,mound[0]+19,mound[1]+19),fill=255)
    texture=np.zeros((H,W,3),dtype=np.uint8)
    for c,value in enumerate((142,103,75)):
        texture[...,c]=np.clip(value*illumination+rng.normal(0,2,(H,W)),0,255)
    img.paste(Image.fromarray(texture),(0,0),dirt)
    dr=ImageDraw.Draw(img)
    chalk=(215,225,211)
    for angle in (45,135):
        dr.line([HOME,polar(angle,600)],fill=chalk,width=2)
        x,y=polar(angle,600)
        dr.line([(x,y),(x,y-60)],fill=(218,191,94),width=3)
        dr.line([(x,y-60),(x+8,y-60),(x+8,y-30)],fill=(146,137,83),width=1)
    for x,y in (polar(45,D),polar(90,D*math.sqrt(2)),polar(135,D)):
        dr.polygon([(x,y-6),(x+6,y),(x,y+6),(x-6,y)],fill=(239,237,218))
    dr.rectangle((mound[0]-5,mound[1]-2,mound[0]+5,mound[1]+2),fill=chalk)
    dr.polygon([(794,816),(806,816),(806,822),(800,828),(794,822)],fill=chalk)
    for x in (779,810):
        dr.rectangle((x,806,x+11,829),outline=(195,187,160),width=1)
    # Dugouts and low railings anchor the near side of the field.
    for side in (-1,1):
        x=800+side*220
        dr.rounded_rectangle((x-55,735,x+55,759),radius=4,fill=(10,25,34),outline=(64,85,94),width=2)
        dr.line([(x-49,741),(x+49,741)],fill=(98,134,146),width=2)
        for dx in range(-45,46,15):
            dr.line([(x+dx,743),(x+dx,756)],fill=(45,68,80),width=2)
    # Architectural light banks with restrained bloom and visible individual lamps.
    for x,y in ((270,106),(490,36),(1110,36),(1330,106)):
        dr=ImageDraw.Draw(img)
        dr.line([(x,y+12),(x,y+84)],fill=(70,87,101),width=4)
        dr.line([(x-20,y+15),(x,y+45),(x+20,y+15)],fill=(59,76,90),width=2)
        dr.rounded_rectangle((x-46,y-12,x+46,y+13),radius=3,fill=(39,56,70))
        glow(img,(x,y),145,(174,215,244),75)
        glow(img,(x,y),54,(220,239,251),120)
        dr=ImageDraw.Draw(img)
        for col in range(9):
            for row in range(2):
                lx=x-39+col*9; ly=y-6+row*10
                dr.rectangle((lx,ly,lx+5,ly+4),fill=(236,247,255))
    # Low haze binds the bowl and field without obscuring the playable geometry.
    glow(img,(800,250),650,(113,162,187),16)
    distance=((xx-800)/1000)**2+((yy-480)/740)**2
    vignette=np.zeros((H,W,4),dtype=np.uint8)
    vignette[...,:3]=(3,8,15)
    vignette[...,3]=np.clip((distance-.35)*120,0,200).astype(np.uint8)
    img.alpha_composite(Image.fromarray(vignette))
    img.convert('RGB').save(OUT,'WEBP',quality=95,method=6)
    print(f'{OUT}: {OUT.stat().st_size:,} bytes')


if __name__=='__main__':
    main()
