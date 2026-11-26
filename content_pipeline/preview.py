"""Render reviewable facts and local assets into WeChat-friendly HTML."""

from hashlib import sha256
from html import escape
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

CELL = "padding:9px 5px;border-bottom:1px solid #dce4ec;vertical-align:top;"
BOX = "background:#eff6ff;border-left:4px solid #0369a1;padding:12px;margin:14px 0;"


def source_link(source):
    url = source["url"]
    target = urlsplit(url)
    if (not isinstance(url, str) or any(char.isspace() for char in url) or target.scheme != "https"
            or not target.hostname or target.username is not None or target.password is not None
            or target.port not in (None, 443)):
        raise ValueError("Editorial sources must be HTTPS URLs without embedded credentials")
    return f'<a style="color:#075985;" href="{escape(url, quote=True)}">{escape(source["organization"])}</a>'


def table(headers, rows):
    headings = "".join(f'<th style="{CELL}text-align:left;">{escape(header)}</th>' for header in headers)
    body = "".join("<tr>" + "".join(f'<td style="{CELL}">{cell}</td>' for cell in row) + "</tr>" for row in rows)
    return f'<table style="border-collapse:collapse;width:100%;font-size:15px;"><thead><tr>{headings}</tr></thead><tbody>{body}</tbody></table>'


def pace_text(seconds):
    minutes, whole_seconds = divmod(round(seconds), 60)
    return f"{minutes}:{whole_seconds:02d} /km"


def write_race(facts):
    rows = facts["results"]
    contents = []
    for row in rows:
        project = "全马" if row["distance_m"] == 42195 else "半马"
        scope = "中国籍" if row["sub_category"] == "DOMESTIC" else "总榜"
        category = "男子" if row["category"] == "MEN" else "女子"
        contents.append([escape(row["race_date"]), escape(row["name"]), escape(row["athlete"]),
                         f'{project}{category}{scope}第 {row["rank"]} 名', escape(row["finish_time"]),
                         pace_text(row["pace_s"]), source_link(row["source"])])
    title = facts["subject"] + "的官方成绩与配速拆解"
    return title, (
        f'<p style="{BOX}">首条记录为 {escape(rows[0]["race_date"])}，'
        f'{escape(rows[0]["athlete"])}用时 {escape(rows[0]["finish_time"])}，'
        f'均配约 {pace_text(rows[0]["pace_s"])}。</p>'
        '<h2>战况速览</h2>'
        + table(["日期", "赛事", "跑者", "组别名次", "用时", "均配", "出处"], contents) +
        '<h2>怎么看配速</h2><p>均配由官方用时除以项目距离得出。分段成绩可以进一步显示'
        '前后半程的节奏，这份事实清单当前以官方完赛用时为准。</p>'
        '<h2>普通跑者怎么用</h2><p>复盘时可以把平均配速与自己的每公里配速放在一起看，'
        '记录后程何时开始掉速，以及当时的补给和体感。连续参赛时，把恢复情况放进下一场的安排。</p>'
    )


def write_training(facts):
    def metric(value, unit):
        return "数据不足" if value is None else f"{value:g}{unit}"

    distance = facts["distance_m"] / 1000
    chart = '<img src="training.png" alt="整公里配速与心率" style="width:100%;display:block;height:auto;" />'
    return "后半程为什么掉速？从一条训练记录拆解", (
        '<p>均配只能概括整次训练。比较每公里配速和心率，更容易看见节奏从哪一段开始变化。</p>'
        f'<p style="{BOX}">记录里程 {distance:g} km，均配 {pace_text(facts["average_pace_s"])}，'
        f'平均心率 {metric(facts["mean_hr"], " bpm")}。</p>'
        '<h2>训练曲线</h2>' + (chart if facts["kilometers"] else '<p>距离不足一个整公里。</p>') +
        f'<p>效率因子 EF {metric(facts["efficiency"], "")}，'
        f'Pa:Hr {metric(facts["drift_pct"], "%")}，'
        f'末个整公里相对首公里的配速衰减 {metric(facts["pace_fade_pct"], "%")}。</p>'
        '<h2>如何解读</h2><p>EF 用每分钟速度除以区间时间加权心率。Pa:Hr 比较前后两个等时段的 EF，'
        '正值表示后段效率降低。地形、风、气温、配速变化和停顿也会影响这项指标。</p>'
        '<h2>下次怎么跑</h2><p>下次轻松长跑可选择相似路线，尽量稳定配速，记下补给和体感，'
        '再用相同口径复盘。</p>'
    )


