package vn.unlimit.vpngate.collector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.unlimit.vpngate.data.model.VpnPortSource
import vn.unlimit.vpngate.data.model.VpnRecords
import vn.unlimit.vpngate.data.model.VpnServerRecord
import vn.unlimit.vpngate.data.remote.FetchedSource
import vn.unlimit.vpngate.data.remote.HttpFetcher
import vn.unlimit.vpngate.data.remote.HttpResponse
import vn.unlimit.vpngate.data.remote.RedirectPolicy
import vn.unlimit.vpngate.data.remote.SourceIdentity
import vn.unlimit.vpngate.data.remote.SourceKind
import vn.unlimit.vpngate.data.remote.SourceRejectReason
import vn.unlimit.vpngate.data.remote.SourceUrl
import vn.unlimit.vpngate.data.remote.VpnGateApiSource
import vn.unlimit.vpngate.data.remote.VpnGateHtmlSource
import vn.unlimit.vpngate.data.remote.VpnGateMirrorSource
import vn.unlimit.vpngate.data.remote.VpnGateSourceCandidates
import vn.unlimit.vpngate.data.remote.VpnGateSourceValidation
import vn.unlimit.vpngate.data.remote.VpnGateUrls
import vn.unlimit.vpngate.merger.Provenance
import vn.unlimit.vpngate.merger.VpnServerMerger
import vn.unlimit.vpngate.parser.VpnGateHtmlParser
import vn.unlimit.vpngate.repository.VpnServerRepository

/**
 * Stage 2C-A: source identity and mirror validation.
 *
 * Everything here is deterministic and offline. A mirror body must keep
 * its own identity, a page that only satisfies a keyword must be refused,
 * a redirect may not leave its source, and canonical HTML and mirror HTML
 * must stay distinguishable all the way into the merged record.
 */
class MirrorValidationTest {

    // ---- test fetcher -------------------------------------------------

    private class FakeHttpFetcher(
        private val handler: (String) -> HttpResponse?,
    ) : HttpFetcher {
        val requested = mutableListOf<String>()

        override suspend fun get(
            url: String,
            redirectPolicy: RedirectPolicy?,
        ): HttpResponse? {
            requested.add(url)
            return handler(url)
        }
    }

    /**
     * Builds one response. A redirect is only followed when the source's
     * own policy allows it — exactly what [OkHttpFetcher] does — so a
     * refused hop comes back as a rejected redirect instead of a body.
     */
    private fun response(
        requestedUrl: String,
        body: String?,
        finalUrl: String = requestedUrl,
        status: Int = 200,
        contentType: String? = "text/html; charset=utf-8",
    ): HttpResponse {
        if (finalUrl != requestedUrl) {
            val policy = policyFor(requestedUrl)
            val reason = policy?.rejectionReason(requestedUrl, finalUrl)
            if (reason != null) {
                return HttpResponse(
                    requestedUrl = requestedUrl,
                    finalUrl = requestedUrl,
                    status = 302,
                    contentType = null,
                    body = null,
                    redirectChain = emptyList(),
                    redirectRejected = true,
                    redirectRejectionReason = reason,
                )
            }
        }
        return HttpResponse(
            requestedUrl = requestedUrl,
            finalUrl = finalUrl,
            status = status,
            contentType = contentType,
            body = body,
        )
    }

    private fun policyFor(requestedUrl: String): RedirectPolicy? {
        val canonical = requestedUrl.startsWith("http://www.vpngate.net") ||
            requestedUrl.startsWith("https://www.vpngate.net")
        val identity = SourceUrl.parse(
            requestedUrl,
            if (canonical) SourceKind.CANONICAL_HTML else SourceKind.BOOTSTRAP_MIRROR,
        ) ?: return null
        return identity.redirectPolicy()
    }

    private fun mirrorIdentity(url: String = MirrorTestFixtures.BOOTSTRAP_MIRROR_URL) =
        SourceUrl.forMirror(url, SourceKind.BOOTSTRAP_MIRROR)!!

