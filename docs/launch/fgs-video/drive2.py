import subprocess, time, json, re, sys, xml.etree.ElementTree as ET
ADB = ["/Users/ermay12/Library/Android/sdk/platform-tools/adb", "-s", "59041JEBF03350"]
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


assert fgs_text() == "Receiving messages", fgs_text()
sh("input keyevent 3"); time.sleep(1.0)
base = transmit_msg_updates()
sh("rm -f /sdcard/fgs.mp4")
rec = subprocess.Popen(ADB + ["shell", "date +%s.%N; exec screenrecord --bit-rate 10000000 --time-limit 180 /sdcard/fgs.mp4"], stdout=subprocess.PIPE, text=True)
LOG["events"]["rec_start"] = float(rec.stdout.readline().split()[0]); print("rec_start", flush=True)
LOG["events"]["home"] = LOG["events"]["rec_start"]
print("WAITING FOR TEXT", flush=True)
# 3. Wait for the test text: a new or updated Transmit message notification.
t0 = time.time(); upd = None
while time.time() - t0 < 170:
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
