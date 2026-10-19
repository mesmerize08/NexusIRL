"""Regenerates the launcher icon layers and the store icon from docs/art/icon-source.png.

Usage: python tools/make-icons.py docs/art/icon-source.png app/src/main/res            fastlane/metadata/android/en-US/images/icon.png <preview.png>
Needs Pillow. The preview shows the icon under circle, squircle, square and themed masks.
"""
import sys, os
from PIL import Image, ImageDraw, ImageFilter, ImageOps
src, res, store, preview = sys.argv[1:5]
im = Image.open(src).convert('RGBA')
BG = (3, 6, 30, 255)
# Interior of the rounded square, inside its glowing border; the launcher mask supplies the shape.
crop = im.crop((150, 150, 1110, 1110))
def feather(img, frac=0.14):
    w, h = img.size
    m = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(m)
    inset = int(w * frac / 2)
    d.rounded_rectangle((inset, inset, w - inset, h - inset), radius=int(w * 0.18), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(w * frac / 4))
    out = img.copy(); out.putalpha(Image.composite(img.split()[3], Image.new('L', (w, h), 0), m))
    return out
art = feather(crop)
def foreground(px):
    layer = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    # Launchers show the centre 72 of 108 dp; keep the N inside the 66 dp safe zone.
    inner = int(px * 54 / 108)
    a = art.resize((inner, inner), Image.LANCZOS)
    layer.alpha_composite(a, ((px - inner) // 2, (px - inner) // 2))
    return layer
def monochrome(px):
    fg = foreground(px)
    lum = ImageOps.autocontrast(fg.convert('L'), cutoff=1)
    alpha = Image.eval(lum, lambda v: 0 if v < 70 else min(255, (v - 70) * 3))
    alpha = Image.composite(alpha, Image.new('L', fg.size, 0), fg.split()[3])
    out = Image.new('RGBA', fg.size, (255, 255, 255, 0)); out.putalpha(alpha); return out
dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in dens.items():
    d = os.path.join(res, f'mipmap-{name}'); os.makedirs(d, exist_ok=True)
    px = int(108 * k)
    foreground(px).save(os.path.join(d, 'ic_launcher_foreground.png'), optimize=True)
    monochrome(px).save(os.path.join(d, 'ic_launcher_monochrome.png'), optimize=True)
# Store / F-Droid icon: the full artwork at 512.
os.makedirs(os.path.dirname(store), exist_ok=True)
full = im.crop(im.split()[3].getbbox()).resize((480, 480), Image.LANCZOS)
st = Image.new('RGBA', (512, 512), (0, 0, 0, 0)); st.alpha_composite(full, (16, 16)); st.save(store, optimize=True)
# Preview: circle, squircle, rounded square masks plus themed.
px = 432; tiles = []
for shape in ['circle', 'squircle', 'square']:
    t = Image.new('RGBA', (px, px), BG); t.alpha_composite(foreground(px))
    view = t.crop((72, 72, 360, 360)).resize((360, 360), Image.LANCZOS)
    m = Image.new('L', view.size, 0); d = ImageDraw.Draw(m)
    if shape == 'circle': d.ellipse((0, 0, 359, 359), fill=255)
    elif shape == 'squircle': d.rounded_rectangle((0, 0, 359, 359), radius=120, fill=255)
    else: d.rounded_rectangle((0, 0, 359, 359), radius=40, fill=255)
    view.putalpha(m); tiles.append(view)
th = Image.new('RGBA', (px, px), (40, 60, 90, 255)); mono = monochrome(px)
tint = Image.new('RGBA', (px, px), (190, 220, 255, 255)); tint.putalpha(mono.split()[3]); th.alpha_composite(tint)
th = th.crop((72, 72, 360, 360)).resize((360, 360), Image.LANCZOS); m = Image.new('L', th.size, 0); ImageDraw.Draw(m).ellipse((0, 0, 359, 359), fill=255); th.putalpha(m); tiles.append(th)
sheet = Image.new('RGBA', (360 * 4 + 50, 380), (230, 230, 230, 255))
for i, t in enumerate(tiles): sheet.alpha_composite(t, (10 + i * 370, 10))
sheet.save(preview)
