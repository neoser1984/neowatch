version = 6

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "En Güncel Asya ve Kore Dizileri izleme Sitesi"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizikorea3.com&sz=%size%"
}