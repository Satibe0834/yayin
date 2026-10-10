# Yayın — Android TV ve Android telefon için IPTV oynatıcı

Tek bir APK hem Android TV / TV kutularında hem de Android telefon ve tabletlerde çalışır.

- **Arayüz:** `app/src/main/assets/index.html` (ana ekran, kanal listesi, film/dizi afişleri).
- **Oynatma:** Android'in yerel video motoru **Media3 / ExoPlayer** + **FFmpeg ses çözücü**
  (`NativePlayer.kt`). HLS, DASH, MPEG-TS, MP4, MKV; ses tarafında AAC, MP3, AC3, E-AC3,
  DTS, MP2, TrueHD, Opus, FLAC. Görüntü tarafında cihazın donanım çözücüsü kullanılır
  (H.264 / H.265-HEVC / VP9 / AV1 / MPEG-2 cihaz destekliyorsa).
- **Hızlı kanal geçişi:** tek oynatıcı yeniden kullanılır, 0,5 sn veriyle oynatma başlar,
  MPEG-TS'de anahtar kare beklenmez, komşu kanallara önceden bağlanılır, geçişte son kare
  ekranda kalır, tuşa basılı tutunca ara kanallar yüklenmeden atlanır.

## Liste ekleme
Ana ekranda **Liste değiştir**:
- **Xtream Codes:** sunucu adresi, kullanıcı adı ve şifreyle giriş. Canlı kanallar, filmler ve diziler
  kategorileriyle gelir; afiş, konu, puan, tür, yönetmen, oyuncular ve bölüm listeleri otomatik alınır.
  Canlı kanallarda "şu an / sıradaki" yayın akışı (EPG) görünür. Arşiv destekli kanallarda ⟲ düğmesi
  geçmiş günlerin yayınlarını açar (catch-up). Hesap bitiş tarihi ana ekranın sol altında yazar.
- **M3U listesi:** dosya, bağlantı veya yapıştırma.

## Öne çıkan özellikler
- **Telefonla kurulum:** Liste değiştir → "📱 Telefonla kur". TV'deki QR kodu telefonla okutun, açılan sayfaya
  bilgileri yazın; TV giriş yapar. Sunucu yalnızca aynı Wi-Fi ağında, tek kullanımlık anahtarla çalışır ve iş bitince kapanır.
- **Maç Merkezi:** Spor kanallarının yayın akışından bugünkü ve yarınki maçları bulur. Canlı olanları tek tuşla açar,
  diğerleri için hatırlatıcı kurar. "Takımlarım"daki takımların maçları ⭐ ile en üstte ve otomatik hatırlatılır.
  (Xtream yayın akışı gerekir; hatırlatıcılar uygulama açıkken çalışır.)
- **Çoklu ekran:** 2 ya da 4 kanal aynı anda. Ses seçili ekrandan gelir. Canlı maçlar tek tuşla çoklu ekranda açılır.
- **Kanal önizleme:** Canlı TV'de OK ile listeyi açın, ↑↓ ile gezinin; bir kanalda durunca sağ altta önizleme oynar,
  OK ile açılır. Liste kapalıyken ↑↓ doğrudan kanal değiştirir.
- **Yedek kaynak:** Kanal açılmazsa ya da 10 sn donarsa aynı kanalın başka kaynağına (HD/FHD/4K) kendiliğinden geçer.
  Açılmayan kanallar 6 saat listede soluk görünür.
- **Profiller:** "Kim izliyor?" ekranı; her profilin favorileri, geçmişi, kaldığı yerleri, hatırlatıcıları ayrı.
  Çocuk profilinde yalnızca çocuk kategorileri görünür, çıkmak için PIN istenir.
- **Google TV "İzlemeye devam et":** Yarım kalan film ve bölümler TV'nin kendi ana ekranında görünür.
- **Sesli arama:** Ana ekrandaki 🔍 ya da kumandanın arama tuşu; kanal, film ve dizilerde tek seferde arar.

## Temalar
Ana ekrandaki **Kanal teması** tema galerisini açar. 46 tema, 6 kategoride:
Şeffaf (5), Renkler (9), Manzara (14), Futbol renkleri (12), Spor (5), Sinema ve uzay (4) ve
**Kendi resmin** (cihazdan fotoğraf seçilir, vurgu rengi resimden otomatik alınır). Tüm görseller
uygulamanın içinde kodla çizilir; internet gerektirmez.
- **Hareketli arka plan:** kar, yıldızlar, kayan yıldız, kuzey ışıkları, ateş böcekleri, düşen yapraklar,
  süzülen balonlar, deniz parıltısı, stadyum flaşları ve konfeti, akan neon zemin, sahne ışığı.
- **Şeffaflık:** Kapalı / Cam / Tam şeffaf. Cam ve Tam şeffafta tema görseli film, dizi ve ayar
  ekranlarında da görünür.
- **Canlı yayın arka planı:** ana menünün arkasında son izlenen kanal oynar (sesi açılıp kapatılabilir);
  Canlı TV'ye girince yayın kesilmeden devam eder.

