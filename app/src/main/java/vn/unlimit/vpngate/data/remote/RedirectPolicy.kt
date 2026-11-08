
package vn.unlimit.vpngate.data.remote

/**
 * Redirect allowlist for canonical VPN Gate feeds and approved CDN mirrors.
 */
data class RedirectPolicy(
    val allowedAuthorities: Set<String>,
    val allowedSchemes: Set<String> = setOf(
        SourceIdentity.HTTPS,
        SourceIdentity.HTTP
    ),
    val allowSecureUpgrade: Boolean = true,
    val maxHops: Int = DEFAULT_MAX_HOPS
) {

    fun rejectionReason(
        fromUrl: String,
        toUrl: String
    ): String? {

        val from = SourceUrl.parse(
            fromUrl,
            SourceKind.CANONICAL_HTML
        ) ?: return "unparsable source url"

        val to = SourceUrl.parse(
            toUrl,
            SourceKind.CANONICAL_HTML
        ) ?: return "unparsable redirect target"

        if (to.scheme !in allowedSchemes) {
            return "scheme not allowed: ${to.scheme}"
        }

        if (
            from.scheme == SourceIdentity.HTTPS &&
            to.scheme == SourceIdentity.HTTP
        ) {
            return "https downgrade not allowed"
        }

        if (
            !allowSecureUpgrade &&
            from.scheme != to.scheme
        ) {
            return "scheme change not allowed"
        }

        if (to.authority !in allowedAuthorities) {
            return "host not in source allowlist: ${to.authority}"
        }

        return null
    }

    fun allows(
        fromUrl: String,
        toUrl: String
    ): Boolean =
        rejectionReason(fromUrl, toUrl) == null

    fun allowsFinal(
        finalUrl: String
    ): Boolean {

        val to = SourceUrl.parse(
            finalUrl,
            SourceKind.CANONICAL_HTML
        ) ?: return false

        if (to.scheme !in allowedSchemes) {
            return false
        }

        return to.authority in allowedAuthorities
    }

    companion object {

        const val DEFAULT_MAX_HOPS = 5

        /**
         * Canonical VPN Gate hosts and approved CSV CDN hosts.
         */
        val CANONICAL_AUTHORITIES = setOf(
            "www.vpngate.net",
            "vpngate.net",

            "fastly.jsdelivr.net",
            "gcore.jsdelivr.net",
            "testingcf.jsdelivr.net",
            "quantil.jsdelivr.net",
            "cdn.jsdelivr.net",

            "raw.githubusercontent.com",
            "githubusercontent.com",
            "raw.github.com"
        )

        fun canonical(): RedirectPolicy =
            RedirectPolicy(
                allowedAuthorities =
                    CANONICAL_AUTHORITIES
            )

        fun forMirror(
            identity: SourceIdentity
        ): RedirectPolicy =
            RedirectPolicy(
                allowedAuthorities =
                    setOf(identity.authority)
            )
    }
}
