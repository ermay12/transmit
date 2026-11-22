import json, subprocess, sys, numpy as np
n = sys.argv[1]; W, H = 54, 121
raw = subprocess.run(["ffmpeg","-v","error","-i",f"{n}.mp4","-vf",f"fps=30,scale={W}:{H},format=gray","-f","rawvideo","-"],capture_output=True).stdout
f = np.frombuffer(raw, np.uint8).reshape(-1, H, W).astype(np.int16)
d = np.abs(np.diff(f, axis=0)).mean(axis=(1, 2))
L = json.load(open(f"{n}.json")); e = L["events"]; r0 = e["rec_start"]
print("frames", len(f), "secs", len(f) / 30)
for k in sys.argv[2:]:
    t = e[k] - r0
    lo = int(max(0, (t - 0.5) * 30)); hi = int((t + 2.0) * 30)
    seg = d[lo:hi]; idx = [i for i, v in enumerate(seg) if v > 1.5]
    print(k, "event", round(t, 2), "first change", round((lo + idx[0] + 1) / 30, 2) if idx else None)