    private fun fetched(
        identity: SourceIdentity,
        body: String?,
        finalUrl: String = identity.url,
        status: Int = 200,
        contentType: String? = "text/html; charset=utf-8",
    ) = FetchedSource(
        identity = identity,
        response = HttpResponse(
            requestedUrl = identity.url,
            finalUrl = finalUrl,
            status = status,
            contentType = contentType,
            body = body,
        ),
    )

    private fun fetchHtml(fetcher: HttpFetcher, mirror: Boolean = false) = runBlocking {
        // Mirrors reach the source layer already numbered, exactly as
        // discoverMirrors() hands them over in production.
        val candidates =
            if (mirror) listOf(mirrorIdentity().withMirrorId("mirror_1")) else emptyList()
        VpnGateHtmlSource(fetcher).fetch(candidates)
    }

    private fun isMirrorUrl(url: String) = url.startsWith(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL)

    // ---- M1: a complete mirror is accepted, with its own identity ----

    @Test
    fun validMirrorHtmlIsAcceptedWithItsOwnIdentity() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) {
                response(url, MirrorTestFixtures.VALID_MIRROR_HTML)
            } else {
                null
            }
        }

        val source = fetchHtml(fetcher, mirror = true)

        assertNotNull("a complete mirror must be accepted", source)
        val identity = source!!.identity
        assertEquals(SourceKind.BOOTSTRAP_MIRROR, identity.kind)
        assertTrue(identity.isMirror)
        assertFalse(identity.isCanonical)
        assertNotEquals("a mirror must never be tagged canonical", "html", identity.sourceTag)
        assertTrue(identity.sourceTag.startsWith("mirror_"))
        assertTrue(VpnGateSourceValidation.validateHtml(source).accepted)
    }

    @Test
    fun mirrorTagIsNeverHtmlOrApi() {
        assertEquals("mirror_1", mirrorIdentity().withMirrorId("mirror_1").sourceTag)
        assertNotEquals("html", mirrorIdentity().withMirrorId("mirror_1").sourceTag)
        assertNotEquals("api", mirrorIdentity().withMirrorId("mirror_1").sourceTag)
        assertEquals(
            "html",
            SourceUrl.parse(VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML)!!.sourceTag,
        )
        assertEquals(
            "api",
            SourceUrl.parse(VpnGateUrls.API_URL, SourceKind.CANONICAL_API)!!.sourceTag,
        )
        // An unnumbered mirror is still never canonical.
        assertEquals("mirror_unnumbered", mirrorIdentity().sourceTag)
    }

    // ---- M2: a missing required column is refused, never guessed ----

    @Test
    fun mirrorMissingRequiredColumnIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) {
                response(url, MirrorTestFixtures.INVALID_MIRROR_MISSING_COLUMNS)
            } else {
                null
            }
        }

        assertNull(
            "a mirror without the OpenVPN column must be refused",
            fetchHtml(fetcher, mirror = true),
        )
    }

    @Test
    fun missingColumnIsReportedAsMissingRequiredColumns() {
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.INVALID_MIRROR_MISSING_COLUMNS,
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.MISSING_REQUIRED_COLUMNS, validation.reason)
    }

    // ---- M3/M4: keyword-only and error pages ----

    @Test
    fun keywordOnlyPageIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) response(url, MirrorTestFixtures.KEYWORD_ONLY_PAGE) else null
        }

        assertNull(
            "a page that only contains the keywords must be refused",
            fetchHtml(fetcher, mirror = true),
        )
    }

    @Test
    fun errorPageWithKeywordsIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) {
                response(url, MirrorTestFixtures.ERROR_PAGE_WITH_KEYWORDS)
            } else {
                null
            }
        }

        assertNull(
            "an error/interstitial page must be refused",
            fetchHtml(fetcher, mirror = true),
        )
    }

    @Test
    fun httpErrorStatusIsRejected() {
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.VALID_MIRROR_HTML,
                status = 503,
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.HTTP_ERROR, validation.reason)
    }

    @Test
    fun unsupportedContentTypeIsRejected() {
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.VALID_MIRROR_HTML,
                contentType = "text/csv",
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.UNSUPPORTED_CONTENT_TYPE, validation.reason)
    }

    @Test
    fun missingContentTypeIsToleratedWhenTheStructureIsValid() {
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.VALID_MIRROR_HTML,
                contentType = null,
            ),
        )
        assertTrue(validation.accepted)
    }

    // ---- M5/M6: redirects ----

    @Test
    fun mirrorRedirectToSameHostIsAccepted() = runBlocking {
        val sameAuthority = "http://198.51.100.20:1770/en/index.html"
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) {
                response(url, MirrorTestFixtures.VALID_MIRROR_HTML, finalUrl = sameAuthority)
            } else {
                null
            }
        }

        val source = fetchHtml(fetcher, mirror = true)
        assertNotNull("a same-authority redirect must be followed", source)
        assertEquals("mirror_1", source!!.identity.sourceTag)
        assertEquals(sameAuthority, source.response.finalUrl)
    }

    @Test
    fun mirrorRedirectToUnrelatedHostIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (isMirrorUrl(url)) {
                response(
                    url,
                    MirrorTestFixtures.VALID_MIRROR_HTML,
                    finalUrl = MirrorTestFixtures.OTHER_MIRROR_URL + "/en/",
                )
            } else {
                null
            }
        }

        assertNull(
            "a redirect to a different mirror must be refused",
            fetchHtml(fetcher, mirror = true),
        )
    }

    @Test
    fun redirectRejectedByFetcherIsRefused() {
        val identity = mirrorIdentity().withMirrorId("mirror_1")
        val validation = VpnGateSourceValidation.validateHtml(
            FetchedSource(
                identity = identity,
                response = HttpResponse(
                    requestedUrl = identity.url,
                    finalUrl = identity.url,
                    status = 302,
                    contentType = null,
                    body = null,
                    redirectRejected = true,
                    redirectRejectionReason = "host not in source allowlist: example.com",
                ),
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.REDIRECT_REJECTED, validation.reason)
    }

    @Test
    fun acceptanceRejectsForeignFinalUrlEvenIfFetcherIgnoredPolicy() {
        // Defence in depth: a fetcher that ignored the policy and reported
        // a foreign final URL still cannot get its body accepted.
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.VALID_MIRROR_HTML,
                finalUrl = MirrorTestFixtures.OTHER_MIRROR_URL + "/en/",
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.REDIRECT_LEFT_SOURCE, validation.reason)
    }

    @Test
    fun redirectPolicyRejectsUnrelatedHostAndAllowsCanonicalAlias() {
        val mirror = mirrorIdentity()
        val mirrorPolicy = mirror.redirectPolicy()
        assertTrue("same authority must be allowed", mirrorPolicy.allows(mirror.url, mirror.url))
        assertFalse(
            "another mirror must not be allowed",
            mirrorPolicy.allows(mirror.url, MirrorTestFixtures.OTHER_MIRROR_URL + "/en/"),
        )
        assertFalse(
            "an unrelated host must not be allowed",
            mirrorPolicy.allows(mirror.url, "http://example.com/en/"),
        )
        assertFalse(
            "a different port on the same IP is a different mirror",
            mirrorPolicy.allows(mirror.url, "http://198.51.100.20:9999/en/"),
        )

        val canonical = SourceUrl.parse(VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML)!!
        val canonicalPolicy = canonical.redirectPolicy()
        assertTrue(
            "www -> apex is inside the canonical allowlist",
            canonicalPolicy.allows(canonical.url, "https://vpngate.net/en/"),
        )
        assertTrue(
            "an http -> https upgrade on the canonical host is allowed",
            canonicalPolicy.allows("http://www.vpngate.net/en/", canonical.url),
        )
        assertFalse(
            "an https downgrade must be refused",
            canonicalPolicy.allows(canonical.url, "http://www.vpngate.net/en/"),
        )
        assertFalse(
            "canonical must not be allowed to move to a mirror",
            canonicalPolicy.allows(canonical.url, "http://198.51.100.20:1770/en/"),
        )
    }

    // ---- M7/M8: canonical HTML ----

    @Test
    fun canonicalHtmlIsAcceptedAsCanonical() = runBlocking {
        val fixture = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        val fetcher = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/en/")) response(url, fixture) else null
        }

        val source = fetchHtml(fetcher)
        assertNotNull("the real canonical page must be accepted", source)
        assertTrue(source!!.identity.isCanonical)
        assertEquals("html", source.identity.sourceTag)
    }

    @Test
    fun canonicalHtmlWithInvalidSchemaIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/en/")) {
                response(url, MirrorTestFixtures.INVALID_MIRROR_MISSING_COLUMNS)
            } else {
                null
            }
        }

        assertNull(
            "canonical HTML with an incomplete schema must be refused",
            fetchHtml(fetcher),
        )
    }

    @Test
    fun canonicalKeywordOnlyPageIsRejected() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/en/")) {
                response(url, MirrorTestFixtures.KEYWORD_ONLY_PAGE)
            } else {
                null
            }
        }

        assertNull(fetchHtml(fetcher))
    }

    // ---- Phase E: an undefined schema is refused, not guessed ----

    @Test
    fun mirrorServingCsvIsRejectedAsUnsupportedSchema() {
        val validation = VpnGateSourceValidation.validateHtml(
            fetched(
                mirrorIdentity().withMirrorId("mirror_1"),
                MirrorTestFixtures.MIRROR_CSV_BODY,
            ),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.UNSUPPORTED_SCHEMA, validation.reason)
    }

    @Test
    fun canonicalApiIsAcceptedAndAnHtmlBodyOnItIsRefused() = runBlocking {
        val csv = MirrorTestFixtures.canonicalApiCsv()
        val ok = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/api/iphone/")) {
                HttpResponse(url, url, 200, "text/csv", csv)
            } else {
                null
            }
        }
        val accepted = VpnGateApiSource(ok).fetch()
        assertNotNull("the canonical CSV feed must be accepted", accepted)
        assertEquals("api", accepted!!.identity.sourceTag)

        val htmlOnApiPath = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/api/iphone/")) {
                HttpResponse(url, url, 200, "text/html", MirrorTestFixtures.KEYWORD_ONLY_PAGE)
            } else {
                null
            }
        }
        assertNull(
            "an HTML body on the CSV endpoint must be refused",
            VpnGateApiSource(htmlOnApiPath).fetch(),
        )
    }

    @Test
    fun apiSourceNeverRequestsAMirror() = runBlocking {
        val fetcher = FakeHttpFetcher { null }
        VpnGateApiSource(fetcher).fetch()
        assertTrue(
            "the CSV endpoint must only ever be requested canonically: ${fetcher.requested}",
            fetcher.requested.all {
                it.startsWith("http://www.vpngate.net") || it.startsWith("https://www.vpngate.net")
            },
        )
    }

    // ---- Phase H: the mirror cache turns back into identities ----

    @Test
    fun cachedMirrorUrlsBecomeDeterministicIdentities() {
        val identities = VpnGateSourceCandidates.cachedMirrors(
            listOf(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL, "https://cached.opengw.net"),
        )
        assertEquals(2, identities.size)
        assertEquals(SourceKind.CACHED_MIRROR, identities[0].kind)
        assertEquals(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL, identities[0].baseUrl)
        assertEquals("198.51.100.20", identities[0].host)
        assertEquals(1770, identities[0].port)
        assertEquals("cached.opengw.net", identities[1].host)
        // Same URL in, same identity out.
        assertEquals(
            identities[0],
            VpnGateSourceCandidates.cachedMirrors(
                listOf(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL),
            )[0],
        )
    }

    @Test
    fun invalidCachedMirrorUrlsAreDropped() {
        val identities = VpnGateSourceCandidates.cachedMirrors(
            listOf(
                "not a url",
                "ftp://example.com/pub",
                "file:///etc/passwd",
                "https://",
                "https://vpngate.net/en/",
                MirrorTestFixtures.OTHER_MIRROR_URL,
            ),
        )
        assertEquals(1, identities.size)
        assertEquals(MirrorTestFixtures.OTHER_MIRROR_URL, identities[0].baseUrl)
    }

    @Test
    fun unparsableSourceUrlsYieldNoIdentity() {
        assertNull(SourceUrl.parse("", SourceKind.CANONICAL_HTML))
        assertNull(SourceUrl.parse("www.vpngate.net/en/", SourceKind.CANONICAL_HTML))
        assertNull(SourceUrl.parse("https://", SourceKind.CANONICAL_HTML))
        assertNull(SourceUrl.parse("https://user:pw@www.vpngate.net/en/", SourceKind.CANONICAL_HTML))
        assertNull(SourceUrl.parse("https://www.vpngate.net:99999/en/", SourceKind.CANONICAL_HTML))
        assertNull(SourceUrl.parse("https://localhost/en/", SourceKind.CANONICAL_HTML))
    }

    @Test
    fun mirrorDiscoveryNumbersAndDeduplicatesIdentities() {
        val discovered = SourceUrl.forMirror(
            MirrorTestFixtures.OTHER_MIRROR_URL,
            SourceKind.DISCOVERED_MIRROR,
        )!!
        val cached = VpnGateSourceCandidates.cachedMirrors(
            listOf(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL),
        )

        val numbered = VpnGateSourceCandidates.numberedMirrors(listOf(discovered), cached)

        assertEquals("mirror_1", numbered[0].sourceTag)
        assertEquals("mirror_2", numbered[1].sourceTag)
        assertEquals(discovered.baseUrl, numbered[0].baseUrl)
        assertEquals(
            numbered.size,
            numbered.map { it.baseUrl }.distinct().size,
        )
        assertTrue(numbered.size <= VpnGateUrls.MAX_MIRRORS)
        // The built-in bootstrap list is available even without a directory.
        assertTrue(numbered.any { it.kind == SourceKind.BOOTSTRAP_MIRROR })
    }

    // ---- Phase N: merge keeps both origins distinguishable ----

    @Test
    fun mirrorAndCanonicalRecordsMergeWithBothProvenances() {
        val canonical = VpnGateHtmlParser.parseHtml(
            MirrorTestFixtures.VALID_MIRROR_HTML,
            SourceIdentity.HTML_TAG,
        ).first()
        val mirror = VpnGateHtmlParser.parseHtml(
            MirrorTestFixtures.VALID_MIRROR_HTML,
            "mirror_1",
        ).first()

        val merged = VpnServerMerger.mergeRecords(listOf(canonical, mirror))

        assertEquals("one IP must stay one server", 1, merged.size)
        val record: VpnServerRecord = merged.first()
        val sources = VpnRecords.sources(record)
        assertTrue("canonical source lost: $sources", "html" in sources)
        assertTrue("mirror source lost: $sources", "mirror_1" in sources)

        // MIRROR_GROUP_RE is finally reachable.
        assertEquals("mirror", Provenance.sourceGroup("mirror_1"))
        val groups = Provenance.independentSourceGroups(record)
        assertTrue("groups=$groups", groups.containsAll(listOf("html", "mirror")))
        assertEquals(2, groups.size)

        val ipSources = VpnRecords.fieldSources(record)["identity.ip"]
        assertTrue(
            "field sources=$ipSources",
            ipSources!!.containsAll(listOf("html", "mirror_1")),
        )
    }

    @Test
    fun canonicalWinsAConflictWithAMirrorButTheMirrorIsStillRecorded() {
        val canonical = VpnRecords.newServer()
        VpnRecords.setField(canonical, "identity.ip", MirrorTestFixtures.SYNTHETIC_IP, "html")
        VpnRecords.setField(canonical, "performance.sessions", 10, "html")
        VpnRecords.sourceAdd(canonical, "html")

        val mirror = VpnRecords.newServer()
        VpnRecords.setField(mirror, "identity.ip", MirrorTestFixtures.SYNTHETIC_IP, "mirror_1")
        VpnRecords.setField(mirror, "performance.sessions", 99, "mirror_1")
        VpnRecords.sourceAdd(mirror, "mirror_1")

        val merged = VpnServerMerger.mergeRecords(listOf(canonical, mirror))
        assertEquals(1, merged.size)
        val record = merged.first()
        assertEquals(
            "canonical must keep authority over a mirror",
            10,
            VpnRecords.int(VpnRecords.performance(record)["sessions"]),
        )
        assertTrue(VpnRecords.sources(record).contains("mirror_1"))
        assertTrue(VpnRecords.conflicts(record).isNotEmpty())
    }

    // ---- end to end through the repository ----

    @Test
    fun repositoryKeepsMirrorProvenanceAndNeverTagsItHtml() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            when {
                isMirrorUrl(url) -> response(url, MirrorTestFixtures.VALID_MIRROR_HTML)
                url.contains("sites.aspx") -> response(url, MirrorTestFixtures.MIRROR_DIRECTORY_PAGE)
                else -> null
            }
        }

        val repository = VpnServerRepository(fetcher = fetcher)
        val result = repository.refresh()

        assertNotNull("a mirror-only run must still produce a list", result)
        assertFalse("a mirror-only run is not a cache fallback", result!!.fromCache)
        assertTrue(result.connectionList.size() > 0)

        val payload = repository.debugPayload(
            MirrorTestFixtures.SYNTHETIC_IP,
            MirrorTestFixtures.SYNTHETIC_HOST,
        )
        assertNotNull("the merged record must be traceable", payload)
        val dump = payload!!.dump
        assertTrue("mirror id missing from provenance:\n$dump", "mirror_1" in dump)
        assertTrue("canonical tag wrongly attributed:\n$dump", "<- html" !in dump)
    }

    @Test
    fun repositoryFallsBackWhenEverySourceIsRefused() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            when {
                isMirrorUrl(url) -> response(url, MirrorTestFixtures.KEYWORD_ONLY_PAGE)
                url.contains("sites.aspx") -> response(url, MirrorTestFixtures.MIRROR_DIRECTORY_PAGE)
                else -> null
            }
        }

        assertNull(
            "no valid source means no fresh dataset",
            VpnServerRepository(fetcher = fetcher).refresh(),
        )
    }

    @Test
    fun mirrorDirectoryWithoutUsableLinksIsRefused() {
        val identity = SourceUrl.parse(VpnGateUrls.MIRRORS_URL, SourceKind.CANONICAL_HTML)!!
        val validation = VpnGateSourceValidation.validateDirectory(
            fetched(identity, MirrorTestFixtures.DIRECTORY_WITHOUT_USABLE_MIRRORS),
        )
        assertFalse(validation.accepted)
        assertEquals(SourceRejectReason.NO_ACCEPTABLE_MIRROR, validation.reason)
    }

    @Test
    fun mirrorDiscoveryAcceptsTheDirectoryPageAndItsOneValidLink() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            if (url.startsWith("https://www.vpngate.net/en/sites.aspx")) {
                response(url, MirrorTestFixtures.MIRROR_DIRECTORY_PAGE)
            } else {
                null
            }
        }

        val mirrors = VpnGateMirrorSource(fetcher).discoverMirrors(emptyList())

        assertTrue(mirrors.isNotEmpty())
        val discovered = mirrors.first { it.kind == SourceKind.DISCOVERED_MIRROR }
        assertEquals(MirrorTestFixtures.BOOTSTRAP_MIRROR_URL, discovered.baseUrl)
        assertTrue(discovered.sourceTag.startsWith("mirror_"))
        assertTrue("no mirror may be canonical", mirrors.none { it.isCanonical })
    }

    // ---- no protocol/port regression on the new mirror path ----

    @Test
    fun mirrorSourcedRecordsKeepProtocolPortSemantics() = runBlocking {
        val fetcher = FakeHttpFetcher { url ->
            when {
                isMirrorUrl(url) -> response(url, MirrorTestFixtures.VALID_MIRROR_HTML)
                url.contains("sites.aspx") -> response(url, MirrorTestFixtures.MIRROR_DIRECTORY_PAGE)
                else -> null
            }
        }

        val result = VpnServerRepository(fetcher = fetcher).refresh()
        assertNotNull(result)

        val connection = (0 until result!!.connectionList.size())
            .mapNotNull { result.connectionList.get(it) }
            .first { it.ip == MirrorTestFixtures.SYNTHETIC_IP }

        // Printed ports stay protocol-specific and keep their provenance.
        assertEquals(443, connection.seTcpPort)
        assertEquals(0, connection.seUdpPort)
        assertFalse(connection.seUdpSupported)
        assertEquals(1300, connection.tcpPort)
        assertEquals(0, connection.udpPort)
        assertEquals(995, connection.sstpPort)
        assertEquals(VpnPortSource.EXPLICIT, connection.sstpPortSource)
        assertEquals(995, connection.sstpConnectPort)
    }

    @Test
    fun apiConfigDoesNotRemoveHtmlOpenVpnUdpPort() {
        // Stage 2A-B invariant, re-checked after the merge/source rework:
        // an API record that carries only an OpenVPN profile must not
        // delete the UDP port the HTML source published.
        val html = CollectorTestFixtures.load(CollectorTestFixtures.TABLE_SAMPLE)
        val htmlRecords = VpnGateHtmlParser.parseHtml(html, SourceIdentity.HTML_TAG)
        val target = htmlRecords.first { record ->
            VpnRecords.int(
                VpnRecords.transport(
                    VpnRecords.map(VpnRecords.protocol(record, "openvpn")),
                    "udp",
                )["port"],
            ) > 0
        }
        val ip = VpnRecords.str(VpnRecords.identity(target)["ip"])
        val udpPort = VpnRecords.int(
            VpnRecords.transport(
                VpnRecords.map(VpnRecords.protocol(target, "openvpn")),
                "udp",
            )["port"],
        )
        assertTrue("fixture must contain an OpenVPN UDP port", udpPort > 0)

        val apiRecord = vn.unlimit.vpngate.parser.VpnGateApiParser.parseApi(
            "*vpn_servers\n" + CollectorTestFixtures.makeApiRow(
                mapOf(
                    "IP" to ip,
                    "HostName" to "public-vpn-206",
                    "OpenVPN_ConfigData_Base64" to java.util.Base64.getEncoder()
                        .encodeToString(
                            "dev tun\nproto tcp\nremote $ip 443\n".toByteArray(),
                        ),
                ),
            ),
            SourceIdentity.API_TAG,
        ).first()

        val merged = VpnServerMerger.mergeRecords(listOf(htmlRecords.first(), apiRecord))
        val mergedUdp = VpnRecords.int(
            VpnRecords.transport(
                VpnRecords.map(VpnRecords.protocol(merged.first(), "openvpn")),
                "udp",
            )["port"],
        )
        assertEquals("the HTML UDP port must survive the merge", udpPort, mergedUdp)
    }
}
