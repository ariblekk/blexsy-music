import time
from typing import Any, Dict
from fastapi import FastAPI, APIRouter, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from SpotipyFree import Spotify

# ponytail: in-memory single Spotify instance without auth; upgrade to session pool if user login/cookies are needed.
sp = Spotify()

# ponytail: simple in-memory LRU cache to prevent hammering Spotify from origin; upgrade to Redis if multi-worker needed.
class MemoryCache:
    def __init__(self, ttl_seconds: int = 3600, max_size: int = 1000):
        self.ttl = ttl_seconds
        self.max_size = max_size
        self.store: Dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Any | None:
        if key in self.store:
            saved_at, value = self.store[key]
            if time.time() - saved_at < self.ttl:
                return value
            del self.store[key]
        return None

    def set(self, key: str, value: Any):
        if len(self.store) >= self.max_size:
            for k in list(self.store.keys())[:150]:
                self.store.pop(k, None)
        self.store[key] = (time.time(), value)

cache = MemoryCache()

app = FastAPI(title="SpotipyFree API", version="1.0.0")

# 1. GZip compression (reduces JSON payload size by 70-90%)
app.add_middleware(GZipMiddleware, minimum_size=1000)

# 2. CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 3. Cache-Control headers for Cloudflare Edge & Browser caching
@app.middleware("http")
async def add_cache_headers(request: Request, call_next):
    response = await call_next(request)
    if request.method == "GET" and response.status_code == 200:
        path = request.url.path
        if path.startswith(("/v1/tracks", "/v1/track", "/v1/albums", "/v1/album")):
            response.headers["Cache-Control"] = "public, max-age=14400, s-maxage=604800, stale-while-revalidate=86400"
        elif path.startswith(("/v1/artists", "/v1/artist", "/v1/playlists", "/v1/playlist")):
            response.headers["Cache-Control"] = "public, max-age=7200, s-maxage=86400, stale-while-revalidate=43200"
        elif path.startswith("/v1/search"):
            response.headers["Cache-Control"] = "public, max-age=3600, s-maxage=86400, stale-while-revalidate=3600"
    return response

v1 = APIRouter(prefix="/v1", tags=["v1"])


@v1.get("/health")
def health():
    return {"status": "ok"}


@v1.get("/search")
def search(q: str = Query(..., min_length=1), limit: int = Query(20, ge=1, le=50)):
    key = f"search:{q.strip().lower()}:{limit}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.search(q)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Search failed: {e}")


@v1.get("/tracks/{track_id}")
@v1.get("/track/{track_id}")
def get_track(track_id: str):
    key = f"track:{track_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.track(track_id)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Track not found: {e}")


@v1.get("/albums/{album_id}")
@v1.get("/album/{album_id}")
def get_album(album_id: str):
    key = f"album:{album_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.album(album_id)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Album not found: {e}")


@v1.get("/albums/{album_id}/tracks")
@v1.get("/album/{album_id}/tracks")
def get_album_tracks(album_id: str):
    key = f"album_tracks:{album_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.album_tracks(album_id)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Album tracks not found: {e}")


@v1.get("/artists/{artist_id}")
@v1.get("/artist/{artist_id}")
def get_artist(artist_id: str):
    key = f"artist:{artist_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.artist(artist_id)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Artist not found: {e}")


@v1.get("/artists/{artist_id}/albums")
@v1.get("/artist/{artist_id}/albums")
def get_artist_albums(artist_id: str):
    key = f"artist_albums:{artist_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.artist_albums(artist_id, include_groups="album,single,compilation")
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Artist albums not found: {e}")


@v1.get("/playlists/{playlist_id}")
@v1.get("/playlist/{playlist_id}")
def get_playlist(playlist_id: str):
    key = f"playlist:{playlist_id}"
    cached = cache.get(key)
    if cached is not None:
        return cached
    try:
        res = sp.playlist_items(playlist_id)
        cache.set(key, res)
        return res
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Playlist not found: {e}")


app.include_router(v1)
