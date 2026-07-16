package com.indomovie

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// Sama kayak versi awal, mainPage dipangkas cuma 4 kategori yang relevan buat kita
class MovieboxProvider : MainAPI() {
    override var mainUrl = "https://moviebox.ph"
    private val mainAPIUrl = "https://h5-api.aoneroom.com"
    private val secondAPIUrl = "https://filmboom.top"
    override val instantLinkLoading = true
    override var name = "MovieBox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        // Info project Firebase moviebox.ph, kepake buat generate mb_token
        private const val FIREBASE_PROJECT_ID = "mb-seo-f9b99"
        private const val FIREBASE_APP_ID = "1:854587335712:web:da0ea605801a7998114845"
        private const val FIREBASE_API_KEY = "AIzaSyCx80ru6-RXeTi3GvqkFsMVyMf-vpgIoVw"
        private const val FIREBASE_SDK_VERSION = "w:0.6.4"

        private var cachedToken: String? = null
        private var cachedTokenExpiry: Long = 0L
    }

    override val mainPage: List<MainPageData> = mainPageOf(
        "872031290915189720" to "Trending Now",
        "5283462032510044280" to "Latest Indonesian Drama",
        "6528093688173053896" to "Trending Indonesian Movies",
        "5848753831881965888" to "Indonesian Horror Stories",
    )

    // ===== Firebase Installations token (mb_token) =====
    // Token ini dipake sebagai Bearer buat semua request ke h5-api.aoneroom.com.
    // Confirmed: bukan digenerate JS custom, tapi dari Firebase Installations API resmi.
    // Berlaku 7 hari (604800s), jadi kita cache biar gak minta token baru tiap request.
    private fun generateFid(): String {
        // Spesifikasi FID: 17 byte random, 4 bit pertama byte awal diset jadi 0111 (versi 4 random)
        val bytes = ByteArray(17)
        java.security.SecureRandom().nextBytes(bytes)
        bytes[0] = (0x70 or (bytes[0].toInt() and 0x0F)).toByte()
        return base64UrlEncode(bytes).substring(0, 22)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(table[b0 shr 2])
            sb.append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (i + 1 < bytes.size) sb.append(table[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            if (i + 2 < bytes.size) sb.append(table[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    private suspend fun getMbToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedTokenExpiry) return it }

        val res = app.post(
            "https://firebaseinstallations.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/installations",
            headers = mapOf(
                "Content-Type" to "application/json",
                "x-goog-api-key" to FIREBASE_API_KEY
            ),
            requestBody = mapOf(
                "fid" to generateFid(),
                "authVersion" to "FIS_v2",
                "appId" to FIREBASE_APP_ID,
                "sdkVersion" to FIREBASE_SDK_VERSION
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).parsedSafe<InstallationRes>()

        val token = res?.authToken?.token ?: throw ErrorLoadingException("Gagal ambil token MovieBox")
        val expiresInSec = res.authToken.expiresIn?.removeSuffix("s")?.toLongOrNull() ?: 3600L
        cachedToken = token
        cachedTokenExpiry = now + (expiresInSec * 1000) - 60_000L // buffer 1 menit

        return token
    }

    private suspend fun apiHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${getMbToken()}",
        "Content-Type" to "application/json",
        "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
        "X-Request-Lang" to "en"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainAPIUrl/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=12"
        val home = app.get(url, headers = apiHeaders()).parsedSafe<Media>()?.data?.subjectList?.map {
            it.toSearchResponse(this)
        } ?: throw ErrorLoadingException("No Data Found")

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "$mainAPIUrl/wefeed-h5api-bff/subject/search",
            headers = apiHeaders(),
            requestBody = mapOf(
                "keyword" to query,
                "page" to "1",
                "perPage" to 28,
                "subjectType" to 0
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).parsedSafe<Media>()?.data?.items?.map { it.toSearchResponse(this) }
            ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val document = app.get("$secondAPIUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id")
            .parsedSafe<MediaDetail>()?.data
        val subject = document?.subject
        val title = subject?.title ?: ""
        val poster = subject?.cover?.url
        val tags = subject?.genre?.split(",")?.map { it.trim() }
        val year = subject?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (subject?.subjectType == 2) TvType.TvSeries else TvType.Movie
        val description = subject?.description
        val trailer = subject?.trailer?.videoAddress?.url
        val rating = subject?.imdbRatingValue?.toIntOrNull()
        val actors = document?.stars?.mapNotNull { cast ->
            ActorData(
                Actor(cast.name ?: return@mapNotNull null, cast.avatarUrl),
                roleString = cast.character
            )
        }?.distinctBy { it.actor }

        val recommendations = app.get("$mainUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12")
            .parsedSafe<Media>()?.data?.items?.map { it.toSearchResponse(this) }

        return if (tvType == TvType.TvSeries) {
            val episode = document?.resource?.seasons?.map { seasons ->
                (if (seasons.allEp.isNullOrEmpty()) (1..seasons.maxEp!!) else seasons.allEp.split(",").map { it.toInt() })
                    .map { ep ->
                        newEpisode(LoadData(id, seasons.se, ep, subject?.detailPath).toJson()) {
                            this.season = seasons.se
                            this.episode = ep
                        }
                    }
            }?.flatten() ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episode) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(id, detailPath = subject?.detailPath).toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = parseJson<LoadData>(data)
        val referer = "$secondAPIUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"

        val streams = app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
            referer = referer
        ).parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.map { source ->
            callback.invoke(
                newExtractorLink(this.name, this.name, source.url ?: return@map, INFER_TYPE) {
                    this.referer = "$secondAPIUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams?.first()?.id
        val format = streams?.first()?.format

        app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=${media.id}",
            referer = referer
        ).parsedSafe<Media>()?.data?.captions?.map { subtitle ->
            subtitleCallback.invoke(newSubtitleFile(subtitle.lanName ?: "", subtitle.url ?: return@map))
        }

        return true
    }

    data class InstallationRes(
        @JsonProperty("authToken") val authToken: AuthToken? = null,
    ) {
        data class AuthToken(
            @JsonProperty("token") val token: String? = null,
            @JsonProperty("expiresIn") val expiresIn: String? = null,
        )
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
    )

    data class Media(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf(),
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null,
            )

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null,
        ) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null,
            )

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
            ) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null,
                )
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
    ) {
        fun toSearchResponse(provider: MovieboxProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title ?: "",
                subjectId ?: "",
                if (subjectType == 1) TvType.Movie else TvType.TvSeries,
                false
            ) {
                this.posterUrl = cover?.url
            }
        }

        data class Cover(@JsonProperty("url") val url: String? = null)
        data class Trailer(@JsonProperty("videoAddress") val videoAddress: VideoAddress? = null) {
            data class VideoAddress(@JsonProperty("url") val url: String? = null)
        }
    }
}
