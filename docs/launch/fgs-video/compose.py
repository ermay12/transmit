import subprocess, sys, textwrap
from PIL import Image, ImageDraw, ImageFont, ImageFilter
sys.path.insert(0, sys.argv[1])
from privacy import private
D = sys.argv[1]; OUT = sys.argv[2]
FW, FH, FPS = 1920, 1080, 30
F = "/Users/ermay12/Library/Android/sdk/platforms/android-26/data/fonts/Roboto-"
def font(w, s): return ImageFont.truetype(F + w + ".ttf", s)
BLUE = (63, 81, 232); INK = (24, 27, 38); GREY = (96, 102, 120); BG = (244, 245, 250)
ICON = Image.open("/Users/ermay12/Documents/transmit/docs/launch/store-assets/icon-512.png").convert("RGBA")

def wrap(d, text, f, width):
    lines = []
    for para in text.split("\n"):
        words = para.split(); cur = ""
        for w in words:
            t = (cur + " " + w).strip()
            if d.textlength(t, font=f) <= width: cur = t
            else: lines.append(cur); cur = w
        lines.append(cur)
    return lines

def caption(label, title, body, foot):
    im = Image.new("RGBA", (FW, FH), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    x, w = 930, 880
    fl, ft, fb, ff = font("Bold", 30), font("Bold", 60), font("Regular", 36), font("Regular", 26)
    tl = wrap(d, title, ft, w); bl = wrap(d, body, fb, w); fo = wrap(d, foot, ff, w)
    h = 44 + len(tl) * 74 + 28 + len(bl) * 52
    y = (FH - h) // 2 - 30
    d.text((x, y), label, font=fl, fill=BLUE); y += 58
    for l in tl: d.text((x, y), l, font=ft, fill=INK); y += 74
    y += 22
    for l in bl: d.text((x, y), l, font=fb, fill=GREY); y += 52
    fy = FH - 70 - len(fo) * 36
    for l in fo: d.text((x, fy), l, font=ff, fill=GREY); fy += 36
    return im

def card(title, sub, body, foot):
    im = Image.new("RGB", (FW, FH), BG); d = ImageDraw.Draw(im)
    ic = ICON.resize((180, 180), Image.LANCZOS)
    m = Image.new("L", (180, 180), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, 179, 179), 44, fill=255)
    fb = font("Regular", 38); bl = wrap(d, body, fb, 1240)
    h = 180 + 40 + 84 + (64 if sub else 0) + 30 + len(bl) * 56
    y = (FH - h) // 2
    im.paste(ic, ((FW - 180) // 2, y), m); y += 220
    ft = font("Bold", 76); d.text(((FW - d.textlength(title, font=ft)) / 2, y), title, font=ft, fill=INK); y += 96
    if sub:
        fs = font("Medium", 44); d.text(((FW - d.textlength(sub, font=fs)) / 2, y), sub, font=fs, fill=BLUE); y += 76
    for l in bl: d.text(((FW - d.textlength(l, font=fb)) / 2, y), l, font=fb, fill=GREY); y += 56
    ff = font("Regular", 26); d.text(((FW - d.textlength(foot, font=ff)) / 2, FH - 80), foot, font=ff, fill=GREY)
    return im

SH = 960  # screen height on the canvas
def frame_layer(sw):
    """Background with a phone bezel and shadow for a screen of width sw."""
    im = Image.new("RGB", (FW, FH), BG)
    x0 = 540 - sw // 2; y0 = (FH - SH) // 2
    sh = Image.new("L", (FW, FH), 0)
    ImageDraw.Draw(sh).rounded_rectangle((x0 - 14, y0 - 14 + 10, x0 + sw + 14, y0 + SH + 14 + 10), 58, fill=90)
    im.paste((180, 184, 200), (0, 0), sh.filter(ImageFilter.GaussianBlur(22)))
    ImageDraw.Draw(im).rounded_rectangle((x0 - 14, y0 - 14, x0 + sw + 14, y0 + SH + 14), 58, fill=(20, 22, 28))
    m = Image.new("L", (sw, SH), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, sw - 1, SH - 1), 44, fill=255)
    return im, (x0, y0), m

TXT = "Transmit 1.0.0-beta18"
PIX = "Pixel 9a, Android 17, " + TXT + " from Google Play. Personal chats and other apps' notifications are blurred."
EMU = "Android emulator, " + TXT + ", before pairing."
c1 = caption("STEP 1 · PAIRING", "The user pairs Transmit with Google Messages",
    "They tap Connect Google Messages, then sign in with the Google account Google Messages uses, or scan a QR code with Google Messages (Device pairing). Pairing starts the sync.", EMU)
c2 = caption("STEP 2 · THE SERVICE STARTS", "Opening Transmit starts the foreground service",
    "Once paired, Transmit starts the service when the user opens it. The service keeps the connection to Google Messages open.", PIX)
c2b = caption("STEP 2 · THE SERVICE STARTS", "An ongoing notification shows it's running",
    "While the service runs, its notification reads \"Connected to Google Messages · Receiving messages\".", PIX)
c3 = caption("STEP 3 · A TEXT ARRIVES", "A text arrives while Transmit is in the background",
    "The user leaves Transmit. A test text from a second phone reaches Google Messages, which relays it to Transmit over the open connection, and Transmit notifies right away. Without the service, it would wait until the user opened Transmit again.", PIX)

# (source, start, end, caption, tap(x, y, video time) or None)
SEGS = [
    ("pair", 1.5, 3.57, c1, (540, 1584, 3.5)), ("pair", 3.57, 7.0, c1, None), ("pair", 15.6, 21.2, c1, (540, 1059, 16.05)),
    ("phone", 0.6, 5.6, c2, (412, 2026, 2.08)), ("phone", 16.75, 18.0, c2b, None), ("phone", 21.0, 26.5, c2b, None),
    ("phone", 28.8, 31.6, c3, None), ("phone2", 3.4, 9.6, c3, None), ("phone2", 9.6, 15.6, c3, None),
]
SIZES = {"pair": (1080, 2400), "phone": (1080, 2424), "phone2": (1080, 2424)}
enc = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{FW}x{FH}", "-r", str(FPS), "-i", "-",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", "-movflags", "+faststart", OUT], stdin=subprocess.PIPE)
def emit(im, n):
    b = im.convert("RGB").tobytes()
    for _ in range(n): enc.stdin.write(b)

