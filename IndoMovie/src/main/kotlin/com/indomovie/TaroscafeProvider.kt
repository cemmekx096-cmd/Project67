package com.indomovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TaroscafeProvider : MainAPI() {
    override var mainUrl = "https://taroscafe.com"
    override var name = "Taroscafe"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        // Kategori yang HARUS diblokir total (hentai/semi/18+)
        private val blockedCategorySlugs = listOf(
            "hentai",
            "film-semi",
            "film-semi-jepang",
            "semi-jepang",
            "film-bokep-jepang"
        )

        // Regex: judul yang cuma berisi kode ala JAV, misal "JUR-440", "GH-838 (2025)"
        private val codeTitleRegex = Regex("""(?i)^[a-z]{2,6}-\d{2,5}(\s*\(\d{4}\))?$""")

        private var blockedSlugsCache: MutableSet<String>? = null
        private var blockedSlugsCacheTime: Long = 0
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 jam

        private fun titleLooksBlocked(title: String): Boolean {
            val lower = title.lowercase()
            if (lower.contains("semi") || lower.contains("18+")) return true
            if (codeTitleRegex.matches(title.trim())) return true
            return false
        }
    }

    // Ambil semua slug URL film yang tercatat di kategori terlarang, lalu di-cache
    private suspend fun getBlockedSlugs(): Set<String> {
        val now = System.currentTimeMillis()
        blockedSlugsCache?.let {
            if (now - blockedSlugsCacheTime < CACHE_TTL_MS) return it
        }

        val slugs = mutableSetOf<String>()
        blockedCategorySlugs.forEach { catSlug ->
            try {
                for (p in 1..3) {
                    val url = if (p == 1) "$mainUrl/category/$catSlug/" else "$mainUrl/category/$catSlug/page/$p/"
                    val doc = app.get(url, timeout = 20L).document
                    val items = doc.select("article.item-infinite")
                    if (items.isEmpty()) break
                    items.forEach { el ->
                        val href = el.selectFirst("a[itemprop=url]")?.attr("href")
                        if (!href.isNullOrBlank()) {
                            slugs.add(href.trimEnd('/').substringAfterLast("/"))
                        }
                    }
                }
            } catch (e: Exception) {
                // kategori mungkin gak ada / gagal fetch, skip aja
            }
        }
        blockedSlugsCache = slugs
        blockedSlugsCacheTime = now
        return slugs
    }

    override val mainPage = mainPageOf(
        "country/indonesia" to "Film Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        val document = app.get(url, timeout = 30L).document
        val blockedSlugs = getBlockedSlugs()

        val home = document.select("article.item-infinite").mapNotNull {
            it.toSearchResult(blockedSlugs)
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(blockedSlugs: Set<String>): SearchResponse? {
        val linkEl = this.selectFirst("a[itemprop=url]") ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val slug = href.trimEnd('/').substringAfterLast("/")
        val title = linkEl.attr("title").ifBlank { this.selectFirst("h2, h3")?.text() ?: "" }
            .removePrefix("Permalink to:").trim()

        // Filter 1: cross-reference kategori terlarang
        if (slug in blockedSlugs) return null
        // Filter 2: regex judul / kata semi-18+
        if (titleLooksBlocked(title)) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 30L).document
        val blockedSlugs = getBlockedSlugs()
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult(blockedSlugs) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore("|")?.trim()
            ?: document.selectFirst("h1")?.text().orEmpty()
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.entry-content[itemprop=description] p")?.text()?.trim()

        val genres = document.select("div.gmr-moviedata").firstOrNull { it.text().startsWith("Genre") }
            ?.select("a")?.map { it.text() } ?: emptyList()

        // Safety net terakhir: kalau ternyata genre-nya semi/hentai walau lolos filter listing, blokir di sini juga
        if (genres.any { g -> g.lowercase().let { it.contains("semi") || it.contains("18+") || it == "hentai" } }
            || titleLooksBlocked(title)) {
            throw ErrorLoadingException("Konten diblokir")
        }

        val rating = document.select("div.gmr-rating-item").text()
            .replace(Regex("[^0-9.]"), "").toDoubleOrNull()
        val duration = document.selectFirst("div.gmr-duration-item")?.text()
            ?.replace(Regex("\\D"), "")?.toIntOrNull()
        val trailer = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        // post_id dibutuhkan buat AJAX ambil server 2-5 nanti di loadLinks
        val postId = document.selectFirst("div.gmr-server-wrap")?.attr("data-id")

        return newMovieLoadResponse(title, url, TvType.Movie, "$url|||${postId.orEmpty()}") {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.duration = duration ?: 0
            addTrailer(trailer)
            if (rating != null) this.score = Score.from10(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (url, postId) = data.split("|||").let { it[0] to it.getOrNull(1) }
        val document = app.get(url, timeout = 30L).document

        // Server 1 (Abys) udah langsung ke-embed di HTML awal -> kita skip sesuai kesepakatan
        document.select("div#p1 iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("abyss", true)) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }

        if (!postId.isNullOrBlank()) {
            listOf("p2", "p3", "p4", "p5").forEach { tab ->
                try {
                    val res = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tab,
                            "post_id" to postId
                        ),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        referer = url
                    ).document

                    res.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotBlank()) loadExtractor(src, url, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // server itu mungkin gak tersedia buat judul ini, lanjut ke tab berikutnya
                }
            }
        }

        return true
    }
}
