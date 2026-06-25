package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.api.Provider

@CloudstreamPlugin
class KisskhProviderPlugin: Provider {
    override val mainApi = KisskhProvider()
}
