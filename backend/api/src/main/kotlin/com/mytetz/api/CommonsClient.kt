package com.mytetz.api

import com.mytetz.graph.Ancestor
import com.mytetz.graph.ImageMedia
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("com.mytetz.api.CommonsClient")

/**
 * The wire shape one page of a combined search-and-metadata answer carries.
 *
 * Confirmed by two independent reads, both on 2026-09-19: `action=help&modules=query%2Bsearch` and
 * `action=help&modules=query%2Bimageinfo` on the live `https://commons.wikimedia.org/w/api.php`,
 * and a small number of real, read-only requests against that same endpoint. `formatversion=2`
 * gives `pages` as a list, not an object keyed by page id — the older `formatversion=1` shape this
 * class does not use.
 *
 * `title` still carries the `File:` namespace prefix Commons puts on every page in namespace 6; see
 * [pageTitleWithoutPrefix].
 */
@Serializable
internal data class PageResult(
    val title: String,
    /** The page's own rank in the search this request ran, lower is more relevant. Absent on a
     * response `formatversion=2` did not attach one to — [CommonsClient] then keeps the order the
     * wire sent, which is already relevance order for an ordinary search. */
    val index: Int? = null,
    val imageinfo: List<ImageInfoResult> = emptyList(),
)

/**
 * One file's own metadata, from `prop=imageinfo&iiprop=url|mime|extmetadata&iiurlwidth=<n>`.
 *
 * `thumburl` — not `url` — is the field this class reads for [ImageMedia.imageUrl]. `url` is the
 * original file, which can run to tens of megabytes; `thumburl` is the raster copy `iiurlwidth`
 * asked for, confirmed present in a live answer for both a `.jpg` original and a `.svg` one — for
 * the `.svg` case `thumburl` names a `.png`, which is Commons' own answer to "no browser renders
 * an SVG through iiurlwidth the way it rasterises a photograph": Commons renders the SVG to a PNG
 * once, server-side, and this class only ever asks for that image.
 *
 * `descriptionurl` — confirmed by the same live read, and previously unconfirmed by either help
 * page on its own — is the page a learner's click on the attribution should open.
 */
@Serializable
internal data class ImageInfoResult(
    val url: String? = null,
    val thumburl: String? = null,
    val descriptionurl: String? = null,
    val mime: String? = null,
    val extmetadata: ExtMetadata? = null,
)

/** One `extmetadata` field's value, wrapped the way Commons always wraps it: `{"value": "…"}`. */
@Serializable
internal data class MetadataValue(val value: String? = null)

/**
 * The five `extmetadata` fields this class reads, confirmed at
 * `https://www.mediawiki.org/wiki/Extension:CommonsMetadata` (read 2026-09-19): `LicenseShortName`
 * ("short human-readable license name"), `LicenseUrl`, `Artist`, `Credit` ("source"), and
 * `Attribution` ("custom attribution that should replace Artist + Credit" — the reason
 * [CommonsClient] prefers it over the other two when it is present).
 *
 * Every field here can hold HTML a Commons editor wrote. None of it is ever stored as this class
 * received it — see [CommonsClient.attributionHtmlFor].
 */
@Serializable
internal data class ExtMetadata(
    @SerialName("LicenseShortName") val licenseShortName: MetadataValue? = null,
    @SerialName("LicenseUrl") val licenseUrl: MetadataValue? = null,
    @SerialName("Artist") val artist: MetadataValue? = null,
    @SerialName("Credit") val credit: MetadataValue? = null,
    @SerialName("Attribution") val attribution: MetadataValue? = null,
)

@Serializable
internal data class SearchResponse(val query: QueryResult? = null)

@Serializable
internal data class QueryResult(val pages: List<PageResult> = emptyList())

private val json = Json { ignoreUnknownKeys = true }

/** `File:Example.jpg` -> `Example.jpg`. A display choice this server makes, not a vendor field. */
private fun pageTitleWithoutPrefix(title: String): String = title.removePrefix("File:")

