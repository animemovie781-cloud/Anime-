package com.example.data.network

import android.util.Log
import com.example.data.model.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class AnimeDubSearchResult(
    val title: String,
    val url: String,
    val poster: String? = null
)

private data class RawQualityLink(
    val quality: String,
    val size: String,
    val url: String
)

class AnimeDubHindiProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
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

    suspend fun search(query: String, season: Int = 1): List<AnimeDubSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val queries = mutableListOf<String>()
        // Prioritize Season-specific query if season > 1
        if (season > 1) {
            queries.add("$cleanQuery Season $season")
            queries.add("$cleanQuery S$season")
        }
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
            val aPattern = Pattern.compile("<h[23][^>]*>\\s*<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL)
            val aMatcher = aPattern.matcher(articleHtml)
            if (aMatcher.find()) {
                val href = aMatcher.group(1) ?: ""
                val rawTitle = aMatcher.group(2) ?: ""
                val cleanTitle = rawTitle.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()

                val imgPattern = Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"']", Pattern.DOTALL)
                val imgMatcher = imgPattern.matcher(articleHtml)
                val poster = if (imgMatcher.find()) imgMatcher.group(1) else null

                if (href.isNotEmpty() && cleanTitle.isNotEmpty()) {
                    results.add(AnimeDubSearchResult(cleanTitle, href, poster))
                }
            }
        }

        // Fallback: match any anchor with anime/hindi
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

            // 1. Check for intermediate adhlinks.com page
            val adhLinkPattern = Pattern.compile("href=[\"'](https?://[^\"']*adhlinks\\.com/[^\"']+)[\"']")
            val adhMatcher = adhLinkPattern.matcher(html)
            val adhUrl = if (adhMatcher.find()) adhMatcher.group(1) else null

            val targetHtml = if (adhUrl != null) {
                try {
                    val adhReq = Request.Builder()
                        .url(adhUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                        .build()
                    val adhRes = client.newCall(adhReq).execute()
                    if (adhRes.isSuccessful) adhRes.body?.string() ?: html else html
                } catch (e: Exception) {
                    html
                }
            } else {
                html
            }

            // 2. Extract qualities and links for the requested episode
            val rawQualities = extractQualitiesFromHtml(targetHtml, episode)
            Log.i(TAG, "Extracted ${rawQualities.size} raw quality links for episode $episode from $postUrl")

            // 3. Resolve direct streams concurrently with timeout
            val deferredList = rawQualities.map { rql ->
                async(Dispatchers.IO) {
                    if (rql.url.contains("hubcloud.ist/drive/")) {
                        val resolved = withTimeoutOrNull(4000L) {
                            resolveHubCloudDirectStream(rql.url)
                        }
                        if (resolved != null && resolved.isNotEmpty()) {
                            resolved.map { ds ->
                                StreamItem(
                                    quality = "${rql.quality} (AnimeDubHindi)",
                                    url = ds,
                                    size = rql.size,
                                    cookie = ""
                                )
                            }
                        } else {
                            listOf(
                                StreamItem(
                                    quality = "${rql.quality} (AnimeDubHindi)",
                                    url = rql.url,
                                    size = rql.size,
                                    cookie = ""
                                )
                            )
                        }
                    } else {
                        listOf(
                            StreamItem(
                                quality = "${rql.quality} (AnimeDubHindi)",
                                url = rql.url,
                                size = rql.size,
                                cookie = ""
                            )
                        )
                    }
                }
            }

            val resolvedGroups = deferredList.awaitAll()
            for (group in resolvedGroups) {
                for (item in group) {
                    if (!streamItems.any { it.url == item.url }) {
                        streamItems.add(item)
                    }
                }
            }

            // If still empty, check any direct links in html
            if (streamItems.isEmpty()) {
                val directPattern = Pattern.compile("href=[\"'](https?://[^\"']*(?:hubcloud|pixeldrain|gdflix|cloudflarestorage|m3u8|mp4)[^\"']*)[\"']")
                val dMatcher = directPattern.matcher(targetHtml)
                var idx = 0
                while (dMatcher.find() && idx < 3) {
                    val url = dMatcher.group(1) ?: continue
                    val q = if (idx == 0) "1080p (AnimeDubHindi)" else if (idx == 1) "720p (AnimeDubHindi)" else "480p (AnimeDubHindi)"
                    streamItems.add(StreamItem(quality = q, url = url, size = "HD", cookie = ""))
                    idx++
                }
            }

            // Ensure distinct qualities are sorted 1080p -> 720p -> 480p
            streamItems.sortByDescending {
                if (it.quality.contains("1080")) 1080
                else if (it.quality.contains("720")) 720
                else if (it.quality.contains("480")) 480
                else 0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting stream links for $postUrl", e)
        }
        streamItems
    }

    private fun extractQualitiesFromHtml(html: String, targetEpisode: Int): List<RawQualityLink> {
        val results = mutableListOf<RawQualityLink>()

        // 1. Movie blocks with pro-ep-quality
        val moviePattern = Pattern.compile(
            "<span[^>]*pro-ep-quality[^>]*>.*?</div>\\s*<div[^>]*pro-btn-group[^>]*>.*?</div>",
            Pattern.DOTALL
        )
        val mMatcher = moviePattern.matcher(html)
        var foundMovie = false
        while (mMatcher.find()) {
            foundMovie = true
            val block = mMatcher.group(0) ?: continue
            val qPattern = Pattern.compile("pro-ep-quality[^>]*>\\[?(480[pP]|720[pP]|1080[pP]|2160[pP]|4[kK])[^<\\]]*\\]?")
            val qM = qPattern.matcher(block)
            val rawQ = if (qM.find()) qM.group(1)?.uppercase() ?: "HD" else "HD"
            val quality = if (!rawQ.endsWith("P") && rawQ != "4K") "${rawQ}P" else rawQ

            val sPattern = Pattern.compile("pro-ep-size[^>]*>💾?\\s*([^<]+)")
            val sM = sPattern.matcher(block)
            val size = if (sM.find()) sM.group(1)?.trim() ?: "HD" else "HD"

            val linkPattern = Pattern.compile("href=[\"']([^\"']+)[\"']")
            val lM = linkPattern.matcher(block)
            while (lM.find()) {
                val link = lM.group(1) ?: continue
                if (isStreamingHost(link)) {
                    results.add(RawQualityLink(quality, size, link))
                    break // pick primary host per quality block
                }
            }
        }
        if (foundMovie && results.isNotEmpty()) {
            return results
        }

        // 2. Series with H4 blocks (Each H4 represents an episode)
        val h4Pattern = Pattern.compile("<h4[^>]*>(.*?)</h4>", Pattern.DOTALL)
        val h4Matcher = h4Pattern.matcher(html)
        val h4Blocks = mutableListOf<String>()
        while (h4Matcher.find()) {
            val content = h4Matcher.group(1) ?: continue
            if (content.contains("480P", ignoreCase = true) ||
                content.contains("720P", ignoreCase = true) ||
                content.contains("1080P", ignoreCase = true)
            ) {
                h4Blocks.add(content)
            }
        }

        if (h4Blocks.isNotEmpty()) {
            val epIndex = (targetEpisode - 1).coerceIn(0, h4Blocks.size - 1)
            val selectedBlock = h4Blocks[epIndex]

            // Split into sections by quality (480P, 720P, 1080P, 4K)
            val parts = selectedBlock.split(Regex("(?=\\b(?:480[pP]|720[pP]|1080[pP]|2160[pP]|4[kK])\\b)"))
            for (part in parts) {
                val qMatch = Regex("\\b(480[pP]|720[pP]|1080[pP]|2160[pP]|4[kK])\\b").find(part)
                if (qMatch != null) {
                    val rawQ = qMatch.value.uppercase()
                    val quality = if (!rawQ.endsWith("P") && rawQ != "4K") "${rawQ}P" else rawQ
                    val sizeMatch = Regex("\\[Size:\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE).find(part)
                    val size = sizeMatch?.groupValues?.get(1)?.trim() ?: "HD"

                    val linkPattern = Pattern.compile("href=[\"']([^\"']+)[\"']")
                    val lMatcher = linkPattern.matcher(part)
                    while (lMatcher.find()) {
                        val link = lMatcher.group(1) ?: continue
                        if (isStreamingHost(link)) {
                            results.add(RawQualityLink(quality, size, link))
                            break
                        }
                    }
                }
            }
        }

        // 3. Fallback: Scan full html for any quality labeled links
        if (results.isEmpty()) {
            val genericQualities = listOf("1080P", "720P", "480P")
            for (q in genericQualities) {
                val pattern = Pattern.compile("$q.*?href=[\"']([^\"']*(?:hubcloud|pixeldrain|gdflix)[^\"']*)[\"']", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(html)
                if (matcher.find()) {
                    val link = matcher.group(1) ?: continue
                    results.add(RawQualityLink(q, "HD", link))
                }
            }
        }

        return results
    }

    private fun isStreamingHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("hubcloud") ||
                lower.contains("gdflix") ||
                lower.contains("pixeldrain") ||
                lower.contains("cloudflarestorage") ||
                lower.contains("desidubanime") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mp4")
    }

    private fun resolveHubCloudDirectStream(hubcloudUrl: String): List<String> {
        val directUrls = mutableListOf<String>()
        try {
            val req = Request.Builder()
                .url(hubcloudUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0")
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return directUrls
            val html = res.body?.string() ?: ""

            // Look for gamerxyt direct link generator
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
                    // Look for pixel.hubcloud or cloudflarestorage or pixeldrain direct link
                    val streamPattern = Pattern.compile("href=[\"'](https?://[^\"']*(?:pixel\\.hubcloud|cloudflarestorage|pixeldrain)[^\"']*)[\"']")
                    val streamMatcher = streamPattern.matcher(gHtml)
                    while (streamMatcher.find()) {
                        val sUrl = streamMatcher.group(1) ?: continue
                        if (!directUrls.contains(sUrl)) {
                            directUrls.add(sUrl)
                        }
                    }
                }
            }

            // Direct pixel or cloudflare link in hubcloud
            val directPattern = Pattern.compile("href=[\"'](https?://[^\"']*(?:pixel\\.hubcloud|cloudflarestorage|pixeldrain)[^\"']*)[\"']")
            val dMatcher = directPattern.matcher(html)
            while (dMatcher.find()) {
                val dUrl = dMatcher.group(1) ?: continue
                if (!directUrls.contains(dUrl)) {
                    directUrls.add(dUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hubcloud resolution error for $hubcloudUrl: ${e.message}")
        }
        return directUrls
    }

    suspend fun resolveStreamForTitle(
        title: String,
        season: Int = 1,
        episode: Int = 1
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val searchResults = search(title, season)
        if (searchResults.isEmpty()) {
            Log.w(TAG, "No AnimeDubHindi results for '$title'")
            return@withContext emptyList()
        }

        // Match season
        val matched = searchResults.firstOrNull {
            it.title.contains("Season $season", ignoreCase = true) ||
            it.title.contains("S0$season", ignoreCase = true) ||
            it.title.contains("S$season", ignoreCase = true)
        } ?: searchResults.first()

        Log.i(TAG, "Matched AnimeDubHindi post: ${matched.title} -> ${matched.url}")
        getStreamLinks(matched.url, season, episode)
    }
}
