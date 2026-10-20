"""Assemble watchface/build/gif/frame_*.png (from ScreenshotTest.renderGifFrames) into docs/img/demo.gif.

    ./gradlew :watchface:testDebugUnitTest --tests '*ScreenshotTest.renderGifFrames'
    python scripts/make_gif.py          # needs Pillow
"""
import glob
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
files = sorted(glob.glob(os.path.join(ROOT, "watchface/build/gif/frame_*.png")))
if not files:
    raise SystemExit("no frames: run the renderGifFrames test first")

S, BEZEL = 320, 4
W = S + 2 * BEZEL
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).ellipse((0, 0, S - 1, S - 1), fill=255)

frames = []
for f in files:
    canvas = Image.new("RGB", (W, W), (13, 15, 30))
    ImageDraw.Draw(canvas).ellipse((0, 0, W - 1, W - 1), fill=(43, 47, 74))
    canvas.paste(Image.open(f).convert("RGB"), (BEZEL, BEZEL), mask)
    frames.append(canvas)

# One palette built from frames across every scene, so every character keeps its colours.
sample = frames[::6]
cols = 6
mosaic = Image.new("RGB", (cols * W, ((len(sample) + cols - 1) // cols) * W))
for i, fr in enumerate(sample):
    mosaic.paste(fr, ((i % cols) * W, (i // cols) * W))
palette = mosaic.quantize(colors=256, method=Image.Quantize.MEDIANCUT)
q = [fr.quantize(palette=palette, dither=Image.Dither.FLOYDSTEINBERG) for fr in frames]

out = os.path.join(ROOT, "docs/img/demo.gif")
q[0].save(out, save_all=True, append_images=q[1:], duration=67, loop=0, optimize=True, disposal=1)
print(f"{out}: {len(q)} frames, {os.path.getsize(out) // 1024} KB")
