version = 2

cloudstream {
    authors     = listOf("NeO")
    language    = "tr"
    description = "Teşhis aracı: eklentilerin bu cihazdaki gerçek davranışını (bağlantı, hata, ağ erişimi) ana sayfada gösterir. Sorun giderme için."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=github.com&sz=%size%"
}