## Ayarlar
Ana ekrandaki **Ayarlar** (veya kumandadaki Menü tuşu) tam ekran ayar sayfasını açar:
- **Liste ve hesap:** hesap bitiş tarihi, bağlantı sayısı, liste değiştir/yenile, canlı yayın biçimi (TS/HLS), çıkış.
- **Oynatma:** açılışta ne gösterileceği, kanal geçiş hızı, görüntü oranı (sığdır/doldur/uzat), arabellek profili,
  tercih edilen ses dili ve altyazı, altyazı boyutu, ses çözücü önceliği, uyku zamanlayıcı.
- **Canlı TV ve EPG:** kanal sırası, listede yayın akışı, EPG saat farkı, kanal logoları.
- **Görünüm:** tema rengi, 12/24 saat, hava durumu ve birimi, tarama çizgileri, animasyonlar.
- **Ebeveyn kilidi:** PIN, yetişkin kategorilerini otomatik kilitleme, her kategoriyi görünür/gizli/kilitli yapma.
  Kilitli içerik ana ekrandaki 🔒 düğmesiyle PIN girilerek o oturum için açılır.
- **Veri ve yedekleme:** yedek al / geri yükle, geçmiş, favori ve önbellek temizleme, sıfırlama.
- **Ağ ve gelişmiş:** bağlantı (hız) testi, özel User-Agent.
- **Hakkında:** sürüm, platform, cihaz kimliği, içerik sayıları.

## APK'yı elde etmenin iki yolu

### A) Bilgisayara hiçbir şey kurmadan (GitHub ile)
1. github.com'da yeni ve boş bir depo (repository) oluşturun.
2. Bu klasörün içindekilerin tamamını depoya yükleyin ("Add file → Upload files").
   `.github` klasörünün de yüklendiğinden emin olun.
3. Deponun **Actions** sekmesini açın. "APK oluştur" işi kendiliğinden başlar (3–5 dk).
4. Bittiğinde işin sayfasında en altta **yayin-apk** dosyası çıkar; indirin, içindeki
   `app-release.apk` dosyası kurulacak dosyadır.

### B) Android Studio ile
1. Android Studio'yu kurun ve bu klasörü **Open** ile açın. Gradle eşitlemesi biter bitmez hazırdır.
2. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. APK `app/build/outputs/apk/` altında oluşur. Telefon USB ile bağlıysa ▶ düğmesi doğrudan kurar.

## Kurulum
- **Telefon:** APK'yı telefona atın, açın, "bilinmeyen kaynaklardan yüklemeye izin ver" deyin.
- **Android TV / TV kutusu:** Telefona *Send Files to TV* uygulamasını kurup APK'yı TV'ye
  gönderin ya da APK'yı USB belleğe kopyalayıp TV'de bir dosya yöneticisiyle açın.
  Ayarlar → Cihaz tercihleri → Güvenlik → Bilinmeyen kaynaklar bölümünden ilgili
  uygulamaya izin verin. Uygulama TV ana ekranında "Yayın" afişiyle görünür.

## Kumanda kullanımı (Android TV)
| Tuş | Ana ekran | Canlı TV | Film / dizi izlerken |
|---|---|---|---|
| ← → | Kutular arasında gez | Kategori değiştir | 10 sn geri / ileri |
| ↑ ↓ | Üst menüye çık / in | Kanal değiştir | Ses |
| OK | Aç | Kanal listesini gizle / göster | Oynat / duraklat |
| Geri | İki kez basınca çıkar | Ana ekrana dön | Bir önceki ekrana dön |
| 0–9 | | Numarayla kanala git | |
| CH+ / CH− | | Kanal değiştir | Sonraki bölüm |
| ⏯ ⏩ ⏪ | | Oynat / duraklat | Oynat, 30 sn sar |
| Menü | Ayarlar | Listeyi gizle / göster | |

## Bilmeniz gerekenler
- Uygulama içinde tarayıcıdaki CORS engeli yoktur; tarayıcıda açılmayan listeler ve yayınlar burada açılır.
- `http://` ile başlayan (şifresiz) yayın adresleri desteklenir.
- Film ve dizilerde Media3'ün kendi kontrolleri açılır: sarma çubuğu, **ses dili ve altyazı seçimi**,
  önceki/sonraki bölüm. Kaldığınız yer her 5 saniyede kaydedilir.
- Cihazın donanımının hiç çözemediği bir görüntü biçimi olursa **Harici oynatıcıda aç** seçeneği çıkar.
- FFmpeg çözücü GPL-3.0 lisanslıdır. Uygulamayı yalnızca kendiniz kullanıyorsanız sorun yoktur;
  başkalarına dağıtacak veya satacaksanız GPL kaynak kodu paylaşma şartını getirir.
  Bunu istemezseniz `app/build.gradle.kts` içindeki `media3-ffmpeg-decoder` satırını silmeniz yeterli
  (o zaman AC3/DTS gibi sesler yalnızca cihaz destekliyorsa çalışır).
- Arayüzü değiştirmek için yalnızca `app/src/main/assets/index.html` dosyasını düzenleyip yeniden derlemeniz yeterli.
- Play Store'a yüklemek isterseniz kendi imza anahtarınızı oluşturmanız gerekir
  (Android Studio: Build → Generate Signed Bundle / APK).
