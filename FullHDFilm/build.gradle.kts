version = 14

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Fullhdfilm (hdfilm.us) ile en yeni vizyon filmler Full HD ve kesintisiz; Türkçe dublaj ve altyazılı film izle."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilm.us&sz=%size%"
}