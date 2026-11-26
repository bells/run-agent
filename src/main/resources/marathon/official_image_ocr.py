import csv
import io
import os
import subprocess
import sys

from PIL import Image, ImageOps


def scan(image, ratio, language, segmentation, padding=0):
    width, height = image.size
    resampling = getattr(Image, "Resampling", Image)
    image = image.resize((round(width * ratio), round(height * ratio)), resampling.LANCZOS)
    if padding:
        image = ImageOps.autocontrast(image).point(lambda pixel: 0 if pixel < 155 else 255)
        image = ImageOps.expand(image, border=padding, fill=255)
    data = io.BytesIO()
    image.save(data, format="PNG")
    result = subprocess.run(
        ["tesseract", "stdin", "stdout", "--dpi", "300", "-l", language, "--psm", segmentation, "tsv"],
        input=data.getvalue(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        check=True, env={**os.environ, "OMP_THREAD_LIMIT": "1"},
    )
    rows = csv.DictReader(io.StringIO(result.stdout.decode("utf-8")), delimiter="\t")
    for row in rows:
        if row["level"] != "5" or not row["text"].strip():
            continue
        x = round((int(row["left"]) + int(row["width"]) / 2 - padding) / ratio)
        y = round((int(row["top"]) + int(row["height"]) / 2 - padding) / ratio)
        height = round(int(row["height"]) / ratio)
        yield x, y, height, row["conf"], row["text"]


def relative_box(image, bounds):
    width, height = image.size
    left, top, right, bottom = bounds
    return round(left * width / 3542), round(top * height / 1290), \
        round(right * width / 3542), round(bottom * height / 1290)


def podium_words(image, domestic):
    width = image.width
    name_bounds = (1680, 625, 2180, 1230) if domestic else (1200, 575, 2580, 1250)
    names_box = relative_box(image, name_bounds)
    headers = [relative_box(image, bounds) for bounds in ((135, 393, 435, 585), (1715, 393, 2100, 585))]
    for x, y, size, confidence, text in scan(image, 0.4, "chi_sim+eng", "11"):
        position = x / width
        name_column = 0.30 <= position < 0.73
        header = headers[0][1] <= y < headers[0][3]
        if header and (position < 0.15 or name_column):
            continue
        if y < names_box[1] or not name_column:
            yield x, y, size, confidence, text

    # Tesseract 4 can miss light text in these two header cells unless the background is inverted.
    for box in headers:
        panel = image.crop(box)
        if panel.getpixel((0, 0)) < 128:
            panel = ImageOps.invert(panel)
        for x, y, size, confidence, text in scan(panel, 0.45, "chi_sim", "7", padding=12):
            yield x + box[0], y + box[1], size, confidence, text

    left, top, _, _ = names_box
    names = image.crop(names_box)
    for x, y, size, confidence, text in scan(names, 0.33, "chi_sim", "11", padding=12):
        yield x + left, y + top, size, confidence, text


with Image.open(sys.stdin.buffer) as crop:
    for word in podium_words(crop.convert("L"), sys.argv[1] == "DOMESTIC"):
        print(*word, sep="\t")
