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

---

## 📱 Repo İçeriği

### 🎬 Film & Dizi

| Eklenti | Site | İçerik |
|---|---|---|
| DiziBal | dizibal.org | Dizi, Film, Anime |
| DiziBox | dizibox.live | Dizi |
| DiziKorea | dizikorea3.com | Asya Dizisi, Film |
| DiziMom | dizimom.wiki | Dizi |
| DiziPal | dizipal2135.com | Dizi, Film |
| DiziPalGuncel | dizipal1586.com | Dizi, Film, Anime |
| DiziPalOrjinal | dizipalorjinal12.com | Dizi, Film |
| Dizilla | dizilla.now | Dizi, Anime, Asya Dizisi |
| DiziYou | diziyou3.com | Dizi |
| FilmMakinesi | filmmakinesi.to | Film, Dizi |
| FilmModu | filmmodu.live | Film, Dizi |
| FullHDFilm | fullhdfilm.site | Film, Dizi |
| FullHDFilmizlesene | fullhdfilmizlesene.now | Film |
| HDFilmCehennemi | hdfilmcehennemi.nl | Film, Dizi |
| JetFilmizle | jetfilmizle.io | Film |
| KoreanTurk | koreanturk.com | Asya Dizisi |
| KultFilmler | kultfilmler.net | Film, Dizi |
| RareFilmm | rarefilmm.com | Film |
| SelcukFlix | selcukflix.com | Dizi, Film |
| SetFilmIzle | setfilmizle.ltd | Film, Dizi |
| SezonlukDizi | sezonlukdizi.cc | Dizi |
| SinemaCX | sinema.cx | Film |
| SuperFilmGeldi | superfilmgeldi.me | Film |
| UgurFilm | ugurfilm8.com | Film |
| WebDramaTurkey | webdramaturkey2.com | Asya Dizisi, Film |
| WebteIzle | webteizle.click | Film |
| Watch2Movies | watch2movies.net | Film _(yapım aşamasında)_ |
| NetflixMirror | iosmirror.cc (Netflix & Prime Video) | Film, Dizi |

### 🧸 Anime & Çizgi Film

| Eklenti | İçerik |
|---|---|
| AnimeciX | Anime |
| TurkAnime | Anime |
| CizgiMax | Çizgi Film |

### 📡 Canlı Yayın & Diğer

| Eklenti | İçerik |
|---|---|
| CanliTV | Canlı TV ([iptv-org](https://github.com/iptv-org/iptv) Türkiye listesi) |
| GolgeTV | Canlı TV _(beta)_ |
| InatBox | Film, Dizi, Canlı TV _(beta)_ |
| RecTV | Film, Dizi, Canlı TV |
| BelgeselX | Belgesel |
| YouTube | Video |

> Sitelerin alan adları sık değiştiği için bir eklenti çalışmazsa ilgili eklentinin `mainUrl` değerini güncelleyip yeniden derlemeniz yeterlidir.

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
