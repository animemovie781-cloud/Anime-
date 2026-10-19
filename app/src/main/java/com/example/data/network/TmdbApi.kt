package com.example.data.network

import com.example.data.model.CastMember
import com.example.data.model.Episode
import com.example.data.model.EpisodeInfo
import com.example.data.model.MovieSubject
import com.example.data.model.ResourceDetector
import com.example.data.model.SeasonInfo
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class TmdbMovieItem(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPaginatedResponse(
    val page: Int = 1,
    val results: List<TmdbMovieItem> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "total_results") val totalResults: Int = 0
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    val id: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbCast(
    val id: Long,
    val name: String,
    val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCredits(
    val cast: List<TmdbCast>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonSummary(
    val id: Long,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "poster_path") val posterPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDetailResponse(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    val genres: List<TmdbGenre>? = null,
    val credits: TmdbCredits? = null,
    val seasons: List<TmdbSeasonSummary>? = null,
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "original_language") val originalLanguage: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbEpisodeItem(
    val id: Long,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @Json(name = "still_path") val stillPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonDetails(
    val id: Long,
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    val episodes: List<TmdbEpisodeItem> = emptyList()
)

interface TmdbApi {
    @GET("trending/all/day")
    suspend fun getTrending(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse

    @GET("tv/popular")
    suspend fun getPopularTv(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse

    @GET("discover/movie")
    suspend fun getDiscoverMovies(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null
    ): TmdbPaginatedResponse

    @GET("discover/tv")
    suspend fun getDiscoverTv(
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1,
        @Query("with_genres") withGenres: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null
    ): TmdbPaginatedResponse

    @GET("search/multi")
    suspend fun searchMulti(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: String,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "credits"
    ): TmdbDetailResponse

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: String,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "credits"
    ): TmdbDetailResponse

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeason(
        @Path("tv_id") tvId: String,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String
    ): TmdbSeasonDetails

    @GET("movie/{movie_id}/recommendations")
    suspend fun getMovieRecommendations(
        @Path("movie_id") movieId: String,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse

    @GET("tv/{tv_id}/recommendations")
    suspend fun getTvRecommendations(
        @Path("tv_id") tvId: String,
        @Query("api_key") apiKey: String,
        @Query("page") page: Int = 1
    ): TmdbPaginatedResponse
}

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3/"
    const val IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500"
    const val IMAGE_BASE_W780 = "https://image.tmdb.org/t/p/w780"
    const val IMAGE_BASE_ORIGINAL = "https://image.tmdb.org/t/p/original"
    const val DEFAULT_TMDB_API_KEY = "2c46288716a18fb7aadcc2a801f3fc6b"

    val api: TmdbApi by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApi::class.java)
    }

    fun mapItemToMovieSubject(item: TmdbMovieItem): MovieSubject {
        val isTv = item.mediaType == "tv" || (item.title == null && item.name != null)
        val title = item.title ?: item.name ?: "Unknown"
        val posterPath = item.posterPath ?: item.backdropPath ?: ""
        val posterUrl = if (posterPath.isNotEmpty()) "$IMAGE_BASE_W500$posterPath" else ""
        val rawDate = item.releaseDate ?: item.firstAirDate ?: "2026"
        val year = if (rawDate.length >= 4) rawDate.substring(0, 4) else "2026"
        val ratingStr = item.voteAverage?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "8.0"

        val prefix = if (isTv) "tmdb_tv_" else "tmdb_movie_"
        return MovieSubject(
            id = "$prefix${item.id}",
            name = title,
            poster = posterUrl,
            description = item.overview ?: "",
            rating = ratingStr,
            year = year,
            genres = emptyList(),
            isTvShow = isTv,
            totalSeasons = 1,
            duration = "120 min",
            cast = emptyList(),
            dubs = listOf("Original", "English", "Hindi"),
            seasonList = emptyList(),
            resourceDetectors = emptyList()
        )
    }

    fun mapDetailToMovieSubject(detail: TmdbDetailResponse, isTv: Boolean): MovieSubject {
        val title = detail.title ?: detail.name ?: "Unknown"
        val posterPath = detail.posterPath ?: detail.backdropPath ?: ""
        val posterUrl = if (posterPath.isNotEmpty()) "$IMAGE_BASE_W500$posterPath" else ""
        val rawDate = detail.releaseDate ?: detail.firstAirDate ?: "2026"
        val year = if (rawDate.length >= 4) rawDate.substring(0, 4) else "2026"
        val ratingStr = detail.voteAverage?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "8.0"
        val durationStr = when {
            detail.runtime != null && detail.runtime > 0 -> "${detail.runtime} min"
            detail.episodeRunTime?.isNotEmpty() == true -> "${detail.episodeRunTime[0]} min"
            else -> if (isTv) "45 min" else "120 min"
        }

        val castList = detail.credits?.cast?.take(15)?.map { c ->
            val avatarUrl = if (!c.profilePath.isNullOrEmpty()) "$IMAGE_BASE_W500${c.profilePath}" else ""
            CastMember(name = c.name, role = c.character ?: "Cast", avatar = avatarUrl)
        } ?: emptyList()

        val seasonList = if (isTv && detail.seasons != null) {
            detail.seasons.filter { it.seasonNumber > 0 }.map { s ->
                SeasonInfo(
                    season = s.seasonNumber,
                    episodeList = (1..(s.episodeCount ?: 1)).map { epNum ->
                        EpisodeInfo(
                            episode = epNum,
                            title = "Episode $epNum",
                            duration = durationStr,
                            stillPath = if (!s.posterPath.isNullOrEmpty()) "$IMAGE_BASE_W500${s.posterPath}" else posterUrl
                        )
                    }
                )
            }
        } else emptyList()

        val genresList = detail.genres?.map { it.name } ?: emptyList()
        val totalSeasons = detail.numberOfSeasons ?: (seasonList.size.coerceAtLeast(1))
        val prefix = if (isTv) "tmdb_tv_" else "tmdb_movie_"

        val isAnimeOrAnimation = genresList.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) } ||
                detail.originCountry?.any { it.equals("JP", ignoreCase = true) } == true ||
                title.contains("Anime", ignoreCase = true) ||
                title.contains("Naruto", ignoreCase = true) ||
                title.contains("Dragon Ball", ignoreCase = true)

        val detectors = if (isAnimeOrAnimation) {
            listOf(
                ResourceDetector(
                    resourceId = "animedubhindi",
                    name = "AnimeDubHindi (Hindi Dub)"
                )
            )
        } else {
            emptyList()
        }

        return MovieSubject(
            id = "$prefix${detail.id}",
            name = title,
            poster = posterUrl,
            description = detail.overview ?: "",
            rating = ratingStr,
            year = year,
            genres = genresList,
            isTvShow = isTv,
            totalSeasons = totalSeasons,
            duration = durationStr,
            cast = castList,
            seasonList = seasonList,
            resourceDetectors = detectors
        )
    }

    fun mapSeasonDetailsToEpisodes(subjectId: String, seasonDetails: TmdbSeasonDetails): List<Episode> {
        val seasonNum = seasonDetails.seasonNumber
        return seasonDetails.episodes.map { ep ->
            val stillUrl = if (!ep.stillPath.isNullOrEmpty()) "$IMAGE_BASE_W500${ep.stillPath}" else ""
            Episode(
                episodeId = "${subjectId}_S${seasonNum}_E${ep.episodeNumber}",
                title = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
                episodeNumber = ep.episodeNumber,
                seasonNumber = seasonNum,
                duration = if (ep.runtime != null && ep.runtime > 0) "${ep.runtime} min" else "45 min",
                stillPath = stillUrl
            )
        }
    }
}
