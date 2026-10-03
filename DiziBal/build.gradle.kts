version = 1

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "dizibaL — online dizi arşivi, altyazılı yabancı diziler"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizibal.org&sz=%size%"
}
