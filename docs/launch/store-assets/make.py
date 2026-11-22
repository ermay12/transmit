import os, sys, subprocess, html
"""Renders the store graphics: the feature graphic and the captioned screenshots, from screenshots/raw/.

Run with a Python that has Pillow (macOS: /usr/bin/python3 make.py). Needs Google Chrome, Roboto from the Android
SDK, and the emulator's emoji font, so the mockup's emoji match the screenshots:
    adb -s emulator-5554 pull /system/fonts/NotoColorEmoji.ttf "$TMPDIR/"
"""
import tempfile
from PIL import Image
A = os.path.dirname(os.path.abspath(__file__))
R = A + '/screenshots/raw'
G = tempfile.mkdtemp(prefix='store-assets-')
os.makedirs(G + '/out'); os.makedirs(G + '/fonts')
SDK_FONTS = os.path.expanduser('~/Library/Android/sdk/platforms/android-26/data/fonts')
for f in ('Roboto-Regular.ttf', 'Roboto-Medium.ttf', 'Roboto-Bold.ttf'):
    os.symlink(f'{SDK_FONTS}/{f}', f'{G}/fonts/{f}')
os.symlink(os.path.join(os.environ.get('TMPDIR', '/tmp'), 'NotoColorEmoji.ttf'), f'{G}/fonts/NotoColorEmoji.ttf')
os.symlink(A + '/icon-512.png', G + '/icon-512.png')
# The "what you see" card: the chat's top bar and first messages, without the empty space above "Today".
_im = Image.open(R + '/01-chat.png').convert('RGB')
_top, _msgs = _im.crop((0, 72, 1080, 215)), _im.crop((0, 400, 1080, 985))
_card = Image.new('RGB', (1080, _top.height + _msgs.height)); _card.paste(_top, (0, 0)); _card.paste(_msgs, (0, _top.height))
_card.save(G + '/card-you.png')
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

FONTS = f'''
@font-face{{font-family:Roboto;font-weight:400;src:url(file://{G}/fonts/Roboto-Regular.ttf)}}
@font-face{{font-family:Roboto;font-weight:500;src:url(file://{G}/fonts/Roboto-Medium.ttf)}}
@font-face{{font-family:Roboto;font-weight:700;src:url(file://{G}/fonts/Roboto-Bold.ttf)}}
@font-face{{font-family:NotoEmoji;src:url(file://{G}/fonts/NotoColorEmoji.ttf)}}
'''
BASE = FONTS + '''
*{box-sizing:border-box}
html,body{margin:0}
body{width:1080px;height:1920px;overflow:hidden;position:relative;color:#fff;
  font-family:Roboto,NotoEmoji,sans-serif;
  background:linear-gradient(165deg,#5163F2 0%,#3F51E8 48%,#3241CF 100%)}
.head{position:absolute;left:0;right:0;top:0;display:flex;flex-direction:column;justify-content:center;
  align-items:center;text-align:center;padding:0 80px}
h1{margin:0;font-weight:700;font-size:72px;line-height:1.12;letter-spacing:-.5px;text-wrap:balance}
.sub{margin:26px 0 0;font-size:38px;line-height:1.32;opacity:.9;text-wrap:balance}
.phone{position:absolute;background:#15161c;border-radius:66px;padding:17px;
  box-shadow:0 34px 90px rgba(12,18,90,.5)}
.phone img{display:block;border-radius:50px}
'''

def page(name, body, css='', w=1080, h=1920):
    path = f'{G}/out/{name}.html'
    open(path, 'w').write(f'<!doctype html><html><head><meta charset="utf-8"><style>{BASE}{css}</style></head><body>{body}</body></html>')
    png = f'{G}/out/{name}.png'
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
                    '--force-device-scale-factor=1', f'--window-size={w},{h}', '--virtual-time-budget=3000',
                    f'--screenshot={png}', f'file://{path}'], check=True, capture_output=True)
    # Play takes 24-bit PNGs, without alpha.
    dest = f'{A}/feature-graphic-1024x500.png' if name == 'feature-graphic' else f'{A}/screenshots/captioned/{name}.png'
    Image.open(png).convert('RGB').save(dest, optimize=True)
    return dest

def one_phone(name, raw, title, sub=None):
    sw = 760; sh = round(sw * 1920 / 1080)          # screen 760 x 1351
    fw = sw + 34; fh = sh + 34
    top = 1920 - fh - 64
    subh = f'<p class="sub">{sub}</p>' if sub else ''
    body = f'''<div class="head" style="height:{top}px"><h1>{title}</h1>{subh}</div>
      <div class="phone" style="left:{(1080-fw)//2}px;top:{top}px"><img src="file://{R}/{raw}" width="{sw}" height="{sh}"></div>'''
    return page(name, body)

