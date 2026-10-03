version = 2

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Dizipal Orijinal — Türkçe dublaj ve altyazılı dizi, film izle"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizipalorjinal12.com&sz=%size%"
}
