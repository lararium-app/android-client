package com.satory.graphenosai.llm

import java.text.Normalizer

/**
 * Local retrieval intent detector.
 *
 * The model still has function-calling available for ambiguous cases, but this classifier
 * catches the obvious "needs live data" intents before the model can answer from stale memory.
 */
enum class RetrievalIntent {
    NONE,
    WEB_SEARCH,
    WEATHER
}

fun classifyRetrievalIntent(query: String): RetrievalIntent {
    val text = normalizeIntentText(query)
    if (text.isBlank()) return RetrievalIntent.NONE

    if (detectWeatherIntent(text)) return RetrievalIntent.WEATHER
    if (detectWebSearchIntent(text)) return RetrievalIntent.WEB_SEARCH

    return RetrievalIntent.NONE
}

fun shouldUseWebSearch(query: String): Boolean {
    return classifyRetrievalIntent(query) in setOf(RetrievalIntent.WEB_SEARCH, RetrievalIntent.WEATHER)
}

fun isWeatherQuery(query: String): Boolean {
    return classifyRetrievalIntent(query) == RetrievalIntent.WEATHER
}

private fun detectWebSearchIntent(text: String): Boolean {
    if (LOCAL_ONLY_PATTERNS.any { it.containsMatchIn(text) } &&
        LIVE_DATA_PATTERNS.none { it.containsMatchIn(text) } &&
        EXPLICIT_LOOKUP_PATTERNS.none { it.containsMatchIn(text) }
    ) {
        return false
    }

    var score = 0

    if (EXPLICIT_LOOKUP_PATTERNS.any { it.containsMatchIn(text) }) score += 3
    if (LIVE_DATA_PATTERNS.any { it.containsMatchIn(text) }) score += 3
    if (VOLATILE_DOMAIN_PATTERNS.any { it.containsMatchIn(text) }) score += 2
    if (SOFTWARE_RELEASE_PATTERNS.any { it.containsMatchIn(text) }) score += 2
    if (AVAILABILITY_PATTERNS.any { it.containsMatchIn(text) }) score += 2
    if (QUESTION_WITH_LIVE_SHAPE.any { it.containsMatchIn(text) }) score += 1
    if (FUTURE_OR_RECENT_YEAR.containsMatchIn(text)) score += 2
    if (URL_OR_DOMAIN.containsMatchIn(text) && EXPLICIT_LOOKUP_PATTERNS.any { it.containsMatchIn(text) }) score += 2

    return score >= 3
}

private fun detectWeatherIntent(text: String): Boolean {
    val hasWeatherTerm = WEATHER_PATTERNS.any { it.containsMatchIn(text) }
    if (!hasWeatherTerm) return false

    val hasQuestionShape = WEATHER_QUESTION_PATTERNS.any { it.containsMatchIn(text) }
    val hasTime = WEATHER_TIME_PATTERNS.any { it.containsMatchIn(text) }
    val hasLocationCue = LOCATION_CUE_PATTERN.containsMatchIn(text)
    val isDirectWeatherRequest = DIRECT_WEATHER_REQUEST_PATTERNS.any { it.containsMatchIn(text) }

    return isDirectWeatherRequest || hasQuestionShape || hasTime || hasLocationCue
}

private fun normalizeIntentText(value: String): String {
    val lower = value.lowercase().trim()
    val withoutDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return withoutDiacritics
        .replace(Regex("[\\p{Z}\\s]+"), " ")
        .trim()
}

private val EXPLICIT_LOOKUP_PATTERNS = listOf(
    Regex("\\b(search|find|look up|google|web search|browse|check online|from the web|on the web)\\b"),
    Regex("\\b(recherche|rechercher|chercher|cherche|cherches|trouve|trouver|buscar|busca|pesquisar|procura|suchen|suche|cerca|cercare)\\b"),
    Regex("\\b(szukaj|znajdz|sprawdz|hledat|hledej|vyhledat|ara|bul|zoeken|opzoeken)\\b"),
    Regex("(найди|поищи|поиск|загугли|посмотри в интернете|проверь в интернете|найди в сети|в сети|интернеттен|интернетте|пошукай|знайди)"),
    Regex("(ابحث|بحث|فتش|جستجو|جست و جو|جستجو کن|खोज|ढूंढ|検索|搜|搜索|查找|查一下|검색)")
)

