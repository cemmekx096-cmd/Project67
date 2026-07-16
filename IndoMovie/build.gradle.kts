// version harus integer, naikkin tiap kali ada update biar Cloudstream tau ada versi baru
version = 1

cloudstream {
    description = "Nonton film Indonesia dari Taroscafe, PencuriMovie, dan MovieBox dalam 1 extension"
    authors = listOf("Cuk")

    /**
     * Status:
     * 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    // Icon yang muncul di daftar extension Cloudstream
    iconUrl = "https://www.google.com/s2/favicons?domain=taroscafe.com&sz=%size%"
}

android {
    buildFeatures {
        buildConfig = true
    }
}