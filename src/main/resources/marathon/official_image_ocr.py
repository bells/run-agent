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


def podium_words(image, domestic):
    width, height = image.size
    bounds = (1680, 625, 2180, 1230) if domestic else (1200, 575, 2580, 1250)
    left, top, right, bottom = (round(bounds[0] * width / 3542), round(bounds[1] * height / 1290),
                                round(bounds[2] * width / 3542), round(bounds[3] * height / 1290))
    # Rank and gun time are read from the complete table. A separate pass keeps the
    # Latin model from mistaking short Chinese names for English words.
    for x, y, size, confidence, text in scan(image, 0.4, "chi_sim+eng", "11"):
        if y < top or not 0.30 <= x / width < 0.73:
            yield x, y, size, confidence, text
    names = image.crop((left, top, right, bottom))
    name_scan = scan(names, 0.33, "chi_sim", "11", padding=12) if domestic else scan(names, 0.4, "chi_sim+eng", "6")
    for x, y, size, confidence, text in name_scan:
        yield x + left, y + top, size, confidence, text


with Image.open(sys.stdin.buffer) as crop:
    for word in podium_words(crop.convert("L"), sys.argv[1] == "DOMESTIC"):
        print(*word, sep="\t")
