package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.select.Elements

open class EkinoProvider : MainAPI() {
    override var mainUrl = "https://ekino-tv.pl/"
    override var name = "ekino-tv.pl"
    override var lang = "pl"
    override val hasMainPage = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // Główna strona z listą filmów i miniaturami
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val lists = document.select(".list")
        val categories = ArrayList<HomePageList>()
        for (list in lists) {
            val title = list.parent()?.selectFirst("h4")?.text()?.capitalize() ?: "Kategoria"
            val items = list.select(".scope_left").mapNotNull { item ->
                val parent = item.parent()
                val name = parent?.selectFirst(".title")?.text() ?: return@mapNotNull null
                val href = parent.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = item.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: "https://via.placeholder.com/68x90/000000/FFFFFF/?text=No+Image"
                val year = parent.selectFirst(".cates")?.text()?.toIntOrNull()
                MovieSearchResponse(
                    name,
                    fixUrl(href),
                    this.name,
                    TvType.Movie,
                    poster,
                    year
                )
            }
            categories.add(HomePageList(title, items))
        }
        return HomePageResponse(categories)
    }

    // Wyszukiwanie filmów z miniaturami i opisem
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/qf/?q=$query"
        val document = app.get(url).document
        val movieItems = document.select(".movies-list-item")

        return movieItems.mapNotNull { item ->
            val href = item.selectFirst(".title > a")?.attr("href") ?: return@mapNotNull null
            val img = item.selectFirst(".cover-list img")?.attr("src")?.let { fixUrl(it) }
            val name = item.selectFirst(".title > a")?.text() ?: return@mapNotNull null
            val description = item.selectFirst(".movieDesc")?.text()

            MovieSearchResponse(
                name = name,
                url = fixUrl(href),
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = img,
                plot = description
            )
        }
    }

    // Strona szczegółów filmu lub serialu z opisem i zdjęciem
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val documentTitle = document.select("title").text().trim()

        if (documentTitle.startsWith("Logowanie")) {
            throw RuntimeException("This page seems to be locked behind a login-wall on the website, unable to scrape it. If it is not please report it.")
        }

        var title = document.select("h1.title").text()
        val data = document.select("#link-list").outerHtml()
        val posterUrl = document.select("img.moviePoster").attr("src").let { fixUrl(it) }
        val plot = document.select(".descriptionMovie").text()
        val episodesElements = document.select("#episode-list a[href]")

        if (episodesElements.isEmpty()) {
            return MovieLoadResponse(title, url, name, TvType.Movie, data, posterUrl, null, plot)
        }

        title = document.selectFirst(".info")?.parent()?.select("h2")?.text() ?: title
        val episodes = episodesElements.mapNotNull { episode ->
            val episodeText = episode.text()
            val regex = Regex("""\[s(\d{1,3})e(\d{1,3})]""").find(episodeText) ?: return@mapNotNull null
            val eid = regex.groups
            Episode(
                fixUrl(episode.attr("href")),
                episodeText.split("]")[1].trim(),
                eid[1]?.value?.toInt(),
                eid[2]?.value?.toInt()
            )
        }.toMutableList()

        return TvSeriesLoadResponse(
            title,
            url,
            name,
            TvType.TvSeries,
            episodes,
            posterUrl,
            null,
            plot
        )
    }

    // Pobieranie linków wideo
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = if (data.startsWith("http"))
            app.get(data).document.selectFirst("#link-list")
        else Jsoup.parse(data)

        document?.select(".warning-msg-bold")?.forEach { item ->
            val decoded = base64Decode(item.selectFirst("a")?.attr("data-iframe") ?: return@forEach)
            val link = tryParseJson<LinkElement>(decoded)?.src ?: return@forEach
            loadExtractor(link, subtitleCallback, callback)
        }
        return true
    }

    // Naprawianie linków do zdjęć z pełnym adresem URL
    private fun fixUrl(url: String?): String {
        return when {
            url == null || url.isEmpty() -> "https://via.placeholder.com/68x90/000000/FFFFFF/?text=No+Image"
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url.removePrefix("/")
            else -> url
        }
    }
}

// Model JSON do dekodowania linków
data class LinkElement(
    @JsonProperty("src") val src: String
)
