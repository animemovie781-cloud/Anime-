import httpx
from fastapi import FastAPI, Query, HTTPException
from typing import Optional
import json

from utils.crypto import generate_signature, get_client_info
from utils.extractor import clean_stream_list

app = FastAPI(title="Movie Proxy API")

OFFICIAL_BASE_URL = "https://api.inmoviebox.com"
DEFAULT_HOST = "api6.aoneroom.com"

async def make_official_request(method: str, endpoint: str, params: dict = None, body: dict = None):
    url = f"{OFFICIAL_BASE_URL}{endpoint}"
    if params is None:
        params = {}
    
    if "host" not in params:
        params["host"] = DEFAULT_HOST
        
    # Construct final URL for signature generation (with query params)
    query_str = "?" + "&".join([f"{k}={v}" for k, v in params.items()]) if params else ""
    full_url_for_sig = f"{endpoint}{query_str}"
    
    body_bytes = json.dumps(body).encode('utf-8') if body else b""
    
    timestamp, signature, client_token = generate_signature(method, full_url_for_sig, body_bytes)
    client_info = get_client_info()
    
    headers = {
        "User-Agent": "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)",
        "Accept": "application/json",
        "Content-Type": "application/json;charset=UTF-8",
        "X-Sign-Version": "2.0",
        "appid": "4U01pxRu278GqCZKY9",
        "X-Timestamp": timestamp,
        "Referer": "https://api.inmoviebox.com/",
        "X-Client-Token": client_token,
        "x-tr-signature": signature,
        "X-Play-Mode": "2",
        "X-Client-Info": client_info,
        "X-Client-Status": "1",
        "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcwNjU5NDg0MTAyMTM4MTYyMzIsInV0cCI6MSwiZXhwIjoxNzkxNzMyMjMzLCJpYXQiOjE3ODM5NTU5MzN9.7iyEzTj4vWAbOF0oXwNnZ0p3Nc1QaO6K9eMiGFyVfGs" # Guest Token
    }
    
    async with httpx.AsyncClient() as client:
        try:
            if method.upper() == "POST":
                response = await client.post(url, params=params, content=body_bytes, headers=headers)
            else:
                response = await client.get(url, params=params, headers=headers)
                
            response.raise_for_status()
            return response.json()
        except httpx.HTTPStatusError as e:
            raise HTTPException(status_code=e.response.status_code, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Proxy error: {str(e)}")

@app.get("/health")
def health_check():
    return {"status": "ok"}

@app.get("/trending")
async def get_trending(page: int = 1, pageSize: int = 24):
    data = await make_official_request("POST", "/wefeed-mobile-bff/subject-api/trending/v2", body={})
    # Extract items and flatten
    items = data.get("data", {}).get("list", []) or data.get("data", {}).get("items", []) or []
    return {"code": 0, "data": items}

@app.get("/search")
async def search(q: str, page: int = 1):
    body = {
        "keyword": q,
        "page": page,
        "pageSize": 20
    }
    data = await make_official_request("POST", "/wefeed-mobile-bff/subject-api/search", body=body)
    items = data.get("data", {}).get("list", []) or data.get("data", {}).get("items", []) or []
    return {"code": 0, "data": {"items": items}}

@app.get("/detail/{subject_id}")
async def get_detail(subject_id: str):
    data = await make_official_request("GET", "/wefeed-mobile-bff/subject-api/get", params={"subjectId": subject_id})
    return data

@app.get("/stream/{subject_id}")
async def get_stream(
    subject_id: str, 
    season: int = 1, 
    episode: int = 1, 
    quality: str = "720p",
    resource_id: Optional[str] = None
):
    params = {
        "subjectId": subject_id,
        "se": season,
        "ep": episode,
        "quality": quality
    }
    if resource_id:
        params["resourceId"] = resource_id
        
    data = await make_official_request("GET", "/wefeed-mobile-bff/subject-api/play-info", params=params)
    
    # Process and clean streams
    raw_streams = data.get("data", {}).get("streamList", []) or data.get("data", {}).get("streams", []) or []
    global_cookie = data.get("signCookie", "")
    
    cleaned = clean_stream_list(raw_streams, global_cookie)
    
    first = cleaned[0] if cleaned else {}
    
    return {
        "code": 0,
        "url": first.get("url", ""),
        "quality": quality,
        "cookie": first.get("cookie", global_cookie),
        "streamList": cleaned,
        "subtitles": [] # Subtitle logic can be added similarly
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
