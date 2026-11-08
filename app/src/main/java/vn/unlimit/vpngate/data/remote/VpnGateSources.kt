
package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.unlimit.vpngate.data.model.CollectorLog
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/**
 * Live VPN Gate sources.
 */
object VpnGateUrls {

    const val MAIN_URL = "https://www.vpngate.net/en/"

    // Primary API mirror
    const val API_URL =
        "https://fastly.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv"

    // Fallback API mirrors
    val API_FALLBACK_URLS = listOf(
        "https://gcore.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
        "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
        "https://testingcf.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv",
        "https://quantil.jsdelivr.net/gh/GeorgeXie2333/vpngate-list-mirror@latest/data/vpngate.csv"
    )

    const val MIRRORS_URL = "https://www.vpngate.net/en/sites.aspx"
    const MAX_MIRRORS = 12

    const val HTML_PATH = "/en/"
    const val API_PATH = "/api/iphone/"
    const DIRECTORY_PATH = "/en/sites.aspx"

    val BOOTSTRAP_MIRRORS = listOf(
        "http://150.40.105.19:35399",
        "http://150.40.105.6:11803",
        "http://150.40.105.11:4917",
        "http://150.40.105.23:64629",
        "http://194.156.89.134:47774",
        "http://121.186.186.97:40587"
    )
}

/**
 * Builds candidate lists for the collector.
 */
object VpnGateSourceCandidates {

    fun canonicalHtml(): List<SourceIdentity> =
        listOfNotNull(
            SourceUrl.parse(
                VpnGateUrls.MAIN_URL,
                SourceKind.CANONICAL_HTML
            )
        )

    /**
     * Primary first, followed by alternate CDN/raw sources.
     * The fetch race accepts the first structurally valid CSV.
     */
    fun canonicalApi(): List<SourceIdentity> =
        (listOf(VpnGateUrls.API_URL) + VpnGateUrls.API_FALLBACK_URLS)
            .mapNotNull { url ->
                SourceUrl.parse(url, SourceKind.CANONICAL_API)
            }

    fun canonicalDirectory(): List<SourceIdentity> =
        listOfNotNull(
            SourceUrl.parse(
                VpnGateUrls.MIRRORS_URL,
                SourceKind.CANONICAL_HTML
            )?.withPath(VpnGateUrls.DIRECTORY_PATH)
        )

    fun bootstrapMirrors(): List<SourceIdentity> =
        VpnGateUrls.BOOTSTRAP_MIRRORS.mapNotNull {
            SourceUrl.forMirror(
                it,
                SourceKind.BOOTSTRAP_MIRROR,
                MirrorOrigin.BOOTSTRAP_LIST
            )
        }

    fun cachedMirrors(urls: List<String>): List<SourceIdentity> =
        urls.mapNotNull { url ->
            val identity = SourceUrl.forMirror(
                url,
                SourceKind.CACHED_MIRROR,
                MirrorOrigin.MIRROR_CACHE
            )

            if (identity == null) {
                CollectorLog.d(
                    "Mirror cache entry ignored: $url"
                )
            }

            identity
        }

    fun numberedMirrors(
        discovered: List<SourceIdentity>,
        cached: List<SourceIdentity>
    ): List<SourceIdentity> {

        val combined = (discovered + cached + bootstrapMirrors())
            .distinctBy { it.baseUrl }
            .take(VpnGateUrls.MAX_MIRRORS)

        return combined.mapIndexed { index, identity ->
            identity.withMirrorId(MIRROR_TAG_PREFIX + (index + 1))
        }
    }

    const val MIRROR_TAG_PREFIX = "mirror_"
}

/**
 * Fetch candidates concurrently and accept the first valid response.
 */