private val LIVE_DATA_PATTERNS = listOf(
    Regex("\\b(latest|current|currently|recent|newest|today|tonight|this week|this month|right now|now)\\b"),
    Regex("\\b(news|breaking|live|updated|update|changelog|release date|available now)\\b"),
    Regex("\\b(dernier|derniere|dernieres|derniers|actuel|actuelle|recemment|aujourd hui|nouvelles|nouveau|noticias|notizie|nachrichten|aktuell|heute|neueste)\\b"),
    Regex("\\b(noticias|hoje|agora|atual|recente|nieuws|vandaag|nu|senaste|nyheter|idag)\\b"),
    Regex("\\b(dzis|dzisiaj|teraz|aktualn\\w*|najnowsz\\w*|wiadomosci|novinky|dnes|ted)\\b"),
    Regex("(последн|актуальн|свеж|сегодня|сейчас|новост|что нового|обновлен|оновлен|зараз|сьогодні|жаңалық|бүгін)"),
    Regex("(最新|现在|今天|ニュース|今日|현재|오늘|اخبار|اليوم|الان|हाल|आज)")
)

private val VOLATILE_DOMAIN_PATTERNS = listOf(
    Regex("\\b(price|prices|cost|stock|stocks|shares|market cap|exchange rate|crypto|bitcoin|ethereum)\\b"),
    Regex("\\b(weather|forecast|score|scores|schedule|standings|election|polls|flight|flights)\\b"),
    Regex("\\b(buy|order|available|availability|in stock|where can i get|where can i buy)\\b"),
    Regex("\\b(precio|prix|preco|preis|cena|cen[aeęy]|kurs|akcje|gielda|borsa|mercado|acciones)\\b"),
    Regex("(цена|стоимость|курс|акции|бирж|крипт|биткоин|эфир|расписание|счет|счёт|результат|рейс|выборы|опрос)"),
    Regex("(سعر|اسعار|بورصة|عملة|رحلة|انتخابات|कीमत|मूल्य|株価|価格|航班|选举|날씨|가격)")
)

private val SOFTWARE_RELEASE_PATTERNS = listOf(
    Regex("\\b(version|release|released|launch|launched|roadmap|stable build|beta|security patch|cve)\\b"),
    Regex("\\b(android|ios|grapheneos|linux|windows|macos|chrome|firefox|kotlin|compose|openai|claude|gemini)\\b.{0,40}\\b(version|release|update|patch|model)\\b"),
    Regex("\\b(version|release|update|patch|model)\\b.{0,40}\\b(android|ios|grapheneos|linux|windows|macos|chrome|firefox|kotlin|compose|openai|claude|gemini)\\b"),
    Regex("(версия|релиз|вышел|вышла|запуск|патч|уязвимость|модель|обновление|прошивка).{0,40}(android|ios|grapheneos|openai|claude|gemini|kotlin)"),
    Regex("(android|ios|grapheneos|openai|claude|gemini|kotlin).{0,40}(версия|релиз|модель|обновление|прошивка)"),
    Regex("(最新.{0,20}モデル|モデル.{0,20}最新)")
)

private val AVAILABILITY_PATTERNS = listOf(
    Regex("\\b(is it out|did it launch|has .* launched|is .* available|where can i buy|in stock)\\b"),
    Regex("(вышел ли|вышла ли|доступен ли|доступна ли|есть ли новый|где купить|есть в наличии)"),
    Regex("\\b(esta disponible|est disponible|ist .* verfugbar|e disponibile|czy .* jest dostepn)\\b")
)

private val QUESTION_WITH_LIVE_SHAPE = listOf(
    Regex("\\b(who is the current|what is the current|when is the next|has there been|is there a new)\\b"),
    Regex("(кто сейчас|какая сейчас|какой сейчас|когда выйдет|коли вийде|что сейчас)")
)

private val LOCAL_ONLY_PATTERNS = listOf(
    Regex("\\b(explain|summarize|translate|rewrite|write|draft|create|calculate|solve|compare conceptually)\\b"),
    Regex("(объясни|переведи|напиши|сочини|реши|посчитай|суммируй|кратко изложи)")
)

private val WEATHER_PATTERNS = listOf(
    Regex("(?<!\\p{L})(weather|forecast|temperature|feels like|humidity|precipitation|uv index)(?!\\p{L})"),
    Regex("(?<!\\p{L})rain(?:ing|y)?(?!\\p{L})"),
    Regex("(?<!\\p{L})snow(?:ing|y)?(?!\\p{L})"),
    Regex("(?<!\\p{L})(?:storm(?:y)?|thunderstorm(?:s)?|wind(?:y)?|cloudy|sunny|umbrella)(?!\\p{L})"),
    Regex("hot outside|cold outside"),
    Regex("(?<!\\p{L})(clima|tiempo|meteo|meteorologia|temperatura|prevision)(?!\\p{L})"),
    Regex("(?<!\\p{L})(regen|schnee|neige|lluvia|nieve|chuva|vento|vent|wind|wetter|weer|vader|saa|hava)(?!\\p{L})"),
    Regex("(?<!\\p{L})(pogoda|prognoza|deszcz|padac|burza|wiatr|dest|snih)(?!\\p{L})"),
    Regex("(погода|прогноз|температура|дождь|снег|ветер|гроза|ливень|жара|холодно|парасолька|погоди|жаңбыр|ауа райы)"),
    Regex("(天気|天气|氣溫|气温|雨|雪|날씨|비|눈|الطقس|مطر|ثلج|मौसम|बारिश)")
)

