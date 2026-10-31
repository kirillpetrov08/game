#!/usr/bin/env python3
"""Собирает скриншоты и иконку для карточки RuStore из «сырых» снимков эмулятора.

RuStore: PNG/JPG, до 10 скриншотов на тип устройства, до 3 МБ, сторона 320–3840 px,
рекомендуемое соотношение 9:16 (1080x1920). Иконка 512x512, без прозрачности.

Что делает скрипт с каждым снимком 540x1200 (эмулятор):
  * срезает строку состояния Android, рекламный баннер и навигационную панель;
  * апскейлит до ширины 1080 и добивает низ продолжением фона (без рамок и мусора);
  * для варианта «с подписью» добавляет короткий текст на градиентной подложке.

Запуск:  python3 tools/make_rustore_screens.py [папка_с_сырыми_снимками]
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "shots"
OUT = ROOT / "store" / "rustore"
FONT = ROOT / "app" / "src" / "main" / "assets" / "fonts" / "RussoOne-Regular.ttf"

W, H = 1080, 1920
STATUS_BAR = 44      # строка состояния Android в снимке 540x1200
BANNER_TOP = 978     # рекламный баннер начинается здесь (проверено на снимках CI)

# порядок карточек в магазине: файл -> подпись (None = без подписи)
SHOTS = [
    ("08_gate", "Облетай красные барьеры — опасность видна заранее"),
    ("11_powers", "Щит, магнит и замедление времени"),
    ("09_ring", "Кольца сжимаются — держи дистанцию"),
    ("05_play_hints", "Левая половина экрана — внутрь, правая — наружу"),
    ("10_meteors", "Метеоритные дожди и комбо до x5"),
    ("01_menu", None),
    ("07_skins", None),
    ("12_over", None),
]


def load(path: Path) -> Image.Image:
    im = Image.open(path).convert("RGB")
    return im


def crop_ui(im: Image.Image) -> Image.Image:
    """Убираем системные панели и рекламу: остаётся только игровой интерфейс."""
    h = im.height
    top = STATUS_BAR
    bottom = min(BANNER_TOP, h)
    return im.crop((0, top, im.width, bottom))


def fill_band(im: Image.Image, extra: int) -> Image.Image:
    """Продолжаем фон вниз: берём нижнюю полосу, растягиваем и сильно размываем."""
    band = im.crop((0, im.height - 90, im.width, im.height))
    band = band.resize((im.width, extra + 90), Image.LANCZOS)
    band = band.filter(ImageFilter.GaussianBlur(28))
    out = Image.new("RGB", (im.width, im.height + extra))
    out.paste(im, (0, 0))
    out.paste(band.crop((0, 90, im.width, extra + 90)), (0, im.height))
    return out


def to_store_size(im: Image.Image) -> Image.Image:
    im = crop_ui(im)
    scale = W / im.width
    im = im.resize((W, round(im.height * scale)), Image.LANCZOS)
    if im.height < H:
        im = fill_band(im, H - im.height)
    elif im.height > H:
        # режем низ (там только фон), верх с HUD не трогаем
        im = im.crop((0, 0, W, H))
    return im


def caption(im: Image.Image, text: str) -> Image.Image:
    """Подпись на градиентной подложке внизу снимка (не перекрывает игру)."""
    im = im.convert("RGB").copy()
    grad_h = 300
    overlay = Image.new("RGBA", (W, grad_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    for i in range(grad_h):
        a = int(215 * (i / grad_h) ** 1.15)
        d.line([(0, i), (W, i)], fill=(4, 6, 26, a))
    im.paste(overlay, (0, H - grad_h), overlay)

    d = ImageDraw.Draw(im)
    font = ImageFont.truetype(str(FONT), 46)
    # переносим по словам, максимум 2 строки
    words, lines, cur = text.split(), [], ""
    for w in words:
        probe = f"{cur} {w}".strip()
        if d.textlength(probe, font=font) <= W - 130:
            cur = probe
        else:
            lines.append(cur)
            cur = w
    lines.append(cur)
    y = H - 60 - 60 * len(lines)
    for line in lines:
        d.text((62, y), line, font=font, fill=(234, 240, 255))
        y += 60
    # акцентная линия слева
    d.rounded_rectangle([28, H - 60 - 60 * len(lines) + 4, 36, H - 46], 4, fill=(45, 226, 230))
    return im


def main() -> None:
    clean_dir = OUT / "screenshots"
    cap_dir = OUT / "screenshots-captioned"
    clean_dir.mkdir(parents=True, exist_ok=True)
    cap_dir.mkdir(parents=True, exist_ok=True)

    missing = []
    made = 0
    for i, (name, text) in enumerate(SHOTS, start=1):
        src = SRC / f"{name}.png"
        if not src.exists():
            missing.append(name)
            continue
        img = to_store_size(load(src))
        base = re.sub(r"^\d+_", "", name)  # 08_gate -> gate, 01_menu -> menu
        out_name = f"{i:02d}_{base}.png"
        img.save(clean_dir / out_name, optimize=True)
        made += 1
        if text:
            caption(img, text).save(cap_dir / out_name, optimize=True)

    # иконка: 512x512 без прозрачности (требование RuStore)
    icon_src = ROOT / "store" / "icon_512.png"
    if icon_src.exists():
        icon = Image.open(icon_src).convert("RGB").resize((512, 512), Image.LANCZOS)
        icon.save(OUT / "icon_512.png", optimize=True)

    print(f"готово: {made} скриншотов -> {clean_dir}")
    if missing:
        print("нет исходников (пропущены):", ", ".join(missing))


if __name__ == "__main__":
    main()
