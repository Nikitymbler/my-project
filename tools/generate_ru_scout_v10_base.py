import json
import struct
import zlib
import uuid
from pathlib import Path

OUT = Path(r"D:\Minecraft\ArenaOfNations\mnt\data")
OUT.mkdir(parents=True, exist_ok=True)
PNG_NAME = "ru_scout_medieval_v10_base.png"
BB_NAME = "ru_scout_medieval_v10_base.bbmodel"
png_path = OUT / PNG_NAME
bb_path = OUT / BB_NAME

MASK = (18, 18, 22, 255)
HOOD = (28, 45, 95, 255)
EYE = (90, 170, 230, 255)
EYE_DARK = (35, 70, 110, 255)
TUNIC = (48, 102, 58, 255)
TUNIC_DARK = (34, 78, 44, 255)
CLOAK = (150, 38, 38, 255)
CLOAK_DARK = (110, 28, 28, 255)
BELT = (92, 58, 32, 255)
BOOT = (72, 44, 24, 255)
BOOT_DARK = (52, 32, 16, 255)
LEG = (42, 88, 50, 255)
TRANSPARENT = (0, 0, 0, 0)

W = H = 64
px = [list(TRANSPARENT) for _ in range(W * H)]


def setp(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y * W + x] = list(c)


def fill(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            setp(x, y, c)


# Head / balaclava
fill(8, 0, 16, 8, MASK)
fill(16, 0, 24, 8, MASK)
fill(0, 8, 8, 16, MASK)
fill(8, 8, 16, 16, MASK)
fill(16, 8, 24, 16, MASK)
fill(24, 8, 32, 16, MASK)

setp(10, 12, EYE)
setp(11, 12, EYE)
setp(10, 13, EYE_DARK)
setp(11, 13, EYE)
setp(13, 12, EYE)
setp(14, 12, EYE)
setp(13, 13, EYE)
setp(14, 13, EYE_DARK)

# Hood
fill(40, 0, 48, 8, HOOD)
fill(48, 0, 56, 8, HOOD)
fill(32, 8, 40, 16, HOOD)
fill(40, 8, 48, 16, HOOD)
fill(42, 10, 46, 15, TRANSPARENT)
fill(48, 8, 56, 16, HOOD)
fill(56, 8, 64, 16, HOOD)

# Body tunic
fill(20, 16, 28, 20, TUNIC_DARK)
fill(28, 16, 36, 20, TUNIC_DARK)
fill(16, 20, 20, 32, TUNIC)
fill(20, 20, 28, 32, TUNIC)
fill(28, 20, 32, 32, TUNIC)
fill(32, 20, 40, 32, TUNIC_DARK)
for y in range(21, 31):
    setp(24, y, TUNIC_DARK)

# Arms
fill(40, 16, 44, 20, TUNIC_DARK)
fill(44, 16, 48, 20, TUNIC_DARK)
fill(40, 20, 44, 32, TUNIC)
fill(44, 20, 48, 32, TUNIC)
fill(48, 20, 52, 32, TUNIC)
fill(52, 20, 56, 32, TUNIC_DARK)

fill(40, 32, 44, 36, TUNIC_DARK)
fill(44, 32, 48, 36, TUNIC_DARK)
fill(40, 36, 44, 48, TUNIC)
fill(44, 36, 48, 48, TUNIC)
fill(48, 36, 52, 48, TUNIC)
fill(52, 36, 56, 48, TUNIC_DARK)

# Legs + boots
fill(0, 16, 4, 20, LEG)
fill(4, 16, 8, 20, BOOT_DARK)
fill(0, 20, 4, 32, LEG)
fill(4, 20, 8, 32, LEG)
fill(8, 20, 12, 32, LEG)
fill(12, 20, 16, 32, LEG)
for x0 in (0, 4, 8, 12):
    fill(x0, 26, x0 + 4, 32, BOOT)
fill(4, 16, 8, 20, BOOT_DARK)

fill(0, 32, 4, 36, LEG)
fill(4, 32, 8, 36, BOOT_DARK)
fill(0, 36, 4, 48, LEG)
fill(4, 36, 8, 48, LEG)
fill(8, 36, 12, 48, LEG)
fill(12, 36, 16, 48, LEG)
for x0 in (0, 4, 8, 12):
    fill(x0, 42, x0 + 4, 48, BOOT)

# Belt
fill(16, 32, 40, 36, BELT)
fill(20, 33, 36, 35, (110, 72, 40, 255))

# Cloak
fill(0, 48, 20, 64, CLOAK)
fill(0, 48, 20, 50, CLOAK_DARK)
fill(0, 62, 20, 64, CLOAK_DARK)
for y in range(50, 62):
    setp(0, y, CLOAK_DARK)
    setp(19, y, CLOAK_DARK)

fill(20, 48, 36, 56, HOOD)
fill(20, 56, 36, 64, HOOD)


def write_png(path, width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            r, g, b, a = pixels[y * width + x]
            raw.extend((r, g, b, a))

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    data = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(data)


write_png(png_path, W, H, px)


def uid():
    return str(uuid.uuid4())


tex_uuid = uid()


def cube(name, from_, to, origin, faces):
    return {
        "name": name,
        "rescale": False,
        "locked": False,
        "from": from_,
        "to": to,
        "autouv": 0,
        "color": 0,
        "origin": origin,
        "faces": faces,
        "type": "cube",
        "uuid": uid(),
    }


def face(uv):
    return {"uv": uv, "texture": 0}


def face_null():
    return {"uv": [0, 0, 0, 0], "texture": None}


elements = [
    cube(
        "head",
        [-4, 24, -4],
        [4, 32, 4],
        [0, 24, 0],
        {
            "north": face([8, 8, 16, 16]),
            "east": face([0, 8, 8, 16]),
            "south": face([24, 8, 32, 16]),
            "west": face([16, 8, 24, 16]),
            "up": face([8, 0, 16, 8]),
            "down": face([16, 0, 24, 8]),
        },
    ),
    cube(
        "hood",
        [-4.6, 23.8, -4.6],
        [4.6, 32.6, 4.6],
        [0, 24, 0],
        {
            "north": face_null(),
            "east": face([32, 8, 40, 16]),
            "south": face([56, 8, 64, 16]),
            "west": face([48, 8, 56, 16]),
            "up": face([40, 0, 48, 8]),
            "down": face_null(),
        },
    ),
    cube(
        "hood_drape",
        [-5, 22.5, -3.2],
        [5, 25.2, 3.5],
        [0, 24, 0],
        {
            "north": face([20, 48, 30, 51]),
            "east": face([20, 51, 26, 54]),
            "south": face([20, 54, 30, 57]),
            "west": face([26, 51, 32, 54]),
            "up": face([20, 57, 30, 61]),
            "down": face([20, 61, 30, 64]),
        },
    ),
    cube(
        "body",
        [-4, 12, -2],
        [4, 24, 2],
        [0, 24, 0],
        {
            "north": face([20, 20, 28, 32]),
            "east": face([16, 20, 20, 32]),
            "south": face([32, 20, 40, 32]),
            "west": face([28, 20, 32, 32]),
            "up": face([20, 16, 28, 20]),
            "down": face([28, 16, 36, 20]),
        },
    ),
    cube(
        "belt",
        [-4.15, 12.2, -2.2],
        [4.15, 14.0, 2.2],
        [0, 24, 0],
        {
            "north": face([16, 32, 24, 34]),
            "east": face([24, 32, 28, 34]),
            "south": face([28, 32, 36, 34]),
            "west": face([36, 32, 40, 34]),
            "up": face([16, 34, 24, 36]),
            "down": face([24, 34, 32, 36]),
        },
    ),
    cube(
        "right_arm",
        [4, 12, -2],
        [8, 24, 2],
        [5, 22, 0],
        {
            "north": face([44, 20, 48, 32]),
            "east": face([40, 20, 44, 32]),
            "south": face([52, 20, 56, 32]),
            "west": face([48, 20, 52, 32]),
            "up": face([44, 16, 48, 20]),
            "down": face([48, 16, 52, 20]),
        },
    ),
    cube(
        "left_arm",
        [-8, 12, -2],
        [-4, 24, 2],
        [-5, 22, 0],
        {
            "north": face([44, 36, 48, 48]),
            "east": face([40, 36, 44, 48]),
            "south": face([52, 36, 56, 48]),
            "west": face([48, 36, 52, 48]),
            "up": face([44, 32, 48, 36]),
            "down": face([48, 32, 52, 36]),
        },
    ),
    cube(
        "right_leg",
        [0, 0, -2],
        [4, 12, 2],
        [2, 12, 0],
        {
            "north": face([4, 20, 8, 32]),
            "east": face([0, 20, 4, 32]),
            "south": face([12, 20, 16, 32]),
            "west": face([8, 20, 12, 32]),
            "up": face([4, 16, 8, 20]),
            "down": face([8, 16, 12, 20]),
        },
    ),
    cube(
        "left_leg",
        [-4, 0, -2],
        [0, 12, 2],
        [-2, 12, 0],
        {
            "north": face([4, 36, 8, 48]),
            "east": face([0, 36, 4, 48]),
            "south": face([12, 36, 16, 48]),
            "west": face([8, 36, 12, 48]),
            "up": face([4, 32, 8, 36]),
            "down": face([8, 32, 12, 36]),
        },
    ),
    cube(
        "cloak",
        [-4.5, 10.5, 2.0],
        [4.5, 24.5, 3.1],
        [0, 24, 0],
        {
            "north": face([1, 50, 10, 62]),
            "east": face([10, 50, 11, 62]),
            "south": face([1, 50, 10, 62]),
            "west": face([11, 50, 12, 62]),
            "up": face([1, 48, 10, 49]),
            "down": face([1, 62, 10, 63]),
        },
    ),
]


def group(name, children, origin):
    return {
        "name": name,
        "origin": origin,
        "color": 0,
        "uuid": uid(),
        "export": True,
        "isOpen": True,
        "locked": False,
        "visibility": True,
        "autouv": 0,
        "selected": False,
        "children": children,
    }


by_name = {e["name"]: e["uuid"] for e in elements}
outliner = [
    group("head", [by_name["head"], by_name["hood"], by_name["hood_drape"]], [0, 24, 0]),
    group("body", [by_name["body"], by_name["belt"], by_name["cloak"]], [0, 24, 0]),
    group("right_arm", [by_name["right_arm"]], [5, 22, 0]),
    group("left_arm", [by_name["left_arm"]], [-5, 22, 0]),
    group("right_leg", [by_name["right_leg"]], [2, 12, 0]),
    group("left_leg", [by_name["left_leg"]], [-2, 12, 0]),
]

model = {
    "meta": {
        "format_version": "4.10",
        "model_format": "modded_entity",
        "box_uv": False,
    },
    "name": "ru_scout_medieval_v10_base",
    "model_identifier": "",
    "visibility": True,
    "resolution": {"width": 64, "height": 64},
    "elements": elements,
    "outliner": outliner,
    "textures": [
        {
            "path": str(png_path).replace("\\", "/"),
            "name": PNG_NAME,
            "folder": "",
            "namespace": "",
            "id": "0",
            "particle": True,
            "render_mode": "default",
            "visible": True,
            "mode": "bitmap",
            "saved": True,
            "uuid": tex_uuid,
            "relative_path": "./" + PNG_NAME,
        }
    ],
    "animations": [],
    "animation_files": [],
    "timeline_setups": [],
    "embedded_animations": True,
}

bb_path.write_text(json.dumps(model, indent="\t"), encoding="utf-8")

model_dir = Path(r"D:\Minecraft\ArenaOfNations\model")
model_dir.mkdir(exist_ok=True)
(model_dir / PNG_NAME).write_bytes(png_path.read_bytes())
(model_dir / BB_NAME).write_text(bb_path.read_text(encoding="utf-8"), encoding="utf-8")

print("OK", png_path, png_path.stat().st_size)
print("OK", bb_path, bb_path.stat().st_size)
print("elements", len(elements))