/**
 * The MIME types this project ever shows through an `<img>` element. `image/svg+xml` is on this
 * list for the *original* file's own MIME — Task 6's own rule — while the URL this class actually
 * serves for such a file is always [ImageInfoResult.thumburl], which Commons already rasterises to
 * a PNG. Nothing in [ImageMedia] repeats the MIME type itself; it exists only to let this class
 * refuse a file this project has no safe way to show at all — a PDF, a video, an audio file, all of
 * which live in the same File namespace this search reads.
 */
private val ACCEPTED_ORIGINAL_MIME_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/svg+xml",
)

/**
 * A licence short name this project treats as freely reusable: `CC0`, `Public domain`, `CC BY
 * <version>`, `CC BY-SA <version>`, each with or without a version number.
 *
 * This is Task 6's own product decision, not a vendor fact — the vendor names no fixed enumeration
 * of `LicenseShortName` values anywhere this plan's authors could confirm. Every other short name —
 * `All rights reserved`, a non-commercial or a no-derivatives licence, and anything this list does
 * not know — is refused, on the allowlist principle [SvgSanitizer] already uses elsewhere in this
 * codebase: an unrecognised value is unsafe by default, never safe by default.
 */
private val ALLOWED_LICENSE_SHORT_NAME = Regex(
    "^(cc0|public domain)(\\s+\\d+(\\.\\d+)?)?\$|^cc by(-sa)?\\s+\\d+(\\.\\d+)?\$",
    RegexOption.IGNORE_CASE,
)

/** At most 300 characters of attribution text reach the wire. A licence credit is a name and a
 * short phrase, never a paragraph; this bound exists only to cap what a hostile or a malformed
 * Commons record could otherwise put in front of a learner. */
private const val MAX_ATTRIBUTION_TEXT_CHARS = 300

private val HTML_TAG = Regex("<[^>]*>")
private val FIRST_HREF_VALUE = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val HTML_ENTITY = Regex("&(#x[0-9A-Fa-f]+|#\\d+|[A-Za-z]+);")
private val WHITESPACE_RUN = Regex("\\s+")

private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
)

/**
 * Decodes the small set of HTML entities Commons' own attribution text plausibly carries.
 *
 * This is deliberately not a full HTML-entity table. [CommonsClient.attributionHtmlFor] escapes
 * every character this function decodes right back before the result ever reaches [ImageMedia] —
 * see that function's own KDoc for why an incomplete decode here still cannot produce anything but
 * inert text.
 */
private fun decodeHtmlEntities(input: String): String = HTML_ENTITY.replace(input) { match ->
    val body = match.groupValues[1]
    val decoded = when {
        body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
        body.startsWith("#") -> body.drop(1).toIntOrNull()
        else -> null
    }
    when {
        decoded != null -> runCatching { String(Character.toChars(decoded)) }.getOrNull()
        else -> NAMED_HTML_ENTITIES[body.lowercase()]
    } ?: match.value
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/** The plain-text-plus-at-most-one-link reduction, before either part is escaped. See
 * [CommonsClient.attributionHtmlFor] for how the two parts become one self-authored HTML string. */
internal data class ReducedAttribution(val text: String, val httpsLink: String?)

/**
 * Reduces one `extmetadata` HTML fragment — `Artist`, `Credit` or `Attribution` — to plain text and
 * at most one `https` link.
 *
 * This is a plain-text extraction, not an HTML parser, on purpose: Task 6 asks that the safety here
 * never depend on how exactly this function reads a real Commons fragment, because
 * [CommonsClient.attributionHtmlFor] escapes both parts again on the way out. A tag this function
 * fails to recognise, or a malformed fragment with no closing tag, can only ever leave behind inert,
 * escaped text — never a live element — so a wrong parse costs this feature an ugly attribution
 * line, and nothing more. Jsoup, or a strict XML parse on [SvgSanitizer]'s own model, were both
 * considered; the samples this class was built against — a plain anchor, a `<span>`, an unclosed
 * tag, a bare event handler — never asked for a parser this simple approach cannot already handle
 * safely, so no dependency was added for this one purpose.
 *
 * Only the first `href` value found anywhere in [rawHtml] is ever kept, and only when it starts
 * with the literal scheme `https://`. A `javascript:` value, a plain `http://` value, and a
 * protocol-relative value such as `//commons.wikimedia.org/…` — a shape a real Commons `Artist`
 * field was found to carry, on 2026-09-19 — are all refused by the same rule: this class does not
 * infer a scheme for a value that does not spell it out.
 */
internal fun reduceAttributionHtml(rawHtml: String): ReducedAttribution {
    val httpsLink = FIRST_HREF_VALUE.find(rawHtml)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.startsWith("https://", ignoreCase = true) }

    val text = decodeHtmlEntities(rawHtml.replace(HTML_TAG, " "))
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(MAX_ATTRIBUTION_TEXT_CHARS)

    return ReducedAttribution(text, httpsLink)
}

