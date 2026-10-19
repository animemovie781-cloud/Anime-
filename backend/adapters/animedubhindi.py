import httpx
import logging
import traceback
from typing import List, Dict, Any, Optional
from bs4 import BeautifulSoup
import re

logger = logging.getLogger(__name__)

class AnimeDubHindiAdapter:
    def __init__(self):
        self.domains = [
            "https://www.animedubhindi.link",
            "https://animedubhindi.org",
            "https://www.animedubhindi.cc"
        ]

    async def search(self, query: str) -> List[Dict[str, Any]]:
        queries = [query]
        if ":" in query:
            queries.append(query.split(":")[0].strip())
        words = query.split()
        if len(words) > 2:
            queries.append(" ".join(words[:2]))
        if len(words) > 1 and " ".join(words[:2]) != words[0]:
            queries.append(words[0])

        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
            "Referer": "https://www.google.com/"
        }

        async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
            for base_url in self.domains:
                for q in queries:
                    search_url = f"{base_url}/?s={q}"
                    try:
                        resp = await client.get(search_url, headers=headers)
                        if resp.status_code == 200:
                            soup = BeautifulSoup(resp.text, "html.parser")
                            results = []
                            for article in soup.select("article"):
                                a_tag = article.select_one("h2 a") or article.select_one("a")
                                if not a_tag:
                                    continue
                                title = a_tag.get_text(strip=True)
                                href = a_tag.get("href")
                                if not href:
                                    continue
                                img = article.select_one("img")
                                poster = img.get("src") if img else None
                                results.append({
                                    "title": title,
                                    "url": href,
                                    "poster": poster,
                                    "type": "anime"
                                })
                            if results:
                                return results
                    except Exception as e:
                        logger.error(f"AnimeDubHindi search error on {base_url} for '{q}': {e}")
        return []

    async def get_links(self, post_url: str, season: int = 1, episode: int = 1) -> List[Dict[str, Any]]:
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
            "Referer": "https://www.animedubhindi.link/"
        }

        async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
            try:
                resp = await client.get(post_url, headers=headers)
                if resp.status_code != 200:
                    return []
                soup = BeautifulSoup(resp.text, "html.parser")

                iframe_a = soup.select_one("div.wp-block-button a")
                if not iframe_a:
                    iframe_a = soup.select_one("iframe") or soup.select_one(".entry-content a")

                iframe_url = iframe_a.get("href") or iframe_a.get("src") if iframe_a else None

                sources = []
                if iframe_url and iframe_url.startswith("http"):
                    try:
                        i_resp = await client.get(iframe_url, headers=headers)
                        if i_resp.status_code == 200:
                            i_soup = BeautifulSoup(i_resp.text, "html.parser")
                            for a in i_soup.select("a"):
                                href = a.get("href", "")
                                if any(k in href for k in ["hubcloud", "gdflix", "pixeldrain", "r2.cloudflarestorage", "adhlinks"]):
                                    sources.append({
                                        "provider": "AnimeDubHindi",
                                        "url": href,
                                        "quality": "1080p",
                                        "language": "Hindi",
                                        "source_type": "official_watch_url"
                                    })
                    except Exception:
                        pass

                for a in soup.select("a"):
                    href = a.get("href", "")
                    if any(k in href for k in ["hubcloud", "gdflix", "pixeldrain", "r2.cloudflarestorage", "adhlinks"]):
                        if not any(s["url"] == href for s in sources):
                            sources.append({
                                "provider": "AnimeDubHindi",
                                "url": href,
                                "quality": "1080p",
                                "language": "Hindi",
                                "source_type": "official_watch_url"
                            })

                # Check for direct intermediate links from adhlinks if found
                adh_links = [s["url"] for s in sources if "adhlinks" in s["url"]]
                for alink in adh_links[:2]:
                    try:
                        a_resp = await client.get(alink, headers=headers)
                        if a_resp.status_code == 200:
                            a_soup = BeautifulSoup(a_resp.text, "html.parser")
                            for a in a_soup.select("a"):
                                ahref = a.get("href", "")
                                if any(k in ahref for k in ["hubcloud", "gdflix", "pixeldrain", "r2.cloudflarestorage"]):
                                    if not any(s["url"] == ahref for s in sources):
                                        sources.append({
                                            "provider": "AnimeDubHindi",
                                            "url": ahref,
                                            "quality": "1080p",
                                            "language": "Hindi",
                                            "source_type": "official_watch_url"
                                        })
                    except Exception:
                        pass

                return sources
            except Exception as e:
                traceback.print_exc()
                logger.error(f"AnimeDubHindi get_links error: {e}")
        return []
