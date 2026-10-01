package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.Verification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class SearchResult(val title: String, val url: String, val snippet: String)

interface WebSearchProvider {
    val name: String
    suspend fun search(query: String, limit: Int): List<SearchResult>
}

// Keyless HTML endpoint. Parsing is best-effort: if the markup changes it yields no results
// and the next provider is tried.
class DuckDuckGoSearch(private val http: HttpGet = DefaultHttpGet) : WebSearchProvider {
    override val name = "DuckDuckGo"

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        parse(http(ENDPOINT + URLEncoder.encode(query, "UTF-8")), limit)

    companion object {
        private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="
        private val link = Regex(
            "<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        private val snippet = Regex(
            "class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</(?:a|div|td)>",
            RegexOption.DOT_MATCHES_ALL
        )

        fun parse(html: String, limit: Int): List<SearchResult> {
            val snippets = snippet.findAll(html).map { HtmlText.inline(it.groupValues[1]) }.toList()
            return link.findAll(html).toList().mapIndexedNotNull { i, m ->
                val url = resolveLink(HtmlText.decodeEntities(m.groupValues[1])) ?: return@mapIndexedNotNull null
                val title = HtmlText.inline(m.groupValues[2])
                if (title.isBlank()) null else SearchResult(title, url, snippets.getOrElse(i) { "" })
            }.distinctBy { it.url }.take(limit)
        }

        // Unwraps DuckDuckGo's redirect links and drops ads and internal links.
        fun resolveLink(href: String): String? {
            val absolute = if (href.startsWith("//")) "https:$href" else href
            val wrapped = Regex("[?&]uddg=([^&]+)").find(absolute)?.groupValues?.get(1)
            val target = wrapped?.let { URLDecoder.decode(it, "UTF-8") } ?: absolute
            if (!target.startsWith("http://") && !target.startsWith("https://")) return null
            val host = runCatching { URI(target).host }.getOrNull()?.lowercase(Locale.US) ?: return null
            return if (host.endsWith("duckduckgo.com")) null else target
        }
    }
}

