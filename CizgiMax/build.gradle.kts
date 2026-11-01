version = 7

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "ÇizgiMax ile çizgi film ve anime izlemek artık daha kolay; donmadan, full HD ve Türkçe."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Cartoon", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=cizgimax.online&sz=%size%"
}