# 3.3.0 — Reels ve Shorts engeli

**Planlar → Reels ve Shorts** bölümünde iki anahtar var: Instagram Reels'i kapat, YouTube Shorts'u kapat. İkisi de isteğe bağlı. Katı moddan bağımsız çalışır ve değişiklik anında uygulanır.

## Nasıl çalışır

- Erişilebilirlik servisi yalnızca Instagram veya YouTube açıkken ekrandaki öğelerin **iç adlarına** ve **seçili sekmeye** bakar.
- İki ayrı işaret aranır:
  - tam ekran oynatıcının iç adı (`clips_viewer…`, `reel_recycler`, `reel_player_page_container`…),
  - alttaki "Reels" / "Shorts" sekmesinin seçili olması.
- Ana akıştaki küçük önizlemeler engellenmez.
- Algılanınca **Geri** tuşuna basılır ve bir önceki sekmeye dönülür. Mesajlar, hikâyeler ve normal videolar açık kalır.
- Geri tuşu üç kez üst üste yine Reels/Shorts'a düşerse (ör. bir linkten açıldıysa) Ana ekrana dönülür.
- Kontrol saniyede en fazla ~3 kez yapılır. Diğer uygulamalarda hiç çalışmaz.
- Okunan hiçbir şey kaydedilmez veya gönderilmez.

## Ekran yapısı kaydı

Instagram ve YouTube güncellemeleri iç adları değiştirebilir. Algılama çalışmazsa:

1. **Ekran yapısını kaydet**'e bas.
2. 10 saniye içinde Reels'i veya Shorts'u aç ve bekle. Kayıt bitene kadar engel durur.
3. "Ekran yapısı kaydedildi" mesajından sonra **Kaydı dışa aktar** ile dosyayı kaydet ve gönder.

Kayıtta öğe adları, sınıf adları, en fazla 30 karakterlik etiketler ve konumlar bulunur. Uzun metinler (mesajlar gibi) yalnızca uzunluklarıyla yazılır.

## Doğrulama durumu

YouTube'un paket adını ve Shorts oynatıcısının iç adını taklit eden bir test uygulamasıyla emülatörde denendi:

- Shorts açıldığında geri dönüldü.
- Sayaç arttı.
- Kayıt alındı ve kayıttan sonra engel yeniden devreye girdi.

Gerçek Instagram ve YouTube sürümlerinde (Galaxy S24) henüz denenmedi.
