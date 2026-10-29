package dev.personal.doomblocker.platform

internal data class BreakSuggestion(val title: String, val message: String)

/** Presentation only. A cooldown's persisted start keeps screen and notification in sync. */
internal object BreakSuggestions {
    private val suggestions = listOf(
        BreakSuggestion("Derse küçük bir dönüş", "Telefonu kenara bırak. Defterini açıp tek bir soruyla başlayabilirsin."),
        BreakSuggestion("Kitabına birkaç sayfa ayır", "Kaldığın sayfayı aç. Birkaç sayfa okumaya ne dersin?"),
        BreakSuggestion("Biraz nefeslen", "Ekrandan gözlerini ayır, omuzlarını gevşet. Birkaç sakin nefes al."),
        BreakSuggestion("Biraz hareket iyi gider", "Ayağa kalkıp kısa bir tur atmaya veya hafifçe esnemeye ne dersin?"),
        BreakSuggestion("Küçük bir adımla başla", "Çalışacağın konudan kısa bir bölümü gözden geçir. Başlamak için bu kadarı yeter."),
        BreakSuggestion("Bu arayı bir hikâyeye ayır", "Telefonu bırakıp kitabını eline al. Kaldığın yer seni bekliyor."),
        BreakSuggestion("Şimdi biraz dinlen", "Bir yudum su al, gözlerini ekrandan uzaklaştır. Kendine kısa bir ara ver."),
        BreakSuggestion("Masadan kısa bir mola", "Dilersen kalk, biraz yürü ve omuzlarını rahatlat. Sonra işine daha sakin dönebilirsin."),
    )

    fun forCooldown(packageName: String, startedAtWallMillis: Long): BreakSuggestion {
        val seed = packageName.hashCode().toLong() + startedAtWallMillis
        return suggestions[java.util.Random(seed).nextInt(suggestions.size)]
    }
}
