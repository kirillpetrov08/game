#!/usr/bin/env python3
"""Генерация звуков, музыки и иконок для игры «Орбита».
Запуск из корня репозитория: python3 tools/gen_assets.py
Зависимости: numpy, pillow.
"""
import os
import wave
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
RAW = os.path.join(RES, "raw")
SR = 22050
os.makedirs(RAW, exist_ok=True)


def save_wav(name, data):
    data = np.clip(data, -1.0, 1.0)
    pcm = (data * 32767).astype("<i2")
    with wave.open(os.path.join(RAW, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def t_axis(dur):
    return np.arange(int(SR * dur)) / SR


def env(n, attack=0.004, release=0.05):
    e = np.ones(n)
    a = max(1, int(SR * attack))
    r = max(1, int(SR * release))
    e[:a] = np.linspace(0, 1, a)
    e[-r:] = np.minimum(e[-r:], np.linspace(1, 0, r))
    return e


def tone(freq, dur, vol=0.5, decay=6.0, harm=(1.0, 0.3, 0.1)):
    t = t_axis(dur)
    s = np.zeros_like(t)
    for i, h in enumerate(harm):
        s += h * np.sin(2 * np.pi * freq * (i + 1) * t)
    s *= np.exp(-decay * t) * vol
    return s * env(len(t))


def sweep(f0, f1, dur, vol=0.5, decay=3.0):
    t = t_axis(dur)
    f = np.linspace(f0, f1, len(t))
    ph = 2 * np.pi * np.cumsum(f) / SR
    s = (np.sin(ph) + 0.25 * np.sin(2 * ph)) * np.exp(-decay * t) * vol
    return s * env(len(t))


def seq(parts):
    return np.concatenate(parts)


def mix(*arrs):
    n = max(len(a) for a in arrs)
    out = np.zeros(n)
    for a in arrs:
        out[: len(a)] += a
    return out


# ---------- эффекты ----------
save_wav("sfx_switch", sweep(520, 880, 0.09, 0.45, 18))
save_wav("sfx_click", tone(1400, 0.05, 0.4, 40, (1.0, 0.2)))
save_wav("sfx_crystal", mix(tone(988, 0.30, 0.38, 7), np.concatenate([np.zeros(int(SR * 0.07)), tone(1319, 0.30, 0.38, 7)]),
                          np.concatenate([np.zeros(int(SR * 0.14)), tone(1976, 0.25, 0.20, 9)])))
sh = sweep(300, 1300, 0.40, 0.4, 2.2)
vib = 1 + 0.15 * np.sin(2 * np.pi * 22 * t_axis(0.40))
save_wav("sfx_shield", sh * vib)
rng = np.random.default_rng(7)
noise = rng.standard_normal(int(SR * 0.5))
k = np.ones(40) / 40
noise = np.convolve(noise, k, mode="same")
tt = t_axis(0.5)
save_wav("sfx_hit", mix(noise * np.exp(-7 * tt) * 1.6, sweep(140, 38, 0.5, 0.7, 4.5)))
save_wav("sfx_over", seq([tone(f, 0.16, 0.45, 4.5) for f in (440, 349, 294)] + [tone(220, 0.5, 0.45, 3.0)]))
save_wav("sfx_levelup", seq([tone(f, 0.09, 0.4, 8) for f in (523, 659, 784)] + [tone(1047, 0.3, 0.4, 5)]))
save_wav("sfx_revive", mix(seq([tone(f, 0.10, 0.4, 7) for f in (392, 523, 659, 784)] + [tone(1047, 0.35, 0.4, 4)]),
                           sweep(200, 900, 0.7, 0.18, 3)))

# ---------- музыка (бесшовный луп) ----------
BPM = 96
beat = 60.0 / BPM
bar = beat * 4
BARS = 8
dur = bar * BARS
N = int(SR * dur)
tail = int(SR * 2.0)
buf = np.zeros(N + tail)


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12.0)


def add(start, arr):
    i = int(start * SR)
    j = min(len(buf), i + len(arr))
    buf[i:j] += arr[: j - i]


chords = [  # Am, F, C, G (по 2 такта)
    ([57, 60, 64], 45),
    ([53, 57, 60], 41),
    ([48, 52, 55], 48),
    ([55, 59, 62], 43),
]
for ci, (notes, bass) in enumerate(chords):
    start = ci * 2 * bar
    d = 2 * bar + 1.2
    t = t_axis(d)
    e = np.minimum(1, t / 0.9) * np.minimum(1, (d - t) / 1.2) * 0.5
    pad = np.zeros_like(t)
    for n in notes:
        for det in (-0.12, 0.0, 0.12):
            pad += np.sin(2 * np.pi * (midi(n) + det) * t) + 0.3 * np.sin(2 * np.pi * (midi(n) + det) * 2 * t)
    pad *= e * 0.045
    add(start, pad)
    # бас
    for b in range(2):
        bt = t_axis(bar * 0.98)
        be = np.exp(-0.9 * bt) * np.minimum(1, bt / 0.02)
        add(start + b * bar, np.sin(2 * np.pi * midi(bass) * bt) * be * 0.30)
    # арпеджио 8-ми
    pattern = [0, 1, 2, 1, 2, 1, 0, 2]
    for step in range(16):
        n = notes[pattern[step % 8]] + 12
        if step % 8 == 7 and (step // 8) % 2 == 1:
            n += 12
        st = start + step * (beat / 2)
        pl = tone(midi(n), 0.55, 0.16, 5.5, (1.0, 0.35, 0.12))
        add(st, pl)

# фолд хвоста в начало — бесшовность
loop = buf[:N].copy()
loop[: len(buf) - N] += buf[N:]
# лёгкое эхо
d1 = int(SR * beat * 0.75)
echo = np.zeros_like(loop)
echo[d1:] = loop[:-d1] * 0.28
echo += np.roll(loop, int(SR * beat * 1.5)) * 0.14
loop = loop + echo
loop = loop / (np.max(np.abs(loop)) + 1e-9) * 0.8
save_wav("music", loop)

# ---------- иконки ----------
S = 1024


def glow_layer(draw_fn, blur):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im))
    return im.filter(ImageFilter.GaussianBlur(blur))