/**
 * Checks [raw] parses as a URI whose scheme and host match exactly — never a prefix or a suffix
 * test. `java.net.URI`'s own `getHost()` is what this class trusts: a string such as
 * `"https://upload.wikimedia.org.evil.example/"` parses with host
 * `upload.wikimedia.org.evil.example`, which does not equal `upload.wikimedia.org`, so this check
 * refuses it — the exact bypass a `startsWith` or a `contains` test on the raw string would miss.
 */
internal fun hasExactSchemeAndHost(raw: String, scheme: String, host: String): Boolean = try {
    val uri = URI(raw)
    uri.scheme.equals(scheme, ignoreCase = true) && uri.host.equals(host, ignoreCase = true)
} catch (e: URISyntaxException) {
    false
}

/** `licenseUrl` is optional on the wire; absent is accepted. Present, it must be `https` — the
 * same exact-scheme rule [hasExactSchemeAndHost] applies to the other two URLs this class reads,
 * though a license URL carries no fixed host to check against. */
internal fun isAcceptableLicenseUrl(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return true
    return try {
        URI(raw).scheme.equals("https", ignoreCase = true)
    } catch (e: URISyntaxException) {
        false
    }
}

/**
 * Looks up one licensed image for a span, from Wikimedia Commons — the real, Ktor-backed
 * implementation of [com.mytetz.graph.CommonsLookup]. `:backend:graph` never builds this class or
 * imports Ktor; [com.mytetz.api.Components] builds it and adapts it to the port with a lambda, on
 * the model [com.mytetz.graph.CommonsLookup]'s own KDoc names for `Reconciliation.reconcile`'s
 * `fetchState` parameter.
 *
 * ## The one request this class ever sends
 *
 * A single GET to `https://commons.wikimedia.org/w/api.php`, combining a File-namespace search and
 * each hit's own image metadata in one call, through `generator=search` rather than two chained
 * requests. Every parameter name below, and the response's own field names, are confirmed —
 * not guessed — against two sources, both read on 2026-09-19:
 *
 * - The live help pages `action=help&modules=query%2Bsearch` and
 *   `action=help&modules=query%2Bimageinfo` on this same endpoint, which confirm `gsrnamespace`
 *   accepts `6` (the `File:` namespace) with a documented default of `0`, and that `iiurlwidth`
 *   exists ("If iiprop=url is set, a URL to an image scaled to this width will be returned").
 * - A small number of real, read-only GET requests this class's own tests are built from, which
 *   confirm the response's exact field names: `thumburl`, `url`, `descriptionurl` and `mime` on
 *   [ImageInfoResult], and `LicenseShortName`, `LicenseUrl`, `Artist`, `Credit` and `Attribution`
 *   inside `extmetadata` — the last five also named, without their exact casing pinned down, by
 *   `https://www.mediawiki.org/wiki/Extension:CommonsMetadata`.
 *
 * [span] reaches the request only through [io.ktor.client.request.HttpRequestBuilder.parameter] —
 * never through string concatenation into the URL — so a learner's selection cannot inject a second
 * query parameter or otherwise reshape the request. [ancestors] is accepted for the port's own
 * shape and is not read by this class today; nothing about the search this class runs needed it.
 *
 * ## What "accept" means
 *
 * A candidate is used only when every one of these holds; the first candidate — by the search's own
 * relevance order — that holds every one of them wins, and every earlier candidate is skipped, not
 * refused outright, the same way a lower-ranked search hit is an ordinary miss and not a fault.
 *
 * - [ImageInfoResult.mime] is a MIME type this project can show through an `<img>` element.
 * - [ImageInfoResult.thumburl] is present — a candidate with no bounded thumbnail is skipped, never
 *   answered with the unbounded original.
 * - [ImageInfoResult.thumburl] has the scheme `https` and the host `upload.wikimedia.org`, and
 *   [ImageInfoResult.descriptionurl] has the scheme `https` and the host `commons.wikimedia.org` —
 *   both checked with [hasExactSchemeAndHost], which parses each value with `java.net.URI` rather
 *   than testing the raw string, so a look-alike host such as
 *   `upload.wikimedia.org.evil.example` is refused.
 * - `extmetadata.LicenseShortName` is on [ALLOWED_LICENSE_SHORT_NAME].
 * - `extmetadata.LicenseUrl`, if present, has the scheme `https` ([isAcceptableLicenseUrl]).
 *
 * ## Every failure answers null
 *
 * A non-2xx status, a body this class cannot decode, a request that never completes (including one
 * this class's own timeout ends), and a search with no acceptable candidate all answer null. A
 * caught [CancellationException] is always re-thrown, never swallowed — the same rule
 * [com.mytetz.api.FreemiusApiClient.fetchState] follows, and for the same reason: coroutine
 * cancellation is not this class's failure to report. Every other caught exception is logged by its
 * class name only — never its own message, and never the span, the built URL, or the response body,
 * any of which a vendor error page or a decode failure's message could otherwise put in a log line.
 *
 * ## Timeouts
 *
 * This class installs no timeout itself; it runs on whatever [httpClient] it is given, so its own
 * tests can run against an engine with no timeout configured at all. [com.mytetz.api.Components]
 * builds the real, production [httpClient] with a three-second connect timeout and a three-second
 * request timeout, so one slow Commons answer can never hold up the explain request that triggered
 * this lookup — the caller already treats a slow lookup exactly like a failed one.
 */
