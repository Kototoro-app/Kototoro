# Animated AVIF fixture

`two-frame.avif` contains two 32×32 frames (red and blue), each lasting 60 seconds.
The long duration makes manual frame advancement deterministic in the decoder/scene regression tests.
It was generated locally with Pillow; no external artwork is included.

```python
from PIL import Image

red = Image.new("RGB", (32, 32), "red")
blue = Image.new("RGB", (32, 32), "blue")
red.save(
    "two-frame.avif",
    format="AVIF",
    save_all=True,
    append_images=[blue],
    duration=60000,
    loop=0,
    quality=100,
)
```

`twelve-frame.avif` contains twelve 32×32 frames at 100 ms each, with alternating red/blue backgrounds
and a green square moving one pixel per frame. It exercises timed native playback, loop boundaries,
input lifetime after Coil closes the source, and a budget that fits only two RGB buffers.

```python
from PIL import Image, ImageDraw

frames = []
for index in range(12):
    frame = Image.new("RGB", (32, 32), (220, 30, 30) if index % 2 == 0 else (30, 30, 220))
    ImageDraw.Draw(frame).rectangle((index, 8, index + 8, 24), fill=(30, 220, 30))
    frames.append(frame)
frames[0].save(
    "twelve-frame.avif", format="AVIF", save_all=True, append_images=frames[1:],
    duration=100, loop=0, quality=80, speed=8,
)
```
