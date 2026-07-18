package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.ArrayList

val app = Injekt.get<AppApi>()

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.do"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Anime
    )

    private val apiUrl = "$mainUrl/api"

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=1" to "Popular",
        "&type=0&sub=0&country=0&status=0&order=2" to "Latest Update",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get("$apiUrl/DramaList/List?page=$page${request.data}")
            .parsedSafe<Responses>()?.data
            ?.mapNotNull { media ->
                media.toSearchResponse()
            } ?: throw ErrorLoadingException("Invalid Json response")
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        return newAnimeSearchResponse(
            title ?: return null,
            "$title/$id",
            TvType.TvSeries,
        ) {
            this.posterUrl = thumbnail
            addSub(episodesCount)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse =
            app.get("$apiUrl/DramaList/Search?q=$query&type=0", referer = "$mainUrl/").text
        return tryParseJson<ArrayList<Media>>(searchResponse)?.mapNotNull { media ->
            media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid Json response")
    }

    private fun getTitle(str: String): String {
        return str.replace(Regex("[^a-zA-Z0-9]"), "-")
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.split("/")
        val res = app.get(
            "$apiUrl/DramaList/Drama/${id.last()}?isq=false",
            referer = "$mainUrl/Drama/${
                getTitle(id.first())
            }?id=${id.last()}"
        ).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json response")

        val episodes = res.episodes?.map { eps ->
            Episode(
                data = Data(res.title, eps.number, res.id, eps.id).toJson(),
                episode = eps.number
            )
        } ?: throw ErrorLoadingException("No Episode")

        return newTvSeriesLoadResponse(
            res.title ?: return null,
            url,
            if (res.type == "Movie" || episodes.size == 1) TvType.Movie else TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = res.thumbnail
            this.year = res.releaseDate?.split("-")?.first()?.toIntOrNull()
            this.plot = res.description
            this.tags = listOf("${res.country}", "${res.status}", "${res.type}")
            this.showStatus = when (res.status) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when (str) {
            "Indonesia" -> "Indonesian"
            else -> str
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = parseJson<Data>(data)

        // ── Step 1: Build kkey for video ───────────────────────────
        val kkey = try {
            KisskhKey.videoKey(loadData.epsId?.toInt() ?: return false)
        } catch (e: Exception) {
            return false
        }

        // ── Step 2: Fetch video sources ────────────────────────────
        app.get(
            "$apiUrl/DramaList/Episode/${loadData.epsId}.png?err=false&ts=null&time=null&kkey=$kkey",
            referer = "$mainUrl/Drama/${getTitle("${loadData.title}")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100"
        ).parsedSafe<Sources>()?.let { source ->
            listOf(source.video, source.thirdParty).apmap { link ->
                safeApiCall {
                    if (link?.contains(".m3u8") == true) {
                        M3u8Helper.generateM3u8(
                            this.name,
                            link,
                            referer = "$mainUrl/",
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    } else if (link != null) {
                        loadExtractor(
                            link.substringBefore("=http"),
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }

        // ── Step 3: Fetch & decrypt subtitles ──────────────────────
        val subKkey = try {
            KisskhKey.subKey(loadData.epsId?.toInt() ?: return true)
        } catch (e: Exception) {
            return true  // continue even if sub fetch fails
        }

        val ctx = context
        if (ctx == null) {
            // Gak dapet context -- skip proses subtitle daripada crash,
            // video tetap jalan tanpa subtitle
            return true
        }

        // Bersihin semua cache subtitle lama dulu sebelum bikin yang baru,
        // biar file GK menumpuk tiap kali ganti episode/judul
        ctx.cacheDir.listFiles { file -> file.name.startsWith("kisskh_sub_") }
            ?.forEach { it.delete() }

        app.get("$apiUrl/Sub/${loadData.epsId}?kkey=$subKkey").text.let { res ->
            tryParseJson<List<Subtitle>>(res)?.forEach { sub ->
                val subUrl = sub.src ?: return@forEach
                val lang = getLanguage(sub.label ?: return@forEach)

                // Fetch raw subtitle
                val rawContent = app.get(subUrl).text

                // Decrypt kalau perlu (deteksi dari ekstensi URL + isi konten)
                val finalContent = KisskhKey.decryptSubtitleContent(rawContent, subUrl)

                // Simpen ke cache file, lokasinya di data/data/<package>/cache
                val cacheFile = java.io.File(
                    ctx.cacheDir,
                    "kisskh_sub_${loadData.epsId}_${lang}.srt"
                )
                cacheFile.writeText(finalContent, Charsets.UTF_8)

                // Kirim path file lokal ke player
                subtitleCallback.invoke(
                    SubtitleFile(lang, "file://${cacheFile.absolutePath}")
                )
            }
        }

        return true
    }

    data class Data(
        val title: String?,
        val eps: Int?,
        val id: Int?,
        val epsId: Int?,
    )

    data class Sources(
        @JsonProperty("Video") val video: String?,
        @JsonProperty("ThirdParty") val thirdParty: String?,
    )

    data class Subtitle(
        @JsonProperty("src") val src: String?,
        @JsonProperty("label") val label: String?,
    )

    data class Responses(
        @JsonProperty("data") val data: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("episodesCount") val episodesCount: Int?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("number") val number: Int?,
        @JsonProperty("sub") val sub: Int?,
    )

    data class MediaDetail(
        @JsonProperty("description") val description: String?,
        @JsonProperty("releaseDate") val releaseDate: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
    )
}
