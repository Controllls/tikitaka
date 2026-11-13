"""Archive official Pleos design references with URLs and SHA-256 provenance.

Development sources live outside app/src/main/assets so original FIG files and
reference screenshots do not bloat the APK or become screen-sized UI images.
Run with Python and the httpx / beautifulsoup4 packages installed.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from bs4 import BeautifulSoup
import httpx

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "design/pleos-official/2026-11-11"
BASE = "https://document.pleos.ai"
DEST.mkdir(parents=True, exist_ok=True)


def main():
    with httpx.Client(follow_redirects=True, timeout=60) as client:
        sitemap = client.get(BASE + "/sitemap.xml")
        sitemap.raise_for_status()
        locations = ET.fromstring(sitemap.content).findall(".//{*}loc")
        paths = sorted({BASE + urlsplit(node.text).path for node in locations if "/en/" not in node.text and any(part in node.text for part in (
            "/docs-design/", "/vehicle-app-planning-guide/", "/app-test-guide/", "/distribute/"))})
        paths += [BASE + "/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide"]

        def page(url):
            response = client.get(url)
            response.raise_for_status()
            soup = BeautifulSoup(response.text, "html.parser")
            article = soup.select_one("article") or soup.select_one("main")
            if article is None:
                raise ValueError(f"No article in {url}")
            slug = urlsplit(url).path.strip("/").replace("/", "__")
            path = DEST / "documents" / (slug + ".html")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(str(article), encoding="utf-8")
            assets = {urljoin(url, image["src"]) for image in article.select("img[src]")}
            assets.update(urljoin(url, a["href"]) for a in article.select("a[href]")
                          if urlsplit(a["href"]).path.lower().endswith((".fig", ".zip", ".ttf", ".otf", ".woff2")))
            return dict(url=url, title=article.select_one("h1").get_text(" ", strip=True),
                        file=str(path.relative_to(DEST)), sha256=hashlib.sha256(path.read_bytes()).hexdigest(), assets=sorted(assets))

        with ThreadPoolExecutor(max_workers=6) as pool:
            documents = list(pool.map(page, paths))
        urls = sorted({url for document in documents for url in document["assets"] if urlsplit(url).hostname == "document.pleos.ai"})

        def asset(url):
            response = client.get(url)
            response.raise_for_status()
            name = Path(urlsplit(url).path).name
            path = DEST / "sources" / (hashlib.sha256(url.encode()).hexdigest()[:12] + "_" + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(response.content)
            return dict(url=url, file=str(path.relative_to(DEST)), bytes=len(response.content),
                        sha256=hashlib.sha256(response.content).hexdigest(), mime=response.headers.get("content-type"))

        with ThreadPoolExecutor(max_workers=6) as pool:
            assets = list(pool.map(asset, urls))
        manifest = dict(fetched_at=datetime.now(timezone.utc).isoformat(), documents=documents, assets=assets,
                        note="Official public references. The user's five-page app Figma file is a separate input, still required.")
        (DEST / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(dict(documents=len(documents), assets=len(assets), bytes=sum(a["bytes"] for a in assets), destination=str(DEST))))


if __name__ == "__main__":
    main()