private suspend fun raceFetch(
    candidates: List<SourceIdentity>,
    fetcher: HttpFetcher,
    validate: (FetchedSource) -> SourceValidation,
    timeoutMs: Long = 12_000L
): FetchedSource? = coroutineScope {

    val result = CompletableDeferred<FetchedSource>()

    val jobs = candidates.distinctBy { it.url }.map { identity ->
        launch(Dispatchers.IO) {
            runCatching {
                val response = fetcher.get(
                    identity.url,
                    identity.redirectPolicy()
                )

                if (response == null) {
                    CollectorLog.d(
                        "No response from ${identity.sourceTag}: ${identity.url}"
                    )
                    return@runCatching
                }

                val source = FetchedSource(identity, response)
                val check = validate(source)

                if (check.accepted) {
                    CollectorLog.d(
                        "Accepted ${identity.url}: ${check.detail}"
                    )
                    result.complete(source)
                } else {
                    CollectorLog.d(
                        "Rejected ${identity.url}: ${check.reason} (${check.detail})"
                    )
                }
            }
        }
    }

    launch {
        jobs.joinAll()

        if (!result.isCompleted) {
            result.completeExceptionally(
                NoSuchElementException()
            )
        }
    }

    try {
        withTimeoutOrNull(timeoutMs) {
            result.await()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    } finally {
        jobs.forEach { it.cancel() }
    }
}

/**
 * Canonical and mirror HTML source.
 */
class VpnGateHtmlSource(
    private val fetcher: HttpFetcher
) {

    suspend fun fetch(
        extraCandidates: List<SourceIdentity> = emptyList()
    ): FetchedSource? {

        val candidates =
            VpnGateSourceCandidates.canonicalHtml() +
                extraCandidates.map {
                    it.withPath(VpnGateUrls.HTML_PATH)
                }

        return raceFetch(
            candidates,
            fetcher,
            validate = {
                VpnGateSourceValidation.validateHtml(it)
            },
            timeoutMs = 12_000L
        )
    }
}

/**
 * Canonical CSV API source with alternate CDN endpoints.
 */
class VpnGateApiSource(
    private val fetcher: HttpFetcher
) {

    suspend fun fetch(
        extraCandidates: List<SourceIdentity> = emptyList()
    ): FetchedSource? {

        val candidates =
            VpnGateSourceCandidates.canonicalApi() +
                extraCandidates

        return raceFetch(
            candidates,
            fetcher,
            validate = {
                VpnGateSourceValidation.validateApi(it)
            },
            timeoutMs = 12_000L
        )
    }
}

/**
 * Official mirror discovery.
 */
class VpnGateMirrorSource(
    private val fetcher: HttpFetcher
) {

    suspend fun discoverMirrors(
        cachedMirrorUrls: List<String> = emptyList()
    ): List<SourceIdentity> {

        val cached =
            VpnGateSourceCandidates.cachedMirrors(
                cachedMirrorUrls
            )

        val bootstrap =
            VpnGateSourceCandidates.bootstrapMirrors()

        val discoveryCandidates =
            VpnGateSourceCandidates.canonicalDirectory() +
                cached.map {
                    it.withPath(
                        VpnGateUrls.DIRECTORY_PATH
                    )
                } +
                bootstrap.map {
                    it.withPath(
                        VpnGateUrls.DIRECTORY_PATH
                    )
                }

        val page = raceFetch(
            discoveryCandidates,
            fetcher,
            validate = {
                VpnGateSourceValidation.validateDirectory(it)
            },
            timeoutMs = 8_000L
        )

        val discovered =
            if (page == null) {
                emptyList()
            } else {
                extractMirrors(page.body)
            }

        val combined =
            VpnGateSourceCandidates.numberedMirrors(
                discovered,
                cached
            )

        CollectorLog.d(
            "Mirrors available: ${combined.size}"
        )

        return combined
    }

    fun extractMirrors(
        html: String
    ): List<SourceIdentity> {

        val discovered =
            mutableListOf<SourceIdentity>()

        val doc =
            VpnGateHtmlParser.makeSoup(html)

        for (a in doc.getElementsByTag("a")) {

            val href =
                a.attr("href").trim()

            if (!VpnGateSourceValidation
                    .isAcceptableMirrorHref(href)
            ) {
                continue
            }

            val clean =
                href.trimEnd('/')

            val identity =
                SourceUrl.forMirror(
                    clean,
                    SourceKind.DISCOVERED_MIRROR,
                    MirrorOrigin.CANONICAL_DIRECTORY
                ) ?: continue

            if (
                discovered.none {
                    it.baseUrl == identity.baseUrl
                }
            ) {
                discovered.add(identity)
            }
        }

        return discovered
    }

    suspend fun fetchMirror(
        url: String
    ): FetchedSource? {

        val identity =
            SourceUrl.forMirror(
                url,
                SourceKind.DISCOVERED_MIRROR
            ) ?: return withContext(Dispatchers.IO) {
                null
            }

        return raceFetch(
            listOf(
                identity.withPath(
                    VpnGateUrls.HTML_PATH
                )
            ),
            fetcher,
            validate = {
                VpnGateSourceValidation.validateHtml(it)
            }
        )
    }
}