def make_foreground():
    """Содержимое в безопасной зоне (центральные ~66%)."""
    cx = cy = S // 2
    base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    radii = [150, 235, 320]

    def rings(d):
        for r in radii:
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(60, 230, 240, 255), width=14)

    base = Image.alpha_composite(base, glow_layer(rings, 18))
    base = Image.alpha_composite(base, glow_layer(rings, 6))
    d = ImageDraw.Draw(base)
    rings(d)

    def barrier(dr):
        r = radii[1]
        dr.arc([cx - r, cy - r, cx + r, cy + r], start=200, end=285, fill=(255, 61, 113, 255), width=46)

    base = Image.alpha_composite(base, glow_layer(barrier, 26))
    d = ImageDraw.Draw(base)
    barrier(d)
    r = radii[1]
    d.arc([cx - r, cy - r, cx + r, cy + r], start=203, end=282, fill=(255, 190, 205, 255), width=12)

    # комета на внешней орбите
    import math
    ang = math.radians(-35)
    R = radii[2]
    hx, hy = cx + R * math.cos(ang), cy + R * math.sin(ang)

    def comet(dr):
        for i in range(40):
            a = ang - math.radians(i * 1.9)
            x, y = cx + R * math.cos(a), cy + R * math.sin(a)
            rad = 34 * (1 - i / 40.0)
            al = int(255 * (1 - i / 40.0) ** 1.4)
            dr.ellipse([x - rad, y - rad, x + rad, y + rad], fill=(80, 190, 255, al))

    base = Image.alpha_composite(base, glow_layer(comet, 22))
    cm = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    comet(ImageDraw.Draw(cm))
    base = Image.alpha_composite(base, cm)
    d = ImageDraw.Draw(base)
    d.ellipse([hx - 44, hy - 44, hx + 44, hy + 44], fill=(255, 255, 255, 255))
    base = Image.alpha_composite(base, glow_layer(lambda dr: dr.ellipse([hx - 60, hy - 60, hx + 60, hy + 60], fill=(120, 230, 255, 255)), 28))
    d = ImageDraw.Draw(base)
    d.ellipse([hx - 44, hy - 44, hx + 44, hy + 44], fill=(255, 255, 255, 255))

    # ядро
    def core(dr):
        dr.ellipse([cx - 70, cy - 70, cx + 70, cy + 70], fill=(255, 216, 77, 255))

    base = Image.alpha_composite(base, glow_layer(core, 40))
    d = ImageDraw.Draw(base)
    d.ellipse([cx - 62, cy - 62, cx + 62, cy + 62], fill=(255, 232, 140, 255))
    d.ellipse([cx - 28, cy - 28, cx + 28, cy + 28], fill=(255, 255, 255, 255))
    return base


