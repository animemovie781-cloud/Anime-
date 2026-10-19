# TMDB Metadata with Dedicated File-Based Stream Extraction Architecture

This plan specifies the architecture to strictly isolate concerns as requested: **all metadata (browse, search, details, posters) is loaded directly from TMDB**, and **only the stream link resolution logic from the provided server/extractor file (`api_server_download.txt`) is used to load streams into the video player**.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions are locked based on your explicit requirement:
> - **Metadata Isolation**: 100% TMDB API key driven. All discovery, trending, search, genres, posters, episode listings, and cast are loaded cleanly from TMDB so there are zero 401 JWT unauthorized errors on the browsing screens.
> - **Stream-Only Logic from Provided File**: The stream resolving algorithm from `api_server_download.txt` is used **exclusively when playing content**. When the user clicks play on any movie, TV show, or anime, only the `/stream/` pipeline executes.
> - **Video Player Delivery**: ExoPlayer receives the resolved direct stream URL (HLS / MP4 / DASH) with its authenticated `signCookie` and displays quality selector options (1080p, 720p, 480p, Auto).

- **Confirmed Decision 1**: **Zero Metadata Traffic to the Flaky Upstream Gateway**. The app will never make trending, tab, or search calls to the upstream MovieBox BFF gateway (which triggers the 401 JWT expired error). TMDB handles all UI content.
- **Confirmed Decision 2**: **Faithful Stream Pipeline from `api_server_download.txt`**:
  1. Title mapping (resolving TMDB title to internal stream subject ID).
  2. Primary `/wefeed-mobile-bff/subject-api/play-info` call.
  3. Anti-piracy `urlprefix` Base64 decode from `signCookie`.
  4. `resourceDetectors` fallback if `play-info` has no direct streams.
  5. H.264 (MP4 / HLS) codec prioritization over HEVC for mobile playback reliability.
  6. UGC resource mirror rotation if primary cloud is offline.

---

### 1. Overview & Core Concept

- **What It Does**: Decouples the application into a high-speed, beautiful frontend powered by TMDB metadata, and a dedicated stream extraction pipeline extracted directly from the user's `api_server_download.txt`. When a movie or anime episode is tapped, the stream pipeline resolves the playable link and hands it directly to ExoPlayer.
- **Target Audience**: Users who want guaranteed smooth navigation with rich movie and anime metadata, without broken dashboards or 401 gateway errors, while playing actual video streams.
- **Key Value**: 100% reliability for browsing/searching + automated stream link extraction from the provided backend logic.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Browsing & Search**: Instant loading of Trending, Top Rated, and Genre-filtered Movies, Series, and Anime directly via TMDB. Fast, error-free UI with high-resolution backdrops and posters.
2. **Details Screen**: Comprehensive overview, ratings, cast cards, and full season/episode listings generated from TMDB.
3. **Play Action**:
   - User taps "Play Movie" or selects an Episode.
   - The app's Stream Resolver executes the `api_server_download.txt` stream pipeline in the background.
   - A subtle buffering indicator shows stream initialization.
4. **Playback & Quality Selection**:
   - Video player starts immediately with the prioritized H.264 stream.
   - User can open the "Video Quality" sheet from the gear menu to toggle between 1080p, 720p, 480p, and Auto.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: TMDB for All Metadata vs. Upstream Gateway**
  - *Chosen Approach*: Use TMDB for all search, home, and details views.
  - *Why*: The upstream MovieBox gateway frequently expires anonymous tokens (401 Unauthorized), which previously crashed the home feed. TMDB has 100% uptime with no JWT expiration issues.
  - *Alternative Considered*: Attempting to proxy all trending calls through Render; rejected because the user reported Render returns `401 Unauthorized: JWT token has expired`.

- **Decision 2: Direct Implementation of `api_server_download.txt` Stream Resolver**
  - *Chosen Approach*: Port the exact Python stream resolution algorithms from `api_server_download.txt` into the client's `LocalMovieBoxServer` and `MovieRepository` stream handler.
  - *Why*: Ensures the Android client can resolve streams locally on-device without depending on an external Render proxy that fails authentication.

---

### 4. Technical Architecture & Data Strategy

#### Architectural System Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI                       │
│     HomeFeed / Search / DetailScreen / PlayerScreen + Quality   │
└───────────────────────────────┬─────────────────────────────────┘
                                │
               ┌────────────────┴────────────────┐
               ▼                                 ▼
   [ METADATA OPERATIONS ]              [ STREAM PLAYBACK ONLY ]
   (Browse / Search / Info)             (When User Clicks Play)
               │                                 │
               ▼                                 ▼
┌──────────────────────────────┐ ┌────────────────────────────────┐
│           TmdbApi            │ │  api_server_download Pipeline  │
│  - Trending / Top Movies     │ │  (LocalMovieBoxServer.kt)      │
│  - Multi-Search (Anime/Film) │ ├────────────────────────────────┤
│  - Season & Episode Info     │ │ 1. Title -> Stream Subject ID  │
│  - High-res Posters & Cast   │ │ 2. play-info API               │
└──────────────────────────────┘ │ 3. urlprefix Base64 decode     │
                                 │ 4. resourceDetectors fallback  │
                                 │ 5. H.264 codec prioritization  │
                                 │ 6. UGC mirror rotation         │
                                 └───────────────┬────────────────┘
                                                 │
                                                 ▼
                                 ┌────────────────────────────────┐
                                 │         Media3 ExoPlayer       │
                                 │  - HLS / MP4 playback          │
                                 │  - signCookie injection        │
                                 │  - Quality Sheet Selection     │
                                 └────────────────────────────────┘
```

#### Detailed Stream Extraction Pipeline (from `api_server_download.txt`)
1. **Title Resolution**: When `subjectId` starts with `tmdb_`, query stream database by title to obtain the stream `subjectId`.
2. **Play Info Request**: Send authenticated request with dynamic token and timestamp signature for `(subjectId, season, episode, quality)`.
3. **Anti-Piracy Bypass**: Inspect `signCookie` for `urlprefix=`. If present, Base64 decode the prefix and append stream target (`index.mpd` / `.m3u8` / `.mp4`).
4. **Resource Detector Fallback**: If `streamList` is empty, query `resourceDetectors` in detail and parse `resolutionList` for matching season/episode.
5. **Codec Prioritization**: Sort candidate streams so that non-HEVC H.264 MP4 and HLS streams are prioritized over H.265 (HEVC), which fails on most mobile browsers/players.
6. **Delivery to ExoPlayer**: Populate `PlayInfo(streamList, subTitleList)` so ExoPlayer receives the direct stream with appropriate cookies and quality tags.

#### Implementation Steps
1. **Verify Metadata Complete Isolation**: Ensure all UI browsing and search views exclusively query `TmdbApi` with the configured API key.
2. **Update Stream Handler in `LocalMovieBoxServer.kt`**: Integrate the exact `api_server_download.txt` stream pipeline (urlprefix decode, resourceDetectors fallback, H264 prioritization) into the `/stream/` endpoint.
3. **Verify ExoPlayer Quality Selector**: Ensure the stream items returned by the pipeline populate the quality selection sheet in `MovieScreens.kt`.
4. **Compile & Test Verification**: Verify build and run local unit tests.
