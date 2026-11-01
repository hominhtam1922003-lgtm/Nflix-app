package com.example.nflix.data

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * V2 (0.1): REAL ad blocking for the embed WebView player, wired to the
 * "Chặn quảng cáo trong trang phát nhúng" setting. Intercepts requests
 * whose host is a known ad / gambling domain — the class of popup the
 * user actually met (UK88 / BOM88 betting popups on a dead source is
 * what killed that source) — plus the big generic ad networks that
 * embed pages pull in.
 *
 * Honesty rule: this blocks KNOWN domains only. The settings copy says
 * exactly that ("Không cam kết chặn 100%") — a domain not on this list
 * still loads, and no UI anywhere claims otherwise.
 */
object AdBlock {

    /**
     * Exact hosts and host suffixes. Suffix entries start with '.'
     * (".doubleclick.net" also catches "ads.doubleclick.net").
     * The betting entries are the popup operators observed live on the
     * sources this app embeds, plus the VN-facing betting brands whose
 * popups those pages rotate through.
     */
    private val BLOCKED = listOf(
        // Betting / gambling popup operators (observed + their brand kin)
        "uk88.com", ".uk88.com", "bom88.com", ".bom88.com",
        ".188bet.com", ".fun88.com", ".w88.com", "w88.com",
        ".vn88.com", ".vn88ai.com", ".fb88.com", ".fb88ai.com",
        ".kubet.com", ".kubet.ai", ".ku11.com",
        ".thabet.com", ".mibet.com", ".sin88.com", ".go88.com",
        ".sun.win", ".win79.com", ".rik.vip", ".b52.club",
        ".hit.club", ".68gamebai.club", ".iwin.com",
        ".shbet.com", ".shbet.ai", ".hb88.com", ".hb88ai.com",
        ".okvip.com", ".okvip.ai", ".mb66.com", ".mb66.ai",
        ".hi88.com", ".hi88ai.com", ".f8bet.com", ".f8bet.ai",
        ".789bet.com", ".789bet.ai", ".sv368.com", ".ee88.com",
        ".ae88.com", ".debet.com", ".debet.ai", ".lode88.com",
        ".xoso88.com", ".soicau88.com", ".new88.com", ".new88.ai",
        ".jun88.com", ".jun88.ai", ".hello88.com", ".hello88.ai",
        ".may88.com", ".may88.ai", ".zbet.com", ".five88.com",
        ".five88.ai", ".win55.com", ".leo88.com", ".rikvip.com",
        ".tf88.com", ".tf88.ai", ".sv88.com", ".7m.com",
        // Generic ad / tracking networks embed pages commonly load
        ".doubleclick.net", ".googlesyndication.com", ".googleadservices.com",
        ".googletagservices.com", ".adnxs.com", ".adsrvr.org",
        ".criteo.com", ".criteo.net", ".rubiconproject.com",
        ".pubmatic.com", ".openx.net", ".casalemedia.com",
        ".amazon-adsystem.com", ".moatads.com", ".scorecardresearch.com",
        ".popads.net", ".popcash.net", ".propellerads.com",
        ".adcash.com", ".exoclick.com", ".juicyads.com",
        ".trafficjunky.com", ".adsterra.com", ".hilltopads.net",
        ".clickadu.com", ".evadav.com", ".adcash.com",
        ".mgid.com", ".revcontent.com", ".outbrain.com",
        ".taboola.com", ".smartadserver.com", ".yieldlab.net",
        ".adform.net", ".flurry.com", ".inmobi.com",
        ".smaato.net", ".startapp.com", ".unityads.unity3d.com",
        ".applovin.com", ".vungle.com", ".chartboost.com",
        ".ironsrc.com", ".supersonicads.com", ".bidvertiser.com",
        ".clickaine.com", ".zeropark.com", ".redirectvoluum.com",
        ".voluumtrk.com", ".bemob.com", ".propellerclicks.com",
        ".onclickgenius.com", ".onclickmax.com", ".acweb.online",
        ".profitablecreativeformat.com", ".effectivemeasure.net",
        ".highperformanceformat.com", ".invoke.js"
    )

    fun isBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            android.net.Uri.parse(url).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return BLOCKED.any { entry ->
            if (entry.startsWith(".")) {
                host == entry.substring(1) || host.endsWith(entry)
            } else {
                host == entry
            }
        }
    }

    /** Empty 200 response — the WebView treats the resource as loaded-but-blank. */
    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val url = try {
            request?.url?.toString()
        } catch (e: Exception) {
            null
        }
        return if (isBlocked(url)) emptyResponse() else null
    }
}
