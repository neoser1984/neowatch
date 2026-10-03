version = 9

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Film modun geldiyse yüksek kalitede en yeni filmleri izle, 1080p izleyebileceğiniz reklamsiz film sitesi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://filmmodu.live&sz=%size%"
}