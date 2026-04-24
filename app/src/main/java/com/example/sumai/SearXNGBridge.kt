package com.example.sumai.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.io.IOException

class SearXNGBridge {

    private val client = OkHttpClient()

    // Публичные серверы SearXNG
    private val searxInstances = listOf(
        "https://searx.be",
        "https://searx.tiekoetter.com"
    )

    data class SearchResult(
        val title: String,
        val link: String,
        val snippet: String
    )

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        for (instance in searxInstances) {
            try {
                val results = searchOnInstance(instance, query)
                if (results.isNotEmpty()) {
                    return@withContext formatResults(results, query)
                }
            } catch (e: Exception) {
                continue
            }
        }
        return@withContext "По запросу \"$query\" ничего не найдено."
    }

    private fun searchOnInstance(instance: String, query: String): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$instance/search?q=$encodedQuery&format=html"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0")
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return emptyList()

        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()

        val resultElements = doc.select(".result, .web-result")
        for (element in resultElements) {
            val titleElement = element.selectFirst("h3 a, a.result-title")
            val title = titleElement?.text()?.trim() ?: continue
            val link = titleElement?.attr("href")?.trim() ?: continue
            val snippet = element.selectFirst(".content, .result-content")?.text()?.trim() ?: ""

            results.add(SearchResult(title, link, snippet))
            if (results.size >= 3) break
        }

        return results
    }

    private fun formatResults(results: List<SearchResult>, query: String): String {
        return buildString {
            append("Результаты поиска по запросу: \"$query\"\n\n")
            results.forEach { result ->
                append("📄 ${result.title}\n")
                append("🔗 ${result.link}\n")
                append("📝 ${result.snippet}\n\n")
            }
        }
    }
}