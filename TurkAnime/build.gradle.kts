version = 3

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Türk Anime TV (turkanime.live) - Türkçe altyazılı anime izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://www.google.com/s2/favicons?domain=turkanime.live&sz=%size%"
}