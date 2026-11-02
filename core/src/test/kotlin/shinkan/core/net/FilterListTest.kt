package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import shinkan.core.net.FilterList.Type

/** Real lines of EasyList and EasyPrivacy (November 2026), each with a request it blocks and one it must not. */
class FilterListTest {
    private fun req(url: String, page: String = "example.org", type: Type = Type.SCRIPT) = FilterList.Request(url, page, type)

    private fun case(rule: String, blocked: FilterList.Request, passed: FilterList.Request) {
        val list = FilterList.parse(rule)
        assertEquals(1, list.kept, rule)
        assertTrue(list.blocks(blocked), "$rule should block ${blocked.url}")
        assertFalse(list.blocks(passed), "$rule should pass ${passed.url}")
    }

    @Test fun `host rules and the third-party option`() {
        case("||adfox.ru^\$third-party", req("https://ads.adfox.ru/x.js", "news.com"), req("https://ads.adfox.ru/x.js", "www.adfox.ru"))
        case("||g.doubleclick.net^", req("https://securepubads.g.doubleclick.net/tag/js/gpt.js"), req("https://doubleclick.net/x"))
        case("||admatic.com.tr^\$third-party", req("https://cdn.admatic.com.tr/a.js"), req("https://admatic.com.tr.example.org/a.js"))
    }

    @Test fun `type options`() {
        case("||tags.news.com.au^\$script", req("https://tags.news.com.au/prod/x.js"), req("https://tags.news.com.au/prod/x.png", type = Type.IMAGE))
        case("/adslide.js\$script", req("https://cdn.example.com/js/adslide.js"), req("https://cdn.example.com/js/adslide.js", type = Type.IMAGE))
        case("/detroitchicago/vpp.gif?\$image", req("https://e.com/detroitchicago/vpp.gif?x=1", type = Type.IMAGE), req("https://e.com/detroitchicago/vpp.gif", type = Type.IMAGE))
        case("/internAds.css\$stylesheet", req("https://e.com/css/internAds.css", type = Type.STYLESHEET), req("https://e.com/css/internAds.css", type = Type.FONT))
        case("||presearch.com/coupons|\$xmlhttprequest", req("https://presearch.com/coupons", type = Type.XMLHTTPREQUEST), req("https://presearch.com/coupons/all", type = Type.XMLHTTPREQUEST))
        case(
            "||trickyrock.com/redirect.aspx?pid=*&bid=\$subdocument,third-party",
            req("https://trickyrock.com/redirect.aspx?pid=1&x=2&bid=3", type = Type.SUBDOCUMENT),
            req("https://trickyrock.com/redirect.aspx?pid=1&x=2&bid=3", type = Type.IMAGE),
        )
    }

    @Test fun `path and substring rules`() {
        case("||api.vaile.ai/api/track", req("https://api.vaile.ai/api/track?id=1"), req("https://api.vaile.ai/api/other"))
        case("||steamchina.com/events/", req("https://store.steamchina.com/events/x"), req("https://steamchina.com.evil.org/events/"))
        case("/fingerprint.min.js", req("https://e.com/static/fingerprint.min.js"), req("https://e.com/static/fingerprint.js"))
        case("-adobe-analytics/", req("https://e.com/lib-adobe-analytics/x.js"), req("https://e.com/adobe-analytics/x.js"))
        case("-logging.nextmedia.com^", req("https://api-logging.nextmedia.com/x"), req("https://api-logging.nextmedia.community/x"))
        case("||veeseo.com^*/url.gif?", req("https://cdn.veeseo.com/a/b/url.gif?u=1", type = Type.IMAGE), req("https://cdn.veeseo.com/a/b/url.gif", type = Type.IMAGE))
        case("^endpoint=track^\$image", req("https://e.com/collect?endpoint=track&x=1", type = Type.IMAGE), req("https://e.com/collect?endpoint=tracker", type = Type.IMAGE))
    }

    @Test fun `anchors at the start and the end`() {
        case("|https://a.pr-cy.ru^\$third-party", req("https://a.pr-cy.ru/counter.js"), req("http://a.pr-cy.ru/counter.js"))
        case("/pingback|", req("https://e.com/wp/pingback"), req("https://e.com/wp/pingback/x"))
    }

    @Test fun `domain options`() {
        case("||bit.ly^\$script,domain=dailyuploads.net|freeshot.live", req("https://bit.ly/abc", "www.dailyuploads.net"), req("https://bit.ly/abc", "example.org"))
        case("||dealhunt.com^\$domain=androidauthority.com", req("https://www.dealhunt.com/w.js", "www.androidauthority.com"), req("https://www.dealhunt.com/w.js", "dealhunt.com"))
        case(
            "\$third-party,xmlhttprequest,domain=mega4upload.net",
            req("https://cdn.tracker.example/x", "mega4upload.net", Type.XMLHTTPREQUEST),
            req("https://api.mega4upload.net/x", "mega4upload.net", Type.XMLHTTPREQUEST),
        )
        case("/ad/img/*\$image,domain=~eki-net.com|~jiji.com", req("https://e.com/ad/img/1.png", "news.org", Type.IMAGE), req("https://e.com/ad/img/1.png", "www.jiji.com", Type.IMAGE))
        case("||e.com^\$domain=google.*", req("https://e.com/x", "www.google.co.uk"), req("https://e.com/x", "google.example.co.uk.evil.org"))
    }

