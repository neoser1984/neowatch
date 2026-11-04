# ☁️ NeO | CloudStream için Türkçe Eklentiler

[![CloudStream Derleyici](https://img.shields.io/github/actions/workflow/status/neoser1984/neowatch/Derleyici.yml?label=CloudStream%20Derleyici&logo=github)](https://github.com/neoser1984/neowatch/actions/workflows/Derleyici.yml)

_CloudStream için Türkçe yayın yapan sitelere ait eklentiler._

[Döküman](https://recloudstream.github.io/csdocs/) **━** [CloudStream](https://github.com/recloudstream/cloudstream)

## 💾 Kurulum

1. **[cloudstream/pre-release](https://github.com/recloudstream/cloudstream/releases/tag/pre-release)** _adresinden güncel APK dosyasını indirip kurun._
2. Uygulamada **Ayarlar → Eklentiler → Depo ekle** yolunu izleyin.
3. `Depo URL'si` kısmına aşağıdaki adresi yazıp **Depo ekle** deyin:

```
https://raw.githubusercontent.com/neoser1984/neowatch/main/repo.json
```

> Eklentiler GitHub Actions ile `builds` dalına derlenir. Depo ilk kez kuruluyorsa önce **Actions → CloudStream Derleyici** iş akışının bir kez çalışmış olması gerekir.

### 📺 » [Google TV Temel Kurulum Adımları](MiBox.md)

### 🌐 Site açılmıyorsa: DNS over HTTPS

Bazı siteler (ör. **DiziPal**) Türkiye'deki internet sağlayıcılarında DNS düzeyinde engellidir. Eklenti "bağlantı bulunamadı" diyorsa ya da içerik gelmiyorsa:

1. CloudStream'de **Ayarlar → Genel → DNS over HTTPS** bölümünü açın.
2. **Cloudflare** (veya Google / AdGuard) seçin ve uygulamayı yeniden başlatın.

Bu ayar yalnızca CloudStream'in kendi bağlantılarını etkiler; telefonun diğer uygulamalarına dokunmaz.

**DiziPal'de bölüm/film açılmıyorsa:** site, oynatmadan önce bir doğrulama sayfası gösteriyor ve eklenti bu sayfayı uygulamanın gömülü tarayıcısıyla açıyor. Gömülü tarayıcı yukarıdaki ayarı kullanmaz; bu yüzden telefonda **Ayarlar → Ağ ve internet → Özel DNS → `dns.google`** (veya `1dot1dot1dot1.cloudflare-dns.com`) yapın.

---

## 📱 Repo İçeriği

### 🎬 Film & Dizi

| Eklenti | Site | İçerik |
|---|---|---|
| DiziBal | dizibal.org | Dizi, Film, Anime |
| DiziBox | dizibox.live | Dizi |
| DiziKorea | dizikorea3.com | Asya Dizisi, Film |
| DiziMom | ddizimom.net | Dizi, Film, Anime |
| DiziPal | dizipal2145.com | Dizi, Film _(DNS over HTTPS gerekebilir)_ |
| DiziPalGuncel | dizipal1593.com | Dizi, Film, Anime |
| DiziPalOrjinal | dizipalorjinal18.com | Dizi, Film |
| Dizilla | dizilla.now | Dizi, Anime, Asya Dizisi |
| DiziYou | diziyou.one | Dizi |
| FilmMakinesi | filmmakinesi.to | Film, Dizi |
| FilmModu | filmmodu.news | Film, Dizi |
| FullHDFilm | hdfilm.us | Film |
| FullHDFilmizlesene | fullhdfilmizlesene.now | Film |
| HDFilmCehennemi | hdfilmcehennemi.nl | Film, Dizi |
| JetFilmizle | jetfilmizle.app | Film, Dizi |
| KoreanTurk | koreanturk.tv | Asya Dizisi, Film |
| KultFilmler | kultfilmler.net | Film, Dizi |
| RareFilmm | rarefilmm.com | Film |
| SelcukFlix | selcukflix.app | Dizi, Film |
| SetFilmIzle | setfilmizle.ltd | Film, Dizi |
| SezonlukDizi | sezonlukdizi.cc | Dizi |
| SinemaCX | sinemacc.com | Film |
| SuperFilmGeldi | superfilmizle.icu _(DNS over HTTPS gerekebilir)_ | Film, Dizi, Anime |
| UgurFilm | ugurfilm8.com | Film |
| WebDramaTurkey | webdramaturkey2.com | Asya Dizisi, Film |
| WebteIzle | webteizle.info | Film |
| Watch2Movies | watch2movies.net | Film _(yapım aşamasında)_ |
| NetflixMirror | iosmirror.cc (Netflix & Prime Video) | Film, Dizi |

### 🧸 Anime & Çizgi Film

| Eklenti | İçerik |
|---|---|
| AnimeciX | Anime |
| TurkAnime | Anime _(turkanime.live)_ |
| CizgiMax | Çizgi Film, Anime |

### 📡 Canlı Yayın & Diğer

| Eklenti | İçerik |
|---|---|
| CanliTV | Canlı TV ([iptv-org](https://github.com/iptv-org/iptv) Türkiye listesi) |
| InatBox | Film, Dizi, Canlı TV _(beta; sunucu adresleri uygulamanın kendi ayarından otomatik alınır)_ |
| RecTV | Film, Dizi, Canlı TV _(API adresi otomatik alınır)_ |
| BelgeselX | Belgesel |
| YouTube | Video _(youtube.com üzerinden; oynatma CloudStream'in YouTube çıkarıcısıyla)_ |

> Sitelerin alan adları sık değiştiği için bir eklenti çalışmazsa ilgili eklentinin `mainUrl` değerini güncelleyip yeniden derlemeniz yeterlidir. Depodaki **Domain Değişiklik Kontrolü** iş akışı adres değişikliklerini 9 saatte bir otomatik kontrol eder.

---

## 🛠️ Derleme

```bash
./gradlew make makePluginsJson
```

Derlenen `.cs3` dosyaları her eklentinin `build` klasörüne düşer. `main` dalına yapılan her push'ta GitHub Actions eklentileri otomatik derleyip `builds` dalına yükler.

---

### 🎁 Teşekkürler

- [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)
- [recloudstream/extensions](https://github.com/recloudstream/extensions)
- [hexated/cloudstream-extensions-hexated](https://github.com/hexated/cloudstream-extensions-hexated)

---

## 🌐 Lisans

Bu depo, GPL-3.0 lisanslı açık kaynak bir CloudStream eklenti deposundan türetilmiştir ve aynı lisansın koşullarına tabidir: [GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007](LICENSE)

> Bu depo herhangi bir içerik barındırmaz; eklentiler yalnızca üçüncü taraf sitelerdeki herkese açık sayfaları okur.
