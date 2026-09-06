# -*- coding: utf-8 -*-
"""
生成 聆 LISN 应用图标:
- legacy 启动器图标 ic_launcher.png / ic_launcher_round.png(mdpi~xxxhdpi)
- 自适应图标背景/前景 PNG(drawable-nodpi)
风格:深空 aurora 渐变 + 白色玻璃感双音符 + 声波弧线(与应用主题一致)
"""
from PIL import Image, ImageDraw, ImageFilter
import os

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res")

BG_DEEP = (7, 9, 15)
INDIGO = (94, 92, 230)
CYAN = (100, 210, 255)
PINK = (255, 45, 85)
WHITE = (255, 255, 255)


def aurora_background(size: int) -> Image.Image:
    """深空底 + aurora 光斑(全出血,供自适应背景与 legacy 底图)"""
    img = Image.new("RGBA", (size, size), BG_DEEP + (255,))
    blob = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(blob)
    s = size / 1080.0
    d.ellipse([180*s-380*s, 140*s-380*s, 180*s+380*s, 140*s+380*s], fill=INDIGO + (200,))
    d.ellipse([900*s-330*s, 400*s-330*s, 900*s+330*s, 400*s+330*s], fill=CYAN + (150,))
    d.ellipse([680*s-380*s, 950*s-380*s, 680*s+380*s, 950*s+380*s], fill=PINK + (140,))
    d.ellipse([120*s-260*s, 880*s-260*s, 120*s+260*s, 880*s+260*s], fill=(125, 123, 255, 120))
    blob = blob.filter(ImageFilter.GaussianBlur(int(150 * s)))
    img = Image.alpha_composite(img, blob)
    # 中央轻微提亮,营造玻璃景深
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([size*0.18, size*0.10, size*0.82, size*0.72], fill=(255, 255, 255, 26))
    glow = glow.filter(ImageFilter.GaussianBlur(int(120 * s)))
    return Image.alpha_composite(img, glow)


def draw_note_art(size: int) -> Image.Image:
    """白色双音符 + 声波弧线(透明底,居中,占画布约 58%)"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    s = size / 1080.0

    def E(cx, cy, rx, ry): return [cx*s-rx*s, cy*s-ry*s, cx*s+rx*s, cy*s+ry*s]

    # ---- 音符(先画到独立层,便于加辉光)----
    note = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    nd = ImageDraw.Draw(note)
    # 音头(两个椭圆)
    nd.ellipse(E(355, 755, 78, 60), fill=WHITE + (245,))
    nd.ellipse(E(610, 715, 78, 60), fill=WHITE + (245,))
    # 音杆
    nd.rectangle([415*s, 395*s, 447*s, 770*s], fill=WHITE + (245,))
    nd.rectangle([670*s, 355*s, 702*s, 730*s], fill=WHITE + (245,))
    # 连音梁(斜切平行四边形)
    nd.polygon([(415*s, 330*s), (702*s, 288*s), (702*s, 362*s), (415*s, 404*s)], fill=WHITE + (245,))

    # 辉光(紫色调)
    glow = note.filter(ImageFilter.GaussianBlur(int(26 * s)))
    tint = Image.new("RGBA", (size, size), INDIGO + (0,))
    tint.putalpha(glow.split()[3].point(lambda a: int(a * 0.9)))
    layer = Image.alpha_composite(layer, tint)
    layer = Image.alpha_composite(layer, note)

    # ---- 声波弧线(右上,青/粉)----
    waves = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    wd = ImageDraw.Draw(waves)
    wglow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    wgd = ImageDraw.Draw(wglow)
    for radius, color, width in [(120, CYAN, 22), (205, PINK, 22)]:
        box = [760*s-radius*s, 300*s-radius*s, 760*s+radius*s, 300*s+radius*s]
        wd.arc(box, start=-68, end=14, fill=color + (235,), width=width)
        wgd.arc([box[0]-6*s, box[1]-6*s, box[2]+6*s, box[3]+6*s], start=-68, end=14,
                fill=color + (110,), width=width+10)
    wglow = wglow.filter(ImageFilter.GaussianBlur(int(10 * s)))
    layer = Image.alpha_composite(layer, wglow)
    layer = Image.alpha_composite(layer, waves)
    return layer


def glass_edge(img: Image.Image) -> Image.Image:
    """内高光描边(玻璃质感)"""
    size = img.size[0]
    s = size / 1080.0
    overlay = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rounded_rectangle([6*s, 6*s, size-6*s, size-6*s], radius=int(230 * s),
                         outline=(255, 255, 255, 60), width=int(5 * s))
    return Image.alpha_composite(img, overlay)


def rounded_mask(img: Image.Image, radius_ratio: float = 0.215) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, size, size], radius=int(size * radius_ratio), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def circle_mask(img: Image.Image) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([0, 0, size, size], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def save_res(img: Image.Image, rel: str, out_size: int):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((out_size, out_size), Image.LANCZOS).convert("RGBA").save(path, "PNG")
    print("wrote", rel, out_size)


def main():
    master_bg = aurora_background(1080)                       # 全出血背景
    art = draw_note_art(1080)                                 # 透明音符层

    # ---- legacy 启动器图标(圆角方 / 圆)----
    legacy = Image.alpha_composite(master_bg, art)
    legacy = glass_edge(legacy)
    rounded = rounded_mask(legacy)
    circled = circle_mask(legacy)

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in densities.items():
        save_res(rounded, f"mipmap-{dpi}/ic_launcher.png", px)
        save_res(circled, f"mipmap-{dpi}/ic_launcher_round.png", px)

    # ---- 自适应图标:背景(全出血 aurora)----
    save_res(master_bg, "drawable-nodpi/ic_launcher_background.png", 432)

    # ---- 自适应图标:前景(仅音符,收进中央 66% 安全区)----
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    bbox = art.getbbox()
    art_cropped = art.crop(bbox)
    target = int(432 * 0.64)  # 安全区约 66%,留一点余量
    ratio = min(target / art_cropped.width, target / art_cropped.height)
    art_scaled = art_cropped.resize((int(art_cropped.width * ratio), int(art_cropped.height * ratio)), Image.LANCZOS)
    fg.paste(art_scaled, ((432 - art_scaled.width) // 2, (432 - art_scaled.height) // 2), art_scaled)
    save_res(fg, "drawable-nodpi/ic_launcher_foreground.png", 432)

    # ---- 单色层(monochrome,Android 13+ 主题图标):纯白音符 ----
    mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    white_art = Image.new("RGBA", art_cropped.size, (0, 0, 0, 0))
    alpha = art_cropped.split()[3]
    white_art.paste((255, 255, 255, 255), (0, 0), alpha)
    ws = white_art.resize((art_scaled.width, art_scaled.height), Image.LANCZOS)
    mono.paste(ws, ((432 - ws.width) // 2, (432 - ws.height) // 2), ws)
    save_res(mono, "drawable-nodpi/ic_launcher_mono.png", 432)


if __name__ == "__main__":
    main()