    @Test fun `an exception beats a block`() {
        val adnz = FilterList.parse("||adnz.co/header.js\n@@||adnz.co/header.js?adTagId=\$domain=finanzen.ch")
        assertTrue(adnz.blocks(req("https://adnz.co/header.js?adTagId=4", "www.cash.ch")))
        assertFalse(adnz.blocks(req("https://adnz.co/header.js?adTagId=4", "www.finanzen.ch")))
        val omtrdc = FilterList.parse("||omtrdc.net^\n@@||omtrdc.net^*/mbox/json?\$xmlhttprequest,domain=absa.co.za|att.com")
        assertTrue(omtrdc.blocks(req("https://att.tt.omtrdc.net/rest/v1/delivery", "www.att.com", Type.XMLHTTPREQUEST)))
        assertFalse(omtrdc.blocks(req("https://att.tt.omtrdc.net/m2/att/mbox/json?mbox=x", "www.att.com", Type.XMLHTTPREQUEST)))
        assertTrue(omtrdc.blocks(req("https://att.tt.omtrdc.net/m2/att/mbox/json?mbox=x", "www.att.com", Type.IMAGE)))
        val eki = FilterList.parse("/ad/img/*\$image,domain=~eki-net.com|~jiji.com\n@@||ienohikari.net/ad/img/\$~third-party")
        assertFalse(eki.blocks(req("https://www.ienohikari.net/ad/img/b.png", "www.ienohikari.net", Type.IMAGE)))
        assertTrue(eki.blocks(req("https://www.ienohikari.net/ad/img/b.png", "news.org", Type.IMAGE)))
    }

    @Test fun `cosmetic, regex and unknown options are dropped and counted`() {
        val list = FilterList.parse(
            """
            [Adblock Plus 2.0]
            ! a comment
            example.com##.ad-banner
            example.com#@#.sponsor
            /^https?:\/\/.*\.(club|xyz|top|casa)\//${'$'}popup,domain=dood.la
            ||doubleclick.net^${'$'}popup
            ||example.com^${'$'}csp=script-src 'none'
            ||example.com^${'$'}removeparam=utm_source
            ||g.doubleclick.net^
            """.trimIndent(),
        )
        assertEquals(1, list.kept)
        assertEquals(6, list.dropped)
        assertFalse(list.blocks(req("https://doubleclick.net/popup")))
    }

    @Test fun `the type of a request as a WebView tells it`() {
        assertEquals(null, FilterList.typeOf("https://e.com/", "text/html", mainFrame = true))
        assertEquals(Type.SUBDOCUMENT, FilterList.typeOf("https://e.com/frame", "text/html,application/xhtml+xml", false))
        assertEquals(Type.STYLESHEET, FilterList.typeOf("https://e.com/a", "text/css,*/*;q=0.1", false))
        assertEquals(Type.IMAGE, FilterList.typeOf("https://e.com/a", "image/avif,image/webp,*/*", false))
        assertEquals(Type.SCRIPT, FilterList.typeOf("https://e.com/a.js?v=2", "*/*", false))
        assertEquals(Type.FONT, FilterList.typeOf("https://e.com/f.woff2", "*/*", false))
        assertEquals(Type.MEDIA, FilterList.typeOf("https://e.com/v.mp4#t=1", null, false))
        assertEquals(Type.UNKNOWN, FilterList.typeOf("https://e.com/api/data", "*/*", false))
        // A request of unknown type is matched by a rule for scripts, not by one for images.
        val list = FilterList.parse("/adslide.js\$script\n/banner/\$image")
        assertTrue(list.blocks(req("https://e.com/adslide.js", type = Type.UNKNOWN)))
        assertFalse(list.blocks(req("https://e.com/banner/1", type = Type.UNKNOWN)))
    }

    @Test fun `first and third party by site`() {
        assertEquals("example.co.uk", Host.base("www.example.co.uk"))
        assertEquals("example.org", Host.base("a.b.example.org"))
        assertFalse(req("https://cdn.example.org/x", "www.example.org").thirdParty)
        assertTrue(req("https://cdn.other.net/x", "www.example.org").thirdParty)
    }
}

/** The lists as shipped: they parse, the index finds what a plain scan of the rules would. */
class ShippedFilterListsTest {
    private val dir = java.io.File("../app/src/main/assets/filters")

    @Test fun `easylist and easyprivacy load and block the ad script of the browser check`() {
        val lists = FilterList.parse(
            java.io.File(dir, "easylist.txt").readLines().asSequence() + java.io.File(dir, "easyprivacy.txt").readLines().asSequence(),
        )
        assertTrue(lists.kept > 50_000, "kept ${lists.kept}")
        assertTrue(lists.blocks(FilterList.Request("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "example.org", Type.SCRIPT)))
        assertFalse(lists.blocks(FilterList.Request("https://example.org/style.css", "example.org", Type.STYLESHEET)))
        assertFalse(lists.blocks(FilterList.Request("https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "en.wikipedia.org", Type.IMAGE)))
    }
}
