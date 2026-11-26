"""Find recent evidence leads without treating search snippets as facts."""

from datetime import date, timedelta
import re
from urllib.parse import urlsplit

DOMAIN = re.compile(r"[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9]+(?:[a-z0-9-]*[a-z0-9])?)+")


def discover(topic, domains, hours, now, search):
    if not topic.strip() or len(topic) > 100 or not 24 <= hours <= 72 or not 1 <= len(domains) <= 20:
        raise ValueError("Supply a topic, 1 to 20 known source domains, and a 24 to 72 hour window")
    domains = [domain.strip().lower() for domain in domains]
    if not all(DOMAIN.fullmatch(domain) for domain in domains):
        raise ValueError("Expected source domains without URLs or ports")
    oldest = (now - timedelta(hours=hours)).date()
    response = search({"query": f"{topic} 马拉松 官方成绩 赛后战报", "search_depth": "advanced",
                       "max_results": 10, "include_domains": domains, "start_date": str(oldest),
                       "include_published_date": True, "filter_by_published_date": True})
    candidates = {}
    for item in response["results"]:
        url, title = item.get("url"), item.get("title")
        if not isinstance(url, str) or not isinstance(title, str) or not title.strip():
            continue
        parts = urlsplit(url)
        host = parts.hostname or ""
        if (parts.scheme != "https" or parts.username is not None or parts.password is not None
                or parts.port not in (None, 443)
                or not any(host == domain or host.endswith("." + domain) for domain in domains)):
            continue
        try:
            published = date.fromisoformat(item["published_date"][:10])
        except (KeyError, ValueError, TypeError):
            continue
        if oldest <= published <= now.date():
            candidates[url] = {"url": url, "title": title.strip()[:240],
                               "published_date": str(published), "status": "CANDIDATE"}
    return list(candidates.values())[:10]