class CommonsClient(private val httpClient: HttpClient) {

    suspend fun findImage(span: String, ancestors: List<Ancestor>): ImageMedia? {
        return try {
            val response = httpClient.get(BASE_URL) {
                parameter("action", "query")
                parameter("generator", "search")
                parameter("gsrsearch", span)
                parameter("gsrnamespace", FILE_NAMESPACE)
                parameter("gsrlimit", SEARCH_LIMIT)
                parameter("prop", "imageinfo")
                parameter("iiprop", "url|mime|extmetadata")
                parameter("iiurlwidth", THUMBNAIL_WIDTH_PIXELS)
                parameter("format", "json")
                parameter("formatversion", "2")
                header(HttpHeaders.UserAgent, USER_AGENT)
            }

            if (!response.status.isSuccess()) {
                log.warn("the Wikimedia Commons lookup answered with status {}", response.status.value)
                return null
            }

            val parsed = json.decodeFromString<SearchResponse>(response.bodyAsText())
            parsed.query?.pages
                .orEmpty()
                .sortedBy { it.index ?: Int.MAX_VALUE }
                .firstNotNullOfOrNull(::imageMediaFrom)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never `e` itself, and never `e.message` — see this class's own KDoc on why. Neither
            // reaches this line, only the exception's own class name does.
            log.warn("the Wikimedia Commons lookup did not complete: {}", e.javaClass.name)
            null
        }
    }

