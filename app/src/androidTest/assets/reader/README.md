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
