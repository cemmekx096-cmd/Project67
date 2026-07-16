package com.indomovie

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

// ===== Reuse dari Pencurimovie (udah kebukti jalan) =====
class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Dsvplay : DoodLaExtractor() {
    override var mainUrl = "https://dsvplay.com"
}

// ===== Percobaan: Morencius & HGCloud pakai basis DoodLaExtractor =====
// CATATAN: ini baru DUGAAN karena pola script (jwplayer + xupload.js) mirip DoodStream clone.
// Kalau pas dites ternyata gagal generate link (karena format URL /stream/{token}/hjkrhuihghfvu/...
// nya beda dari format Dood standar), berarti DoodLaExtractor generik gak cukup dan kita perlu
// bikin class custom sendiri yang niru ulang alur pass_md5 + unpack packed-JS kayak di Extractors.kt
// lama (fungsi extractMasukestin/Unpacker) - tinggal bilang kalau ini yang terjadi.
class Morencius : DoodLaExtractor() {
    override var mainUrl = "https://morencius.com"
}

class Hgcloud : DoodLaExtractor() {
    override var mainUrl = "https://hgcloud.com" // ganti kalau domain aslinya beda
}

// ===== P2P (fastdl.p2pstream.online) =====
// PENTING: ini masih SKELETON. Dari network log yang lo kasih, ketauan flow-nya:
//   1. GET /api/v1/video?id={id}&w=..&h=..&r=.. -> balikin blob HEX terenkripsi
//   2. GET /api/v1/player?t={blob_hex} -> balikin {"success":true,"k":"...","kx":1784...}
//   3. k + kx dipakai buat DECRYPT blob dari langkah 1 jadi JSON berisi url m3u8
// Tapi algoritma decrypt persisnya (AES mode apa, gimana k+kx dipetakan ke key/iv) BELUM ketauan
// dari log doang. Karena lo bilang udah PUNYA extractor P2P yang jalan di extension LK21 lo,
// tolong kirim isi file itu (class yang implement ExtractorApi buat p2pstream) biar tinggal
// di-porting ke sini, daripada gue nebak algoritmanya dan resiko salah.
class P2pStream : ExtractorApi() {
    override val name = "P2PStream"
    override val mainUrl = "https://fastdl.p2pstream.online"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // TODO: tempel logic dari extension LK21 di sini setelah lo kirim source code-nya.
        // val id = url.substringAfter("#")
        // val videoRes = app.get("$mainUrl/api/v1/video?id=$id&w=1920&h=1080&r=null").text
        // val playerRes = app.get("$mainUrl/api/v1/player?t=$videoRes").parsed<PlayerRes>()
        // val decrypted = decrypt(videoRes, playerRes.k, playerRes.kx) // <- logic yg belum ketauan
        // ambil m3u8 dari hasil decrypt, lalu callback.invoke(newExtractorLink(...))
    }
}