    /** One page's own verdict: a usable [ImageMedia], or null when any acceptance check fails. See
     * this class's own KDoc, "What 'accept' means", for the full list this function checks. */
    private fun imageMediaFrom(page: PageResult): ImageMedia? {
        val info = page.imageinfo.firstOrNull() ?: return null

        if (info.mime !in ACCEPTED_ORIGINAL_MIME_TYPES) return null

        val thumbnailUrl = info.thumburl ?: return null
        if (!hasExactSchemeAndHost(thumbnailUrl, "https", "upload.wikimedia.org")) return null

        val descriptionUrl = info.descriptionurl ?: return null
        if (!hasExactSchemeAndHost(descriptionUrl, "https", "commons.wikimedia.org")) return null

        val licenseShortName = info.extmetadata?.licenseShortName?.value ?: return null
        if (!ALLOWED_LICENSE_SHORT_NAME.matches(licenseShortName.trim())) return null

        if (!isAcceptableLicenseUrl(info.extmetadata.licenseUrl?.value)) return null

        return ImageMedia(
            imageUrl = thumbnailUrl,
            title = pageTitleWithoutPrefix(page.title),
            license = licenseShortName,
            attributionHtml = attributionHtmlFor(info.extmetadata),
            commonsPageUrl = descriptionUrl,
        )
    }

    /**
     * Builds the small, self-authored HTML string [ImageMedia.attributionHtml] stores.
     *
     * `Attribution` replaces `Artist` and `Credit` when Commons sends one — the vendor's own
     * documented meaning for that field, at `Extension:CommonsMetadata`. Otherwise this joins
     * `Artist` and `Credit`, the two fields a licence's attribution clause ordinarily needs: who
     * made the work, and where it came from. [reduceAttributionHtml] then strips every tag from
     * whichever source string this picks, and [escapeHtml] escapes both the text and the one
     * link this method might add back — so the HTML this method returns is never a pass-through
     * of anything a Commons editor wrote, whatever that editor's own markup looked like.
     */
    private fun attributionHtmlFor(extmetadata: ExtMetadata?): String {
        val rawSource = extmetadata?.attribution?.value?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                extmetadata?.artist?.value?.takeIf { it.isNotBlank() },
                extmetadata?.credit?.value?.takeIf { it.isNotBlank() },
            ).joinToString(" — ")

        val reduced = reduceAttributionHtml(rawSource)
        val escapedText = escapeHtml(reduced.text)
        val link = reduced.httpsLink

        return if (link != null) {
            "$escapedText (<a href=\"${escapeHtml(link)}\">${escapeHtml(link)}</a>)"
        } else {
            escapedText
        }
    }

    companion object {
        internal const val BASE_URL: String = "https://commons.wikimedia.org/w/api.php"

        /** Confirmed at `action=help&modules=query%2Bsearch`, read 2026-09-19: "6" is the `File:`
         * namespace; the default, `0`, is the main namespace, which never holds a file. */
        private const val FILE_NAMESPACE: String = "6"

        private const val SEARCH_LIMIT: String = "5"

        /** Confirmed at `action=help&modules=query%2Bimageinfo`, read 2026-09-19: `iiurlwidth`
         * exists, and asks for "a URL to an image scaled to this width". 800 is this project's own
         * choice, not a vendor default — wide enough to read a diagram's detail, narrow enough to
         * stay well under "tens of megabytes". */
        private const val THUMBNAIL_WIDTH_PIXELS: String = "800"

        /**
         * Confirmed at `https://foundation.wikimedia.org/wiki/Policy:User-Agent_policy`, read
         * 2026-09-19 (moved there from `meta.wikimedia.org/wiki/User-Agent_policy`, which now
         * redirects): "If your client's User-Agent header ... is empty or generic ... your request
         * will fail with an HTTP 403 error." The same page's own example format is
         * `<client>/<version> (<contact information>)`, with contact information "given as an
         * email address, a website, or a wiki user". This carries the website, and no email
         * address — Task 6's own rule, and this project's own choice of contact form.
         */
        internal const val USER_AGENT: String = "mytetz/1.0 (https://mytetz.com)"
    }
}
