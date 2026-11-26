"""Import an approved Chinese Athletics Association marathon directory."""

from datetime import date
from hashlib import sha256
from io import BytesIO
import re
import unicodedata

from sync_marathon_events import https_url, require

MAX_PDF_BYTES = 8 * 1024 * 1024
HEADER = ("序号", "省份", "赛事名称", "比赛时间", "主办单位", "赛事设项", "赛事类别")
EVENT_DAY = re.compile(r"([0-9]{1,2})月([0-9]{1,2})日")
DISTANCES = {"全程", "半程", "全程/半程"}


def cell(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", text or ""))


def parse_tables(tables, year, source, expected_rows):
    https_url(source)
    require(type(year) is int and 2000 <= year <= 2199, "Invalid catalog year")
    require(type(expected_rows) is int and 0 < expected_rows <= 2000, "Invalid expected directory row count")
    events = {}
    index = 0
    for table in tables:
        require(bool(table) and tuple(map(cell, table[0])) == HEADER, "Unexpected directory headers")
        for raw in table[1:]:
            require(len(raw) == len(HEADER), "Unexpected directory columns")
            number, province, name, date_hint, _, distance, division = map(cell, raw)
            index += 1
            require(number == str(index), "Missing or out-of-order directory row")
            require(bool(name and province and date_hint) and distance in DISTANCES and division in {"A", "B", "C"},
                    "Incomplete directory row")
            if distance == "半程" or division == "C":
                continue
            day = EVENT_DAY.fullmatch(date_hint)
            date_text = str(date(year, *map(int, day.groups()))) if day else None
            events[index] = {
                "directory_row": index, "event_id": f"caa_{index}_{year}", "name": name,
                "race_date": date_text, "date_hint": date_hint, "country": "CN", "province": province,
                "cert_level": division, "distance": "MARATHON", "state": "PLANNED", "edition_source": source,
            }
    require(index == expected_rows, "The directory did not contain the expected number of rows")
    return events


def read_directory(path, manifest):
    from pdfplumber import open as open_pdf

    with path.open("rb") as file:
        document = file.read(MAX_PDF_BYTES + 1)
    require(len(document) <= MAX_PDF_BYTES, "Directory PDF exceeds size limit")
    config = manifest["directory"]
    require(sha256(document).hexdigest() == config["sha256"], "Directory PDF differs from the approved edition")
    with open_pdf(BytesIO(document)) as pdf:
        require(1 <= len(pdf.pages) <= 120, "Invalid directory page count")
        tables = (table for page in pdf.pages for table in page.extract_tables())
        return parse_tables(tables, manifest["year"], manifest["catalog_source"], config["expected_rows"])


def merge_directory(manifest, directory):
    candidates = dict(directory)
    extra = []
    seen = set()
    for override in manifest["events"]:
        number = override.get("directory_row")
        if number is None:
            extra.append(override)
            continue
        require(number in candidates and number not in seen, "Missing or repeated directory edition override")
        row = candidates[number]
        require(row["province"] == override["province"] and row["cert_level"] == override["cert_level"],
                "Directory override does not match source province and class")
        candidates[number] = {**row, **override}
        seen.add(number)
    return {**manifest, "events": [*candidates.values(), *extra]}
