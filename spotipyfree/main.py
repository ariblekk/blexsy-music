from fastapi import FastAPI, APIRouter, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from SpotipyFree import Spotify

# ponytail: in-memory single Spotify instance without auth; upgrade to session pool if user login/cookies are needed.
sp = Spotify()

app = FastAPI(title="SpotipyFree API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

v1 = APIRouter(prefix="/v1", tags=["v1"])


@v1.get("/health")
def health():
    return {"status": "ok"}


@v1.get("/search")
def search(q: str = Query(..., min_length=1), limit: int = Query(20, ge=1, le=50)):
    try:
        return sp.search(q)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Search failed: {e}")


@v1.get("/tracks/{track_id}")
def get_track(track_id: str):
    try:
        return sp.track(track_id)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Track not found: {e}")


@v1.get("/albums/{album_id}")
def get_album(album_id: str):
    try:
        return sp.album(album_id)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Album not found: {e}")


@v1.get("/albums/{album_id}/tracks")
def get_album_tracks(album_id: str):
    try:
        return sp.album_tracks(album_id)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Album tracks not found: {e}")


@v1.get("/artists/{artist_id}")
def get_artist(artist_id: str):
    try:
        return sp.artist(artist_id)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Artist not found: {e}")


@v1.get("/artists/{artist_id}/albums")
def get_artist_albums(artist_id: str):
    try:
        return sp.artist_albums(artist_id, include_groups="album,single,compilation")
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Artist albums not found: {e}")


@v1.get("/playlists/{playlist_id}")
def get_playlist(playlist_id: str):
    try:
        return sp.playlist_items(playlist_id)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Playlist not found: {e}")


app.include_router(v1)