# ---- 1: what you see vs what they see -------------------------------------------------------
def you_vs_them():
    k = 900 / 1080   # the real screen's scale on the card
    px = lambda v: f'{v * k:.1f}px'
    css = f'''
    .label{{position:absolute;left:90px;font-size:32px;font-weight:500;letter-spacing:.3px;
      background:rgba(255,255,255,.16);padding:10px 26px;border-radius:999px}}
    .card{{position:absolute;left:90px;width:900px;border-radius:40px;overflow:hidden;
      box-shadow:0 26px 70px rgba(12,18,90,.45)}}
    .card img{{display:block}}
    .them{{background:#fff;color:#1b1c20;font-family:Roboto,NotoEmoji,sans-serif;padding-bottom:{px(26)}}}
    .bar{{height:{px(143)};display:flex;align-items:center;padding:0 {px(40)};gap:{px(30)};border-bottom:1px solid #eceef1}}
    .arrow{{font-size:{px(56)};line-height:1;color:#3a3d44}}
    .av{{width:{px(94)};height:{px(94)};border-radius:50%;background:#F08A5D;color:#fff;font-weight:500;
      font-size:{px(44)};display:flex;align-items:center;justify-content:center}}
    .name{{font-size:{px(40)};font-weight:700}}
    .msgs{{padding:{px(28)} {px(22)} 0;display:flex;flex-direction:column}}
    .b{{max-width:{px(735)};font-size:{px(41)};line-height:1.53;padding:{px(26)} {px(32)};border-radius:{px(44)};margin-bottom:{px(6)}}}
    .out{{align-self:flex-end;background:#2E323A;color:#fff}}
    .in{{align-self:flex-start;background:#EEF0F3}}
    .t{{font-size:{px(28)};color:#6b6f78;margin:{px(10)} {px(18)} {px(30)}}}
    .t.r{{align-self:flex-end}}
    .foot{{position:absolute;left:0;right:0;top:1822px;text-align:center;font-size:36px;opacity:.92}}
    '''
    body = f'''
    <div class="head" style="height:300px"><h1>You text in Spanish.<br>They see English.</h1></div>
    <div class="label" style="top:316px">What you see</div>
    <div class="card" style="top:384px"><img src="file://{G}/card-you.png" width="900"></div>
    <div class="label" style="top:1024px">What Kyle sees</div>
    <div class="card them" style="top:1092px">
      <div class="bar"><span class="arrow">&#8592;</span><div class="av">A</div><span class="name">Alex</span></div>
      <div class="msgs">
        <div class="b out">Are you free Saturday? My cousin is visiting and we're going to try the new tapas bar</div>
        <div class="b out">You should come! 🥘</div>
        <div class="t r">9:13 AM</div>
        <div class="b in">I'd love to! What time?</div>
        <div class="t">9:20 AM</div>
      </div>
    </div>
    <div class="foot">Kyle gets ordinary texts. No app needed.</div>'''
    return page('01-you-vs-them', body, css)

# ---- 7: a language per chat: two phones ------------------------------------------------------
def per_chat():
    sw = 640; sh = round(sw * 1920 / 1080); fw = sw + 34; fh = sh + 34
    body = f'''<div class="head" style="height:500px"><h1>Spanish with Kyle,<br>Japanese with Rachel</h1>
      <p class="sub">Pick a language for each chat</p></div>
      <div class="phone" style="left:44px;top:556px"><img src="file://{R}/01-chat.png" width="{sw}" height="{sh}"></div>
      <div class="phone" style="left:{1080-44-fw}px;top:646px;box-shadow:-24px 34px 90px rgba(12,18,90,.6)"><img src="file://{R}/07-rachel.png" width="{sw}" height="{sh}"></div>'''
    return page('07-per-chat', body)

# ---- feature graphic -------------------------------------------------------------------------
def feature():
    css = f'''
    body{{width:1024px;height:500px;background:linear-gradient(160deg,#5163F2 0%,#3F51E8 50%,#3241CF 100%)}}
    .mid{{position:absolute;left:262px;width:500px;top:0;bottom:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}}
    .mid img{{width:104px;height:104px;border-radius:24px;box-shadow:0 10px 28px rgba(10,15,80,.4)}}
    .mid .n{{font-size:64px;font-weight:700;margin-top:14px;letter-spacing:-.5px;line-height:1.1}}
    .mid .l{{font-size:27px;line-height:1.3;margin-top:12px;text-wrap:balance}}
    .mid .s{{font-size:20px;margin-top:10px;opacity:.85}}
    .col{{position:absolute;top:0;bottom:0;width:236px;display:flex;flex-direction:column;justify-content:center;gap:10px}}
    .col.l{{left:22px}} .col.r{{right:22px}}
    .cap{{font-size:17px;font-weight:500;letter-spacing:1.4px;text-transform:uppercase;opacity:.8;margin-bottom:4px}}
    .col.r .cap{{text-align:right}}
    .b{{font-size:19px;line-height:1.35;padding:11px 16px;border-radius:20px;max-width:226px}}
    .you-in{{align-self:flex-start;background:#E4E3EA;color:#1b1c20}}
    .you-out{{align-self:flex-end;background:#4A5B93;color:#fff}}
    .them-out{{align-self:flex-end;background:#2E323A;color:#fff}}
    .them-in{{align-self:flex-start;background:#fff;color:#1b1c20}}
    '''
    body = f'''
    <div class="col l"><div class="cap">You see</div>
      <div class="b you-in">¿Estás libre el sábado?</div><div class="b you-out">¡Me encantaría!</div></div>
    <div class="mid"><img src="file://{G}/icon-512.png"><div class="n">Transmit</div>
      <div class="l">Practice a language in your everyday texts</div>
      <div class="s">Your friends just see normal messages</div></div>
    <div class="col r"><div class="cap">They see</div>
      <div class="b them-out">Are you free Saturday?</div><div class="b them-in">I'd love to!</div></div>'''
    return page('feature-graphic', body, css, 1024, 500)

if __name__ == '__main__':
    outs = [
        you_vs_them(),
        one_phone('02-inbox', '02-inbox.png', 'Your real texts, SMS and RCS', "Same number, same chats. Your friends don't install anything."),
        one_phone('03-original', '03-original.png', 'Read every text in Spanish', 'Stuck? Tap to see what they actually wrote.'),
        one_phone('04-reply', '04-reply.png', 'Reply in Spanish', 'See the English Kyle will get before you send.'),
        one_phone('05-check', '05-check.png', 'Get corrected before you hit send', 'Fixes your mistakes and says why.'),
        one_phone('06-explain', '06-explain.png', 'Understand the grammar, idioms and slang', 'Tap Explain on any message.'),
        per_chat(),
        feature(),
    ]
    print('\n'.join(outs))