private val DIRECT_WEATHER_REQUEST_PATTERNS = listOf(
    Regex("\\b(weather|forecast|clima|tiempo|meteo|wetter|pogoda|weer|hava)\\b"),
    Regex("(погода|прогноз погоды|ауа райы|الطقس|मौसम|天気|天气|날씨)")
)

private val WEATHER_TIME_PATTERNS = listOf(
    Regex("\\b(today|tomorrow|tonight|now|currently|this morning|this evening|weekend)\\b"),
    Regex("\\b(hoy|manana|maintenant|aujourd hui|demain|heute|morgen|oggi|domani)\\b"),
    Regex("\\b(dzis|dzisiaj|jutro|teraz|dnes|zitra|nu|vandaag|morgen)\\b"),
    Regex("(сегодня|завтра|сейчас|вечером|утром|на выходных|сьогодні|завтра|зараз|бүгін|ертең|اليوم|غدا|आज|कल|今日|明日|今天|明天|오늘|내일)")
)

private val WEATHER_QUESTION_PATTERNS = listOf(
    Regex("\\?"),
    Regex("\\b(is|will|should|do i need|what is|how is)\\b"),
    Regex("\\b(sera|est ce|hace|hara|brauche|czy|jak|jaka|jaki)\\b"),
    Regex("(нуж|будет|какая|какой|как там|идет ли|пойдет ли|чи буде|як)")
)

private val LOCATION_CUE_PATTERN = Regex(
    "(^|\\s)(in|at|for|near|around|en|a|pour|pres de|bei|w|we|dla|cerca de|em|para|vicino a|в|во|для|около|рядом с|у)\\s+",
    RegexOption.IGNORE_CASE
)

private val FUTURE_OR_RECENT_YEAR = Regex("\\b20(2[4-9]|3\\d)\\b")
private val URL_OR_DOMAIN = Regex("\\b([a-z0-9-]+\\.)+[a-z]{2,}\\b")

fun extractWeatherLocation(query: String): String? {
    val normalized = query.trim()
    val patterns = listOf(
        Regex("\\b(?:in|at|for|near|around)\\s+([^?.!,]+)", RegexOption.IGNORE_CASE),
        Regex("(?:^|\\s)(?:en|a|à|pour|près de|bei|in der nähe von|w|we|dla|cerca de|em|para|vicino a)\\s+([^?.!,]+)", RegexOption.IGNORE_CASE),
        Regex("(?:^|\\s)(?:в|во|для|около|рядом с|у)\\s+([^?.!,]+)", RegexOption.IGNORE_CASE),
        Regex("(?:^|\\s)(?:في|ل|قرب)\\s+([^?.!,،؟]+)", RegexOption.IGNORE_CASE),
        Regex("(?:天气|天気|날씨|मौसम)\\s+([^?.!,，。؟]+)", RegexOption.IGNORE_CASE),
        Regex("([^?.!,，。؟]+)\\s+(?:天气|天気|날씨|मौसम)", RegexOption.IGNORE_CASE)
    )
    val location = patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)
    }?.trim()

    val cleaned = location
        ?.removePrefix("городе ")
        ?.removePrefix("город ")
        ?.replace(
            Regex(
                "\\b(today|tomorrow|tonight|now|currently|this morning|this evening|hoy|mañana|maintenant|aujourd'hui|demain|heute|morgen|oggi|domani|dzis|dziś|jutro|сегодня|завтра|сейчас|вечером)\\b",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        ?.replace(
            Regex("(اليوم|غدا|الان|आज|कल|今日|明日|今天|明天|오늘|내일)"),
            ""
        )
        ?.trim()

    return cleaned
        ?.takeIf { it.length >= 2 }
}

fun buildSearchEnhancedQuery(originalQuery: String, searchResults: List<com.satory.graphenosai.search.SearchResult>): String {
    val resultsText = buildSearchResultsMessage(searchResults)
    return """
        Answer the user's question using the web search results below.
        Prefer current information from the sources, cite URLs inline, and say when results are insufficient.
        Never say that you do not have internet/web access here; these search results are your web context for this answer.

        User question:
        $originalQuery

        Web search results:
        $resultsText
    """.trimIndent()
}

fun buildWeatherEnhancedQuery(originalQuery: String, weatherContext: String): String {
    return """
        Answer the user's weather question using the structured weather data below.
        Be concise, mention the location and observation time, and include practical advice if useful.
        Do not say that live weather is unavailable; the data below is current API context.

        User question:
        $originalQuery

        Weather API data:
        $weatherContext
    """.trimIndent()
}
