version = 7

cloudstream {
    authors     = listOf("NeO", "usdogu")
    language    = "tr"
    description = "Film Makinesi, en yeni ve en güncel filmleri sitemizde full HD kalite farkı ile izleyebilirsiniz. HD film izle denildiğinde akla gelen en kaliteli film izleme sitesi."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://filmmakinesi.to&sz=%size%"
}

dependencies {
    // Rhino uygulamanın içinde zaten var (sürüm 1.8.1); burada yalnızca derleme için eklenir
    "compileOnly"("org.mozilla:rhino:1.8.1")
}
