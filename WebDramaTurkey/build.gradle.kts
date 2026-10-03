version = 1

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Web Drama Turkey — Kore, Çin, Japon dizileri Türkçe altyazılı izle"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://webdramaturkey2.com&sz=%size%"
}
