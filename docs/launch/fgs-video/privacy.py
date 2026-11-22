"""What stays sharp in the Pixel clips. Everything else is blurred. Times are video seconds."""
from PIL import Image, ImageDraw, ImageFilter
STATUS = ("rect", (0, 0, 1080, 128))
ICON = ("circle", (412, 2026, 86))
HEADSUP = ("rrect", (42, 184, 1038, 478, 60))
# Melodee's photo is blurred again; Transmit's small badge on it stays sharp.
def face(cy): return [("blur", (137, cy, 60)), ("circle", (167, cy + 32, 24))]
RULES = {
 "phone": [(0, 2.10, [STATUS, ICON]), (2.10, 3.05, [STATUS]), (3.05, 16.85, [("rect", (0, 0, 1080, 292))]),
           (16.85, 21.65, []), (21.65, 28.85, [("rrect", (42, 1943, 1038, 2209, 56))]), (28.85, 31.0, []),
           (31.0, 1e9, [STATUS, ICON])],
 "phone2": [(0, 5.80, [STATUS, ICON]), (5.80, 9.02, [STATUS, ICON, HEADSUP] + face(278)), (9.02, 9.65, []),
            (9.65, 17.6, [("rrect", (42, 635, 1038, 976, 56)), ("rrect", (42, 1820, 1038, 2009, 56))] + face(730)),
            (17.6, 1e9, [])],
}
def ops(clip, t):
    for a, b, r in RULES[clip]:
        if a <= t < b: return r
    return []
def mask(size, kind, g):
    m = Image.new("L", size, 0); d = ImageDraw.Draw(m)
    if kind == "rect": d.rectangle(g, fill=255)
    elif kind == "rrect": d.rounded_rectangle(g[:4], radius=g[4], fill=255)
    else: cx, cy, r = g; d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    return m
def private(img, clip, t):
    W, H = img.size
    blurred = img.resize((W // 30, H // 30), Image.BILINEAR).resize((W, H), Image.BILINEAR).filter(ImageFilter.GaussianBlur(14))
    out = blurred.copy()
    for kind, g in ops(clip, t):
        if kind == "blur": out.paste(blurred, (0, 0), mask(img.size, "circle", g))
        else: out.paste(img, (0, 0), mask(img.size, kind, g))
    return out
