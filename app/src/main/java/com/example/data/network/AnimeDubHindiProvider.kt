package com.example.data.network

import android.util.Log
import com.example.data.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class AnimeDubSearchResult(
    val title: String,
    val url: String,
    val poster: String? = null
)

class AnimeDubHindiProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "AnimeDubHindiProvider"
        val DOMAINS = listOf(
            "https://www.animedubhindi.link",
            "https://animedubhindi.org",
            "https://www.animedubhindi.cc"
        )
    }

    suspend fun search(query: String): List<AnimeDubSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val queries = mutableListOf<String>()
        queries.add(cleanQuery)
        if (cleanQuery.contains(":")) {
            queries.add(cleanQuery.substringBefore(":").trim())
        }
        if (cleanQuery.contains("-")) {
            queries.add(cleanQuery.substringBefore("-").trim())
        }
        if (cleanQuery.contains("(")) {
            queries.add(cleanQuery.substringBefore("(").trim())
        }
        val words = cleanQuery.split(" ")
        if (words.size > 2) {
            queries.add("${words[0]} ${words[1]}")
        }
        if (words.isNotEmpty() && words[0] != cleanQuery) {
            queries.add(words[0])
        }

        val distinctQueries = queries.distinct().filter { it.isNotBlank() }

        for (domain in DOMAINS) {
            for (q in distinctQueries) {
                try {
                    val encodedQ = java.net.URLEncoder.encode(q, "UTF-8")
                    val searchUrl = "$domain/?s=$encodedQ"
                    val request = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Referer", "https://www.google.com/")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val html = response.body?.string() ?: ""
                        val results = parseSearchResults(html, domain)
                        if (results.isNotEmpty()) {
                            Log.i(TAG, "Found ${results.size} AnimeDubHindi search results for '$q' on $domain")
                            return@withContext results
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error searching AnimeDubHindi on $domain for '$q': ${e.message}")
                }
            }
        }
        emptyList()
    }

    private fun parseSearchResults(html: String, baseUrl: String): List<AnimeDubSearchResult> {
        val results = mutableListOf<AnimeDubSearchResult>()
        // Match articles
        val articlePattern = Pattern.compile("<article[^>]*>(.*?)</article>", Pattern.DOTALL)
        val matcher = articlePattern.matcher(html)

        while (matcher.find()) {
            val articleHtml = matcher.group(1) ?: continue
            // Extract title and href from h2 or h3
            val aPattern = Pattern.compile("<h[23][^>]*>\\s*<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
            val aMatcher = aPattern.matcher(articleHtml)
            if (aMatcher.find()) {
                val href = aMatcher.group(1) ?: ""
                val rawTitle = aMatcher.group(2) ?: ""
                val cleanTitle = rawTitle.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()

                // Extract poster
                val imgPattern = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"']", Pattern.DOTALL)
                val imgMatcher = imgPattern.matcher(articleHtml)
                val poster = if (imgMatcher.find()) imgMatcher.group(1) else null

                if (href.isNotEmpty() && cleanTitle.isNotEmpty()) {
                    results.add(AnimeDubSearchResult(cleanTitle, href, poster))
                }
            }
        }

        // Fallback: match any anchor with class or rel referencing post
        if (results.isEmpty()) {
            val genericPattern = Pattern.compile("<a[^>]*href=[\"'](https?://[^\"']+(?:anime|hindi|season|episode|movie)[^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
            val genMatcher = genericPattern.matcher(html)
            var count = 0
            while (genMatcher.find() && count < 10) {
                val href = genMatcher.group(1) ?: ""
                val text = genMatcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                if (text.length > 5 && !results.any { it.url == href }) {
                    results.add(AnimeDubSearchResult(text, href, null))
                    count++
                }
            }
        }

        return results
    }

    suspend fun getStreamLinks(postUrl: String, season: Int = 1, episode: Int = 1): List<StreamItem> = withContext(Dispatchers.IO) {
        val streamItems = mutableListOf<StreamItem>()
        try {
            val request = Request.Builder()
                .url(postUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", "https://www.animedubhindi.link/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            val html = response.body?.string() ?: ""

            val rawLinks = mutableListOf<String>()

            // Find all anchor links
            val linkPattern = Pattern.compile("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
            val matcher = linkPattern.matcher(html)
            while (matcher.find()) {
                val href = matcher.group(1) ?: continue
                val text = matcher.group(2) ?: ""
                if (isTargetStreamHost(href)) {
                    rawLinks.add(href)
                }
            }

            // Check intermediate adhlinks if present
            val adhLinks = rawLinks.filter { it.contains("adhlinks.com") }
            for (adhUrl in adhLinks.take(3)) {
                try {
                    val adhReq = Request.Builder()
                        .url(adhUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                        .build()
                    val adhRes = client.newCall(adhReq).execute()
                    if (adhRes.isSuccessful) {
                        val adhHtml = adhRes.body?.string() ?: ""
                        val adhMatcher = linkPattern.matcher(adhHtml)
                        while (adhMatcher.find()) {
                            val innerHref = adhMatcher.group(1) ?: continue
                            if (isDirectStreamOrCloudHost(innerHref)) {
                                rawLinks.add(innerHref)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error resolving adhlink $adhUrl: ${e.message}")
                }
            }

            // Now resolve HubCloud / Direct Cloudflare / GDFlix / Pixeldrain
            val distinctLinks = rawLinks.distinct().filter { isDirectStreamOrCloudHost(it) }

            for (link in distinctLinks) {
                // If link is HubCloud, attempt resolving direct download / stream link
                if (link.contains("hubcloud.ist/drive/")) {
                    val directStreams = resolveHubCloudStream(link)
                    for (ds in directStreams) {
                        streamItems.add(ds)
                    }
                } else if (link.contains("pixeldrain") || link.contains("gdflix") || link.contains("r2.cloudflarestorage")) {
                    val quality = if (link.contains("1080")) "1080p (Hindi Dub)"
                        else if (link.contains("720")) "720p (Hindi Dub)"
                        else if (link.contains("480")) "480p (Hindi Dub)"
                        else "HD (Hindi Dub)"
                    streamItems.add(
                        StreamItem(
                            quality = quality,
                            url = link,
                            size = "HD",
                            cookie = ""
                        )
                    )
                }
            }

            // If no streamItems resolved from deep links yet, wrap distinctLinks as stream options
            if (streamItems.isEmpty()) {
                for (link in distinctLinks.take(5)) {
                    val quality = if (link.contains("1080")) "1080p (AnimeDubHindi)"
                        else if (link.contains("720")) "720p (AnimeDubHindi)"
                        else if (link.contains("480")) "480p (AnimeDubHindi)"
                        else "HD (AnimeDubHindi)"
                    streamItems.add(
                        StreamItem(
                            quality = quality,
                            url = link,
                            size = "Dynamic",
                            cookie = ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting stream links for $postUrl", e)
        }
        streamItems
    }

    private fun isTargetStreamHost(href: String): Boolean {
        val lower = href.lowercase()
        return lower.contains("adhlinks") ||
                lower.contains("hubcloud") ||
                lower.contains("gdflix") ||
                lower.contains("pixeldrain") ||
                lower.contains("cloudflarestorage") ||
                lower.contains("fastdl") ||
                lower.contains("filebee") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mp4")
    }

    private fun isDirectStreamOrCloudHost(href: String): Boolean {
        val lower = href.lowercase()
        return lower.contains("hubcloud") ||
                lower.contains("gdflix") ||
                lower.contains("pixeldrain") ||
                lower.contains("cloudflarestorage") ||
                lower.contains("filebee") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mp4") ||
                lower.endsWith(".mkv")
    }

    private fun resolveHubCloudStream(hubcloudUrl: String): List<StreamItem> {
        val items = mutableListOf<StreamItem>()
        try {
            val req = Request.Builder()
                .url(hubcloudUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return items
            val html = res.body?.string() ?: ""

            // Look for gamerxyt direct link generator or direct links
            val tokenPattern = Pattern.compile("href=[\"'](https?://gamerxyt\\.com/hubcloud\\.php\\?[^\"']+)[\"']")
            val tokenMatcher = tokenPattern.matcher(html)
            if (tokenMatcher.find()) {
                val gamerxytUrl = tokenMatcher.group(1) ?: ""
                val gReq = Request.Builder()
                    .url(gamerxytUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                    .header("Referer", hubcloudUrl)
                    .build()
                val gRes = client.newCall(gReq).execute()
                if (gRes.isSuccessful) {
                    val gHtml = gRes.body?.string() ?: ""
                    // Extract Cloudflare R2 / PixelDrain / FSL Server direct stream URL
                    val streamPattern = Pattern.compile("href=[\"'](https?://[^\"']*(?:cloudflarestorage\\.com|pixeldrain\\.dev|pixel\\.hubcloud)[^\"']*)[\"']")
                    val streamMatcher = streamPattern.matcher(gHtml)
                    while (streamMatcher.find()) {
                        val sUrl = streamMatcher.group(1) ?: continue
                        val quality = if (sUrl.contains("1080")) "1080p (AnimeDubHindi)"
                            else if (sUrl.contains("720")) "720p (AnimeDubHindi)"
                            else if (sUrl.contains("480")) "480p (AnimeDubHindi)"
                            else "1080p (AnimeDubHindi Direct)"
                        items.add(
                            StreamItem(
                                quality = quality,
                                url = sUrl,
                                size = "HD",
                                cookie = ""
                            )
                        )
                    }
                }
            }

            // Also check for direct r2 or pixel links in hubcloud directly
            val directPattern = Pattern.compile("href=[\"'](https?://[^\"']*(?:cloudflarestorage|pixel\\.hubcloud|pixeldrain)[^\"']*)[\"']")
            val dMatcher = directPattern.matcher(html)
            while (dMatcher.find()) {
                val dUrl = dMatcher.group(1) ?: continue
                if (!items.any { it.url == dUrl }) {
                    items.add(
                        StreamItem(
                            quality = "720p (AnimeDubHindi Direct)",
                            url = dUrl,
                            size = "HD",
                            cookie = ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hubcloud stream resolution error: ${e.message}")
        }
        return items
    }

    suspend fun resolveStreamForTitle(
        title: String,
        season: Int = 1,
        episode: Int = 1
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val searchResults = search(title)
        if (searchResults.isEmpty()) {
            Log.w(TAG, "No AnimeDubHindi results for '$title'")
            return@withContext emptyList()
        }

        // Match the closest result (first or matching season)
        val matched = searchResults.firstOrNull {
            it.title.contains("Season $season", ignoreCase = true) ||
            it.title.contains("S0$season", ignoreCase = true) ||
            it.title.contains("S$season", ignoreCase = true)
        } ?: searchResults.first()

        Log.i(TAG, "Matched AnimeDubHindi post: ${matched.title} -> ${matched.url}")
        getStreamLinks(matched.url, season, episode)
    }
}
