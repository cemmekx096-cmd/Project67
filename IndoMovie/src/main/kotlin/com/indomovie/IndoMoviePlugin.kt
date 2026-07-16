package com.indomovie

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoMoviePlugin : Plugin() {
    override fun load(context: Context) {
        // 3 sumber film
        registerMainAPI(TaroscafeProvider())
        registerMainAPI(PencurimovieProvider())
        registerMainAPI(MovieboxProvider())

        // Extractor yang dipakai bareng (terutama Taroscafe & Pencurimovie)
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Dsvplay())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(P2pStream())
    }
}