// Fallback provider with a stable JSON API (encyclopedic topics only).
class WikipediaSearch(private val http: HttpGet = DefaultHttpGet) : WebSearchProvider {
    override val name = "Wikipedia"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val json = JSONObject(http("$ENDPOINT&srlimit=$limit&srsearch=" + URLEncoder.encode(query, "UTF-8")))
        val hits = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.map { hit ->
            val title = hit.optString("title")
            SearchResult(
                title = title,
                url = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                snippet = HtmlText.inline(hit.optString("snippet"))
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json"
    }
}

// Tries providers in order. Throws only when every provider failed, so a network outage is
// reported as a failure and never as "no results".
class WebSearchClient(
    private val providers: List<WebSearchProvider> = listOf(DuckDuckGoSearch(), WikipediaSearch())
) {
    data class Response(val provider: String, val results: List<SearchResult>)

    suspend fun search(query: String, limit: Int): Response {
        var lastError: Exception? = null
        var anyResponded = false
        for (provider in providers) {
            try {
                val results = provider.search(query, limit)
                anyResponded = true
                if (results.isNotEmpty()) return Response(provider.name, results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anyResponded) throw java.io.IOException("web search is unavailable (${lastError?.message ?: "no provider"})")
        return Response(providers.first().name, emptyList())
    }
}

class WebSearchTool(private val client: WebSearchClient = WebSearchClient()) : JaxTool {
    override val name = "web_search"
    override val description =
        "Search the web. Returns result titles, URLs and snippets (not page content; use web_fetch for that)."
    override val parameters = listOf(
        ToolParam("query", "string", "What to search for", true),
        ToolParam("limit", "number", "Maximum results, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)")
    )
    override val isDestructive = false
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult.fatal("query is required")
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val response = client.search(query, limit)
        val results = JSONArray()
        response.results.forEach {
            results.put(JSONObject().put("title", it.title).put("url", it.url).put("snippet", it.snippet))
        }
        val data = JSONObject().put("query", query).put("provider", response.provider).put("results", results)
        if (response.results.isEmpty()) return ToolResult.ok("I couldn't find any web results for \"$query\".", data)
        val message = buildString {
            append("Top web results for \"$query\" (${response.provider}):")
            response.results.forEachIndexed { i, r ->
                append("\n${i + 1}. ${r.title} — ${r.url}")
                if (r.snippet.isNotBlank()) append("\n   ${r.snippet.take(SNIPPET_CHARS)}")
            }
        }
        return ToolResult.ok(message, data)
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val data = result.data ?: return Verification.failed("no search data returned")
        val count = data.optJSONArray("results")?.length() ?: 0
        return Verification.verified(
            if (count > 0) "$count live result(s) from ${data.optString("provider")}" else "the search completed with no results"
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 8
        private const val SNIPPET_CHARS = 200

        fun parseResults(data: JSONObject?): List<SearchResult> {
            val arr = data?.optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                SearchResult(it.optString("title"), it.optString("url"), it.optString("snippet"))
            }.filter { it.url.isNotBlank() }
        }
    }
}

class BlockedUrlException(message: String) : Exception(message)

// Only public http(s) pages may be fetched: blocks local/private network targets (SSRF).
object UrlSafety {
    fun check(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "not a valid URL"
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return "only http(s) pages can be fetched"
        val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return "the URL has no host"
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) return "local network addresses are not fetched"
        if (host.contains(':') || host.startsWith("[") || host.all { it.isDigit() || it == '.' } || host.startsWith("0x")) {
            return "IP-address URLs are not fetched"
        }
        return null
    }

    fun isPrivate(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")
}

// Fetch with redirects followed manually so every hop is re-checked against UrlSafety.
val SafeFetchHttpGet: HttpGet = { url -> withContext(Dispatchers.IO) { fetchPage(url) } }

private const val MAX_REDIRECTS = 3

private fun fetchPage(start: String): String {
    var url = start
    var hops = 0
    while (hops++ <= MAX_REDIRECTS) {
        UrlSafety.check(url)?.let { throw BlockedUrlException(it) }
        val host = URI(url).host
        if (InetAddress.getAllByName(host).any(UrlSafety::isPrivate)) {
            throw BlockedUrlException("that address points to a private network")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", HTTP_USER_AGENT)
            setRequestProperty("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IllegalStateException("HTTP $code without a location")
                url = URL(URL(url), location).toString()
                continue
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val type = connection.contentType.orEmpty().lowercase(Locale.US)
            if (type.isNotEmpty() && listOf("html", "text", "xml").none { type.contains(it) }) {
                throw BlockedUrlException("not a readable page ($type)")
            }
            return readCapped(connection.inputStream, HTTP_MAX_CHARS)
        } finally {
            connection.disconnect()
        }
    }
    throw IllegalStateException("too many redirects")
}

class WebFetchTool(private val http: HttpGet = SafeFetchHttpGet) : JaxTool {
    override val name = "web_fetch"
    override val description =
        "Fetch a public web page and return its title, readable text and the passages most relevant to a query."
    override val parameters = listOf(
        ToolParam("url", "string", "The http(s) URL to read", true),
        ToolParam("query", "string", "What to look for on the page")
    )
    override val isDestructive = false
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JSONObject): ToolResult {
        val url = args.optString("url").trim()
        UrlSafety.check(url)?.let { return ToolResult.fatal("Can't read $url: $it.") }
        val html = try {
            http(url)
        } catch (e: BlockedUrlException) {
            return ToolResult.fatal("Can't read $url: ${e.message}.")
        }
        val title = HtmlText.title(html).ifBlank { url }
        val text = HtmlText.toText(html)
        if (text.isBlank()) return ToolResult.fatal("$url has no readable text.")
        val query = args.optString("query").trim()
        val passages = if (query.isBlank()) text.lines().filter { it.length >= PassageExtractor.MIN_CHUNK }.take(3)
        else PassageExtractor.relevant(text, query)
        return ToolResult.ok(
            "Read \"$title\" ($url).",
            JSONObject()
                .put("url", url)
                .put("title", title)
                .put("passages", JSONArray(passages))
                .put("text", text.take(MAX_TEXT_CHARS))
                .put("chars", text.length)
        )
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val chars = result.data?.optInt("chars", 0) ?: 0
        return if (chars > 0) Verification.verified("page retrieved ($chars characters)")
        else Verification.failed("the page had no readable text")
    }

    private companion object {
        const val MAX_TEXT_CHARS = 4_000
    }
}

// Minimal HTML-to-text conversion; enough to read articles without a parser dependency.
object HtmlText {
    private val options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val dropped = Regex("<(script|style|noscript|svg|head|template|iframe|nav|footer)\\b[^>]*>.*?</\\1\\s*>", options)
    private val lineBreaks = Regex("<(?:br|p|li|tr|h[1-6]|/p|/div|/li|/tr|/h[1-6]|/section|/article|/blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^>]+>")
    private val titleTag = Regex("<title[^>]*>(.*?)</title>", options)
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]{1,6});")
    private val spaces = Regex("[ \\t\\u00A0]+")

    fun title(html: String): String = titleTag.find(html)?.groupValues?.get(1)?.let(::inline).orEmpty()

    fun toText(html: String): String {
        val stripped = html.replace(comments, " ").replace(dropped, " ").replace(lineBreaks, "\n").replace(tags, " ")
        return decodeEntities(stripped).lines()
            .map { it.replace(spaces, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun inline(fragment: String): String =
        decodeEntities(fragment.replace(tags, "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(text: String): String = numericEntity.replace(text) { m ->
        val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
        if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else " "
    }
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

// Deterministic relevance: keeps the page chunks that mention the most query terms.
object PassageExtractor {
    const val MIN_CHUNK = 40
    private const val MAX_CHUNK = 500
    private val stopWords = setOf(
        "the", "and", "for", "with", "what", "which", "that", "this", "from", "are", "was", "were", "how", "why",
        "when", "who", "about", "into", "your", "you", "latest", "find", "search", "compare", "between", "does",
        "tell", "information", "research", "look", "versus", "near", "best", "top"
    )

    fun terms(query: String): List<String> =
        query.lowercase(Locale.US).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in stopWords }.distinct()

    fun relevant(text: String, query: String, max: Int = 3, maxChars: Int = 400): List<String> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        return text.lines().flatMap(::chunk).filter { it.length >= MIN_CHUNK }
            .mapIndexed { index, chunk -> Triple(index, chunk, terms.count { chunk.lowercase(Locale.US).contains(it) }) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(max)
            .sortedBy { it.first }
            .map { it.second.take(maxChars) }
    }

    private fun chunk(line: String): List<String> {
        if (line.length <= MAX_CHUNK) return listOf(line)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        line.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
            if (current.isNotEmpty() && current.length + sentence.length > MAX_CHUNK) {
                out += current.toString().trim()
                current.clear()
            }
            current.append(sentence).append(' ')
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }
}
