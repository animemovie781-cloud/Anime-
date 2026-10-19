package com.example.data.repository

import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
import com.example.data.local.MovieDao
import com.example.data.model.*
import com.example.data.network.AnimeDubHindiProvider
import com.example.data.network.GetListRequest
import com.example.data.network.MovieBoxApi
import com.example.data.network.TmdbClient
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MovieRepository(
    private val api: MovieBoxApi,
    private val dao: MovieDao,
    private val prefs: PreferencesManager? = null
) {
    // Room Databases operations
    val favorites: Flow<List<FavoriteEntity>> = dao.getAllFavorites()
    val history: Flow<List<HistoryEntity>> = dao.getAllHistory()

    suspend fun insertFavorite(favorite: FavoriteEntity) = withContext(Dispatchers.IO) {
        dao.insertFavorite(favorite)
    }

    suspend fun deleteFavorite(id: String) = withContext(Dispatchers.IO) {
        dao.deleteFavoriteById(id)
    }

    suspend fun syncFavorites(favorites: List<FavoriteEntity>) = withContext(Dispatchers.IO) {
        dao.syncFavorites(favorites)
    }

    fun isFavorite(id: String): Flow<Boolean> = dao.isFavorite(id)
    suspend fun isFavoriteDirect(id: String): Boolean = withContext(Dispatchers.IO) {
        dao.isFavoriteDirect(id)
    }

    suspend fun insertHistory(history: HistoryEntity) = withContext(Dispatchers.IO) {
        dao.insertHistory(history)
    }

    suspend fun deleteHistory(id: String) = withContext(Dispatchers.IO) {
        dao.deleteHistoryById(id)
    }

    suspend fun getHistoryById(id: String): HistoryEntity? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)
    }

    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, MovieSubject>()

    suspend fun getTrending(page: Int = 1, pageSize: Int = 20): List<MovieSubject> = withContext(Dispatchers.IO) {
        val useTmdb = prefs?.metadataSource == "tmdb"
        if (useTmdb) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val res = TmdbClient.api.getTrending(apiKey, page)
                val list = res.results.map { TmdbClient.mapItemToMovieSubject(it) }
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB getTrending failed, falling back to MovieBox API", e)
            }
        }
        try {
            val response = api.getTrending(page, pageSize)
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDetailRec(subjectId: String): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (subjectId.startsWith("tmdb_")) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val isTv = subjectId.startsWith("tmdb_tv_")
                val cleanId = if (isTv) subjectId.removePrefix("tmdb_tv_") else subjectId.removePrefix("tmdb_movie_")
                val rec = if (isTv) {
                    TmdbClient.api.getTvRecommendations(cleanId, apiKey)
                } else {
                    TmdbClient.api.getMovieRecommendations(cleanId, apiKey)
                }
                val list = rec.results.map { TmdbClient.mapItemToMovieSubject(it) }
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB getDetailRec failed for $subjectId", e)
            }
        }
        try {
            val req = mapOf(
                "subjectId" to subjectId,
                "page" to 1,
                "pageSize" to 15
            )
            val response = api.getDetailRec(req)
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if (response.code == 0 || response.code == 200) list else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getCategoryList(categoryId: Int, page: Int = 1, pageSize: Int = 20): List<MovieSubject> = withContext(Dispatchers.IO) {
        val useTmdb = prefs?.metadataSource == "tmdb"
        if (useTmdb) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val res = when (categoryId) {
                    2 -> TmdbClient.api.getPopularMovies(apiKey, page)
                    5 -> TmdbClient.api.getPopularTv(apiKey, page)
                    8 -> TmdbClient.api.getDiscoverTv(apiKey, page, withGenres = "16", withOriginCountry = "JP")
                    18 -> TmdbClient.api.getDiscoverMovies(apiKey, page, withOriginCountry = "IN|KR|JP|CN")
                    else -> TmdbClient.api.getPopularMovies(apiKey, page)
                }
                val list = res.results.map { TmdbClient.mapItemToMovieSubject(it) }
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB getCategoryList failed for $categoryId", e)
            }
        }
        try {
            val response = api.getList(GetListRequest(categoryId, page, pageSize))
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCachedDetail(subjectId: String): MovieSubject? {
        return detailCache[subjectId]
    }

    fun getCachedEpisodes(subjectId: String, season: Int): List<Episode> {
        val detail = detailCache[subjectId] ?: return emptyList()
        val seasonInfo = detail.seasonList.find { it.season == season } ?: return emptyList()
        return seasonInfo.episodeList.map { epInfo ->
            Episode(
                episodeId = "${subjectId}_S${season}_E${epInfo.episode}",
                title = epInfo.title.ifEmpty { "Episode ${epInfo.episode}" },
                episodeNumber = epInfo.episode,
                seasonNumber = season,
                stillPath = epInfo.stillPath
            )
        }
    }

    suspend fun getDetail(subjectId: String): MovieSubject? = withContext(Dispatchers.IO) {
        val cached = detailCache[subjectId]
        if (cached != null) return@withContext cached

        if (subjectId.startsWith("tmdb_")) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val isTv = subjectId.startsWith("tmdb_tv_")
                val cleanId = if (isTv) subjectId.removePrefix("tmdb_tv_") else subjectId.removePrefix("tmdb_movie_")
                val detail = if (isTv) {
                    TmdbClient.api.getTvDetails(cleanId, apiKey)
                } else {
                    TmdbClient.api.getMovieDetails(cleanId, apiKey)
                }
                val subject = TmdbClient.mapDetailToMovieSubject(detail, isTv)
                detailCache[subjectId] = subject
                return@withContext subject
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB getDetail failed for $subjectId", e)
            }
        }

        try {
            val response = api.getDetail(subjectId)
            if ((response.code == 200 || response.code == 0) && response.data != null) {
                detailCache[subjectId] = response.data
                response.data
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getEpisodes(subjectId: String, season: Int): List<Episode> = withContext(Dispatchers.IO) {
        if (subjectId.startsWith("tmdb_tv_")) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val tvId = subjectId.removePrefix("tmdb_tv_")
                val seasonDetails = TmdbClient.api.getTvSeason(tvId, season, apiKey)
                val episodes = TmdbClient.mapSeasonDetailsToEpisodes(subjectId, seasonDetails)
                if (episodes.isNotEmpty()) return@withContext episodes
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB getEpisodes failed for $subjectId S$season", e)
            }
        }

        val detail = getDetail(subjectId)
        if (detail != null && detail.seasonList.isNotEmpty()) {
            val seasonInfo = detail.seasonList.find { it.season == season }
            if (seasonInfo != null) {
                return@withContext seasonInfo.episodeList.map { epInfo ->
                    Episode(
                        episodeId = "${subjectId}_S${season}_E${epInfo.episode}",
                        title = epInfo.title.ifEmpty { "Episode ${epInfo.episode}" },
                        episodeNumber = epInfo.episode,
                        seasonNumber = season,
                        duration = epInfo.duration.ifEmpty { "45 min" },
                        stillPath = epInfo.stillPath
                    )
                }
            }
        }
        emptyList()
    }

    suspend fun getPlayInfo(
        subjectId: String,
        season: Int,
        episode: Int,
        quality: String = "1080p",
        resourceId: String? = null
    ): PlayInfo = withContext(Dispatchers.IO) {
        var targetSubjectId = subjectId

        // If the subject is from TMDB, resolve stream link using app's internal search / MovieBox provider
        if (subjectId.startsWith("tmdb_")) {
            val isTv = subjectId.startsWith("tmdb_tv_")
            val detail = getCachedDetail(subjectId) ?: getDetail(subjectId)
            val title = detail?.name ?: ""

            if (title.isNotBlank()) {
                val candidateTitles = listOf(
                    title,
                    title.substringBefore(":").substringBefore("-").substringBefore("(").trim(),
                    title.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
                ).distinct().filter { it.isNotBlank() }

                for (candTitle in candidateTitles) {
                    if (targetSubjectId != subjectId) break
                    try {
                        val searchResp = api.search(candTitle, 1, 10)
                        val candidates = searchResp.data?.let {
                            if (it.items.isNotEmpty()) it.items else it.list
                        } ?: emptyList()

                        val matched = candidates.firstOrNull { c ->
                            c.isTvShow == isTv && (c.name.equals(candTitle, ignoreCase = true) || c.name.contains(candTitle, ignoreCase = true))
                        } ?: candidates.firstOrNull { it.isTvShow == isTv } ?: candidates.firstOrNull()

                        if (matched != null) {
                            targetSubjectId = matched.id
                            android.util.Log.d("MovieRepository", "Resolved TMDB title '$candTitle' to App MovieBox ID: $targetSubjectId")
                            break
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MovieRepository", "Failed to resolve stream for TMDB title $candTitle", e)
                    }
                }
            }
        }

        val basePlayInfo = try {
            val info = api.playInfo(targetSubjectId, season, episode, quality, resourceId)
            if (info.streamList.isNotEmpty()) {
                info
            } else if (targetSubjectId != subjectId) {
                // Fallback to original subjectId so LocalMovieBoxServer's internal stream resolver can attempt
                api.playInfo(subjectId, season, episode, quality, resourceId)
            } else {
                info
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error getting play info for $targetSubjectId", e)
            PlayInfo(emptyList(), emptyList())
        }

        // Query AnimeDubHindi provider if streams are empty or if animedubhindi is selected / title is anime
        val detail = getCachedDetail(subjectId) ?: getDetail(subjectId)
        val title = detail?.name ?: ""
        val isAnime = detail?.genres?.any { it.contains("anime", ignoreCase = true) || it.contains("animation", ignoreCase = true) } == true ||
                detail?.name?.contains("dub", ignoreCase = true) == true ||
                resourceId == "animedubhindi"

        if ((basePlayInfo.streamList.isEmpty() || isAnime || resourceId == "animedubhindi") && title.isNotBlank()) {
            try {
                val animeProvider = AnimeDubHindiProvider()
                val animeStreams = animeProvider.resolveStreamForTitle(title, season, episode)
                if (animeStreams.isNotEmpty()) {
                    val validBaseStreams = basePlayInfo.streamList.filter { 
                        !it.url.contains("macdn.aoneroom.com") && !it.url.contains("b164fbfb4347792950bdfbfb563d39d9")
                    }
                    val combinedStreams = (animeStreams + validBaseStreams).distinctBy { it.url }
                    return@withContext PlayInfo(combinedStreams, basePlayInfo.subTitleList)
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "AnimeDubHindi stream provider fallback failed", e)
            }
        }

        basePlayInfo
    }

    suspend fun search(query: String): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val useTmdb = prefs?.metadataSource == "tmdb"
        if (useTmdb) {
            try {
                val apiKey = prefs?.tmdbApiKey ?: TmdbClient.DEFAULT_TMDB_API_KEY
                val res = TmdbClient.api.searchMulti(apiKey, query, 1)
                val list = res.results.filter { it.mediaType != "person" }.map { TmdbClient.mapItemToMovieSubject(it) }
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                android.util.Log.e("MovieRepository", "TMDB search failed for '$query'", e)
            }
        }
        try {
            val response = api.search(query, 1, 30)
            val items = response.data?.let { data ->
                if (data.items.isNotEmpty()) data.items else data.list
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && items.isNotEmpty()) {
                items
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getSearchSuggestions(query: String? = null): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.getSearchSuggestions(query)
            if (response.code == 0 || response.code == 200) {
                response.data
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