title = card("Transmit", "Foreground service: remote messaging",
    "Transmit is a client for the user's own Google Messages, like Messages for Web. A foreground service keeps its connection to Google Messages open, so new texts arrive right away.",
    "com.ermay12.transmit")
emit(title, int(4.5 * FPS))
layers = {}
class Src:
    def __init__(self, name):
        self.W, self.H = SIZES[name]; self.i = 0; self.fs = self.W * self.H * 3
        self.p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", f"{D}/{name}.mp4", "-vf", f"fps={FPS}",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    def frames(self, a, b):
        while True:
            t = self.i / FPS
            if t >= b: return
            buf = self.p.stdout.read(self.fs); self.i += 1
            if len(buf) < self.fs: return
            if t >= a: yield t, Image.frombytes("RGB", (self.W, self.H), buf)
srcs = {}
for src, a, b, cap, tap in SEGS:
    if src not in srcs: srcs[src] = Src(src)
    W, H = SIZES[src]; sw = round(SH * W / H) // 2 * 2
    if sw not in layers: layers[sw] = frame_layer(sw)
    base, (x0, y0), m = layers[sw]
    for t, img in srcs[src].frames(a, b):
        if src != "pair": img = private(img, src, t)
        if tap and tap[2] - 0.45 <= t <= tap[2] + 0.2:
            k = (t - tap[2] + 0.45) / 0.65
            ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
            r = 60 + 50 * k; x, y = tap[0], tap[1]
            d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, int(150 * (1 - k * 0.6))), outline=(63, 81, 232, 230), width=8)
            img = Image.alpha_composite(img.convert("RGBA"), ov).convert("RGB")
        fr = base.copy(); fr.paste(img.resize((sw, SH), Image.LANCZOS), (x0, y0), m)
        fr.paste(cap, (0, 0), cap)
        emit(fr, 1)
for p in srcs.values(): p.p.kill()
end = card("The service stops when the user unpairs", None,
    "It also stops if Google Messages signs Transmit out. Transmit has no push channel of its own: this connection is how texts arrive.",
    "Transmit · com.ermay12.transmit")
emit(end, int(5.0 * FPS))
enc.stdin.close(); enc.wait()