def make_background():
    bg = Image.new("RGBA", (S, S), (7, 11, 37, 255))
    px = np.zeros((S, S, 4), dtype=np.uint8)
    yy, xx = np.mgrid[0:S, 0:S]
    d = np.sqrt((xx - S / 2) ** 2 + (yy - S / 2) ** 2) / (S * 0.72)
    d = np.clip(d, 0, 1)
    c0 = np.array([30, 24, 92])
    c1 = np.array([5, 7, 26])
    for k in range(3):
        px[..., k] = (c0[k] * (1 - d) + c1[k] * d).astype(np.uint8)
    px[..., 3] = 255
    bg = Image.fromarray(px, "RGBA")
    rng = np.random.default_rng(3)
    dr = ImageDraw.Draw(bg)
    for _ in range(70):
        x, y = rng.integers(0, S, 2)
        r = rng.integers(1, 4)
        dr.ellipse([x - r, y - r, x + r, y + r], fill=(220, 230, 255, int(rng.integers(90, 220))))
    return bg


fg = make_foreground()
bg = make_background()


def save_png(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


dens_launcher = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
dens_fg = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# foreground в «адаптивном» холсте: масштабируем содержимое до 66% и центрируем
fg_adaptive = Image.new("RGBA", (S, S), (0, 0, 0, 0))
small = fg.resize((int(S * 0.78), int(S * 0.78)), Image.LANCZOS)
fg_adaptive.paste(small, ((S - small.width) // 2, (S - small.height) // 2), small)

full = Image.alpha_composite(bg, fg.resize((S, S)))
mask_round = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask_round).ellipse([0, 0, S - 1, S - 1], fill=255)
mask_sq = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask_sq).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)

for dn, sz in dens_launcher.items():
    sq = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sq.paste(full, (0, 0), mask_sq)
    save_png(sq, os.path.join(RES, f"mipmap-{dn}", "ic_launcher.png"), sz)
    rd = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd.paste(full, (0, 0), mask_round)
    save_png(rd, os.path.join(RES, f"mipmap-{dn}", "ic_launcher_round.png"), sz)
for dn, sz in dens_fg.items():
    save_png(fg_adaptive, os.path.join(RES, f"mipmap-{dn}", "ic_launcher_foreground.png"), sz)

os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(RES, "mipmap-anydpi-v26", n), "w") as f:
        f.write(adaptive)

# иконка для RuStore (512x512, без прозрачности)
os.makedirs(os.path.join(ROOT, "store"), exist_ok=True)
Image.alpha_composite(bg, fg).convert("RGB").resize((512, 512), Image.LANCZOS).save(os.path.join(ROOT, "store", "icon_512.png"))
print("assets ok")