def write_calendar(facts):
    rows = []
    for item in facts["events"]:
        days = item["registration_days"]
        deadline = "待公布" if days is None else (
            f'{item["registration_deadline"]} · {"已过截止日" if days < 0 else f"剩 {days} 天"}')
        distances = "、".join("全马" if value == "MARATHON" else "半马" for value in item["distances"])
        status = "已确认" if item["status"] == "CONFIRMED" else "计划中"
        rows.append([escape(item["race_date"]), escape(item["name"]), escape(item["city"]), distances,
                     escape(item["cert_level"] or "待公布"), status, escape(deadline), source_link(item["source"])])
    contents = table(["日期", "赛事", "城市", "项目", "级别", "状态", "报名截止", "来源"], rows)
    if not rows:
        contents = "<p>当前赛历没有符合条件的场次。</p>"
    return f'未来 {facts["window_days"]} 天马拉松赛历', (
        f'<p style="{BOX}">赛历更新基准日 {facts["as_of"]}。报名前请核对对应赛事公告。</p>'
        + contents + '<h2>出发前</h2><p>开跑前核对赛道图、补给站和当地天气，再决定装备和配速。</p>'
    )


def write_nutrition(facts):
    claims = "".join(f'<li>{escape(item["text"])} · {source_link(item["source"])}</li>'
                     for item in facts["claims"])
    return facts["subject"], (
        f'<p style="{BOX}">{escape(facts["season"])} · {escape(facts["phase"])}</p>'
        '<h2>补给怎么安排</h2><ul>' + claims + '</ul>'
        '<p>可以先在常规训练里试食物和补给，记录吃的时间、体感与胃肠反应。</p>'
    )


WRITERS = {"race_news_athlete": write_race, "data_tutorial": write_training,
           "event_preview": write_calendar, "nutrition_guide": write_nutrition}


def training_chart(kilometers, destination):
    from matplotlib.backends.backend_agg import FigureCanvasAgg
    from matplotlib.figure import Figure

    with_hr = any(row["heart_rate"] is not None for row in kilometers)
    figure = Figure(figsize=(6, 5.5 if with_hr else 3.4), dpi=180, facecolor="white")
    FigureCanvasAgg(figure)
    axes = figure.subplots(2 if with_hr else 1, sharex=True, squeeze=False).ravel()
    x = [row["km"] for row in kilometers]
    keys = [("pace_s", "min/km", "#0369a1")]
    if with_hr:
        keys.append(("heart_rate", "bpm", "#b45309"))
    for axis, (key, label, color) in zip(axes, keys):
        y = [row[key] / 60 if key == "pace_s" else row[key] for row in kilometers]
        axis.plot(x, y, color=color, linewidth=2.5, marker="o", markersize=4)
        axis.set_ylabel(label, fontsize=13)
        axis.tick_params(labelsize=11)
        axis.spines[["right", "top"]].set_visible(False)
        axis.grid(axis="y", color="#e2e8f0")
    axes[-1].set_xlabel("km", fontsize=13)
    figure.tight_layout()
    figure.savefig(destination)


def export_preview(kind, facts, output):
    title, content = WRITERS[kind](facts)
    if facts.get("synthetic"):
        content = f'<p style="{BOX}">合成训练数据演示</p>' + content
    body = (
        '<section style="margin:0 auto;padding:20px 14px;max-width:720px;color:#13263a;'
        'font-family:system-ui,sans-serif;line-height:1.65;overflow-wrap:anywhere;">'
        f'<h1 style="font-size:25px;line-height:1.3;">{escape(title)}</h1>{content}</section>'
    )
    name = escape(title)
    preview = (
        '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>{name}</title></head><body>{body}</body></html>'
    )
    encoded = (json.dumps({"type": kind, "title": title, "facts": facts},
                          ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()
    identity = sha256(encoded + body.encode()).hexdigest()[:12]
    output.mkdir(parents=True, exist_ok=True)
    destination = output / f"{kind}-{identity}"
    if destination.is_dir():
        return destination
    with TemporaryDirectory(prefix=".preview-", dir=output) as scratch:
        staging = Path(scratch) / "draft"
        staging.mkdir()
        (staging / "facts.json").write_bytes(encoded)
        (staging / "body.html").write_text(body, encoding="utf-8")
        (staging / "preview.html").write_text(preview, encoding="utf-8")
        if kind == "data_tutorial" and facts["kilometers"]:
            training_chart(facts["kilometers"], staging / "training.png")
        try:
            staging.rename(destination)
        except OSError:
            if not destination.is_dir():
                raise
    return destination
