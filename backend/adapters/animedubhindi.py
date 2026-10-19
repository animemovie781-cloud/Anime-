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

    async def search(self, query: str, season: int = 1) -> List[Dict[str, Any]]:
        queries = []
        if season > 1:
            queries.append(f"{query} Season {season}")
            queries.append(f"{query} S{season}")
        queries.append(query)
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

        async with httpx.AsyncClient(timeout=8.0, follow_redirects=True) as client:
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
                html = resp.text

                # Check for intermediate adhlinks.com
                adh_match = re.search(r"href=[\"'](https?://[^\"']*adhlinks\.com/[^\"']+)[\"']", html)
                target_html = html
                if adh_match:
                    try:
                        a_resp = await client.get(adh_match.group(1), headers=headers)
                        if a_resp.status_code == 200:
                            target_html = a_resp.text
                    except Exception:
                        pass

                sources = []

                # 1. Movie blocks
                movie_blocks = re.findall(r"(<span[^>]*pro-ep-quality[^>]*>.*?</div>\s*<div[^>]*pro-btn-group[^>]*>.*?</div>)", target_html, re.DOTALL)
                if movie_blocks:
                    for b in movie_blocks:
                        q_match = re.search(r"pro-ep-quality[^>]*>\[?(480[pP]|720[pP]|1080[pP]|2160[pP]|4[kK])[^<\]]*\]?", b)
                        raw_q = q_match.group(1).upper() if q_match else "HD"
                        quality = (raw_q + "P") if not raw_q.endswith("P") and raw_q != "4K" else raw_q
                        links = re.findall(r"href=[\"']([^\"']+)[\"']", b)
                        for link in links:
                            if any(k in link for k in ["hubcloud", "gdflix", "pixeldrain", "r2", "desidubanime"]):
                                sources.append({
                                    "provider": "AnimeDubHindi",
                                    "url": link,
                                    "quality": f"{quality} (AnimeDubHindi)",
                                    "language": "Hindi Dub",
                                    "source_type": "official_watch_url"
                                })

                # 2. Series H4 blocks
                if not sources:
                    h4_tags = re.findall(r"<h4[^>]*>(.*?)</h4>", target_html, re.DOTALL)
                    filtered_h4 = [h for h in h4_tags if any(q in h for q in ["480P", "720P", "1080P", "480p", "720p", "1080p"])]
                    if filtered_h4:
                        ep_idx = max(0, min(episode - 1, len(filtered_h4) - 1))
                        selected_h4 = filtered_h4[ep_idx]
                        parts = re.split(r"(\b(?:480[pP]|720[pP]|1080[pP]|2160[pP]|4[kK])\b)", selected_h4)
                        for i in range(1, len(parts), 2):
                            raw_q = parts[i].upper()
                            quality = (raw_q + "P") if not raw_q.endswith("P") and raw_q != "4K" else raw_q
                            chunk = parts[i + 1] if i + 1 < len(parts) else ""
                            links = re.findall(r"href=[\"']([^\"']+)[\"']", chunk)
                            for link in links:
                                if any(k in link for k in ["hubcloud", "gdflix", "pixeldrain", "r2", "desidubanime"]):
                                    sources.append({
                                        "provider": "AnimeDubHindi",
                                        "url": link,
                                        "quality": f"{quality} (AnimeDubHindi)",
                                        "language": "Hindi Dub",
                                        "source_type": "official_watch_url"
                                    })

                # 3. Direct links fallback
                if not sources:
                    for a in re.findall(r"href=[\"']([^\"']*(?:hubcloud|gdflix|pixeldrain|r2\.cloudflarestorage)[^\"']*)[\"']", target_html):
                        q = "1080p (AnimeDubHindi)" if "1080" in a else "720p (AnimeDubHindi)" if "720" in a else "480p (AnimeDubHindi)"
                        sources.append({
                            "provider": "AnimeDubHindi",
                            "url": a,
                            "quality": q,
                            "language": "Hindi Dub",
                            "source_type": "official_watch_url"
                        })

                # Sort by quality (1080p -> 720p -> 480p)
                sources.sort(key=lambda s: 1080 if "1080" in s["quality"] else 720 if "720" in s["quality"] else 480 if "480" in s["quality"] else 0, reverse=True)
                return sources
            except Exception as e:
                traceback.print_exc()
                logger.error(f"AnimeDubHindi get_links error: {e}")
        return []
