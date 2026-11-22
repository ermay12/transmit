import os, subprocess, time, json, re, sys, xml.etree.ElementTree as ET
# adb from ANDROID_HOME; set ANDROID_SERIAL to pick the phone.
ADB = [os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")]
LOG = {"events": {}, "rects": {}}
def sh(cmd, check=False):
    return subprocess.run(ADB + ["shell", cmd], capture_output=True, text=True).stdout
def ev(name, cmd=None):
    out = sh(("date +%s.%N; " + cmd) if cmd else "date +%s.%N")
    LOG["events"][name] = float(out.split()[0]); print(name, flush=True)
def rows():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null")
    x = sh("cat /sdcard/ui.xml"); sh("rm /sdcard/ui.xml")
    root = ET.fromstring(x[x.index("<?xml"):] if "<?xml" in x else x)
    out = []
    for n in root.iter("node"):
        if not n.get("resource-id", "").endswith("expandableNotificationRow"): continue
        j = " ".join((d.get("text") or "") + " " + (d.get("content-desc") or "") for d in n.iter("node"))
        b = [int(v) for v in re.findall(r"\d+", n.get("bounds"))]
        tag = "fgs" if "Connected to Google Messages" in j else ("transmit" if "Transmit" in j else "other")
        out.append((tag, b))
    return out, root
def fgs_text():
    d = sh("dumpsys notification --noredact")
    i = d.find("id=2147483646")
    m = re.search(r"android.text=String \(([^)]*)\)", d[i:]) if i >= 0 else None
    return m.group(1) if m else None
def transmit_msg_updates():
    d = sh("dumpsys notification --noredact")
    res = {}
    for m in re.finditer(r"NotificationRecord\(0x\w+: pkg=com.ermay12.transmit .*?id=(-?\d+) ", d):
        if m.group(1) == "2147483646": continue
        u = re.search(r"mUpdateTimeMs=(\d+)", d[m.end():m.end() + 20000])
        res[m.group(1)] = int(u.group(1)) if u else 0
    return res

# 0. Stop Transmit so opening it visibly starts the service.
sh("am force-stop com.ermay12.transmit"); time.sleep(1)
assert fgs_text() is None, "service still running"
sh("input keyevent 3"); time.sleep(1.5)
base = transmit_msg_updates()
sh("rm -f /sdcard/fgs.mp4")
rec = subprocess.Popen(ADB + ["shell", "date +%s.%N; exec screenrecord --bit-rate 10000000 --time-limit 180 /sdcard/fgs.mp4"], stdout=subprocess.PIPE, text=True)
LOG["events"]["rec_start"] = float(rec.stdout.readline().split()[0]); print("rec_start", flush=True)
time.sleep(2.5)
# 1. Open Transmit from its home-screen icon.
ev("launch", "input tap 412 2026")
for _ in range(20):
    time.sleep(0.5)
    if fgs_text() == "Receiving messages": break
print("fgs:", fgs_text(), flush=True)
_, root = rows() if False else (None, None)
# Transmit's top bar: down to the Settings icon's bottom edge.
sh("uiautomator dump /sdcard/ui.xml >/dev/null"); x = sh("cat /sdcard/ui.xml"); sh("rm /sdcard/ui.xml")
r = ET.fromstring(x[x.index("<?xml"):])
for n in r.iter("node"):
    if n.get("content-desc") == "Settings":
        LOG["rects"]["topbar"] = [0, 0, 1080, [int(v) for v in re.findall(r"\d+", n.get("bounds"))][3] + 24]
time.sleep(1.0)
# 2. Pull down the shade and expand Transmit's ongoing notification.
ev("shade1", "cmd statusbar expand-notifications")
time.sleep(1.5)
rs, _ = rows()
f = [b for t, b in rs if t == "fgs"]
if f:
    b = f[0]; cy = (b[1] + b[3]) // 2
    sh(f"input swipe 540 {cy} 540 {min(cy + 300, 2380)} 400"); time.sleep(1.2)
    rs, _ = rows()
LOG["rects"]["shade1"] = [b for t, b in rs if t in ("fgs",)]
ev("shade1_settled"); time.sleep(3.5)
ev("collapse1", "cmd statusbar collapse"); time.sleep(0.8)
ev("home", "input keyevent 3")
print("WAITING FOR TEXT", flush=True)
# 3. Wait for the test text: a new or updated Transmit message notification.
t0 = time.time(); upd = None
while time.time() - t0 < 140:
    now = transmit_msg_updates()
    new = {k: v for k, v in now.items() if v > base.get(k, 0)}
    if new: upd = max(new.values()) / 1000.0; break
    time.sleep(0.5)
if upd is None:
    print("no text arrived", flush=True)
else:
    LOG["events"]["msg_posted"] = upd; print("msg", flush=True)
    rs, _ = rows()
    LOG["rects"]["headsup"] = [b for t, b in rs if t == "transmit"]
    ev("headsup_dumped")
    time.sleep(max(0.0, upd + 4.0 - float(sh("date +%s.%N"))))
    ev("shade2", "cmd statusbar expand-notifications"); time.sleep(1.5)
    rs, _ = rows()
    LOG["rects"]["shade2"] = [b for t, b in rs if t in ("fgs", "transmit")]
    ev("shade2_settled"); time.sleep(4.0)
    ev("collapse2", "cmd statusbar collapse"); time.sleep(1.5)
ev("rec_stop", "pkill -INT screenrecord"); time.sleep(2.5)
subprocess.run(ADB + ["pull", "/sdcard/fgs.mp4", sys.argv[1]], capture_output=True)
sh("rm -f /sdcard/fgs.mp4")
json.dump(LOG, open(sys.argv[2], "w"), indent=1)
print(json.dumps(LOG["rects"]), flush=True)
