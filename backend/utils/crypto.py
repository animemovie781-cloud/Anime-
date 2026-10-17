import hashlib
import hmac
import base64
import time
from urllib.parse import urlparse, parse_qsl, urlencode

def md5_hash(text: str) -> str:
    return hashlib.md5(text.encode('utf-8')).hexdigest()

def hmac_md5(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.md5).digest()

def generate_signature(method: str, url_str: str, body_bytes: bytes = b"") -> tuple[str, str]:
    """
    Generates the x-tr-signature and necessary headers for the movie API.
    Returns: (timestamp, signature_header, client_token)
    """
    timestamp = str(int(time.time() * 1000))
    reversed_ts = timestamp[::-1]
    client_token = f"{timestamp},{md5_hash(reversed_ts)}"
    
    parsed_url = urlparse(url_str)
    # Sort query parameters alphabetically
    query_params = dict(parse_qsl(parsed_url.query))
    sorted_query = urlencode(sorted(query_params.items()))
    
    canonical_path_query = parsed_url.path
    if sorted_query:
        canonical_path_query += f"?{sorted_query}"
        
    accept = "application/json"
    content_type = "application/json;charset=UTF-8"
    
    actual_body_length = ""
    body_hash = ""
    if body_bytes:
        actual_body_length = str(len(body_bytes))
        # Limit hash to first 100KB as per original logic
        limit = min(len(body_bytes), 102400)
        body_hash = hashlib.md5(body_bytes[:limit]).hexdigest()
        
    canonical_string = f"{method.upper()}\n{accept}\n{content_type}\n{actual_body_length}\n{timestamp}\n{body_hash}\n{canonical_path_query}"
    
    # Official Key
    key_str = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
    try:
        key_bytes = base64.b64decode(key_str)
    except:
        key_bytes = key_str.encode('utf-8')
        
    sig_digest = hmac_md5(key_bytes, canonical_string.encode('utf-8'))
    base64_sig = base64.b64encode(sig_digest).decode('utf-8')
    
    signature_header = f"{timestamp}|2|{base64_sig}"
    
    return timestamp, signature_header, client_token

def get_client_info(region: str = "IN", sp_code: str = "40401", device_id: str = None) -> str:
    if not device_id:
        import uuid
        device_id = uuid.uuid4().hex
    
    timezone = "Asia/Kolkata" if region == "IN" else "America/New_York"
    info = {
        "package_name": "com.movieboxpro.android",
        "version_name": "16.2.1",
        "version_code": 16210,
        "os": "android",
        "os_version": "12",
        "install_ch": "googleplay",
        "device_id": device_id,
        "install_store": "googleplay",
        "gaid": "",
        "brand": "google",
        "model": "Pixel 6",
        "system_language": "en",
        "net": "wifi",
        "region": region,
        "timezone": timezone,
        "sp_code": sp_code,
        "X-Play-Mode": "2"
    }
    # Return as compact string (no spaces)
    import json
    return json.dumps(info, separators=(',', ':'))
