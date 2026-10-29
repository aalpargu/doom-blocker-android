# Doom Blocker

Sık kullandığım Android uygulama sınırlayıcısı. Günlük kota, oturum süresi ve zorunlu mola ile dikkat dağıtan uygulamaları daha kontrollü kullanmayı amaçlar.

[English and detailed setup](README.md)

## Teknoloji

Kotlin, Jetpack Compose, Android Usage Access, Accessibility Service ve isteğe bağlı Supabase senkronizasyonu.

## Çalıştırma

JDK 17 ve Android SDK 36 gerekir. `./gradlew :core:test`, `./gradlew :app:testProductionDebugUnitTest :app:lintProductionDebug` ve `./gradlew :app:assembleProductionDebug` ile doğrulanıp derlenebilir. Windows üzerinde `gradlew.bat` kullanılır.

## Kapsam ve durum

Kullanım erişimi ve erişilebilirlik izni gerekir. Arka plan kısıtları cihaz üreticisine göre değişir. Telefon/tablet ortak kotası isteğe bağlıdır; bazı kurallar cihaz bazında kalır. Kişisel 3.1.3 kaynak sürümüdür.
