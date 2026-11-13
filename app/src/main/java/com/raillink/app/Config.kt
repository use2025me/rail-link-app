package com.raillink.app

object Config {
    /** The page the app opens on. */
    const val HOME_URL = "https://www.raillinknetworking.com"

    /** The page the Junction+ tab opens on. */
    const val JP_URL = "https://stream.junctionplus.com"

    /** Downloader app code for installing/updating on Fire TV and Android TV. */
    const val DOWNLOADER_CODE = "9947611"

    /**
     * Websites that open inside the app. Anything else (e.g. LinkedIn, WhatsApp)
     * opens in the phone's own app or browser. Sub-domains are included automatically.
     */
    val INTERNAL_HOSTS = listOf(
        "raillinknetworking.com",
        "lookoutawards.com",
        "evessio.com",
        "junctionplus.com",
        "uscreen.io",
        "uscreen.tv",
        "devilmediagroup.com",
        "devilmediagroup.workers.dev",
        "stripe.com",
        "stripe.network",
        "paypal.com",
    )

    // Our streaming site: taps on the YouTube logo here stay in the app
    val STREAM_HOSTS = listOf("junctionplus.com", "devilmediagroup.com", "devilmediagroup.workers.dev")
}
