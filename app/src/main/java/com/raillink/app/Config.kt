package com.raillink.app

object Config {
    /** The page the app opens on. */
    const val HOME_URL = "https://www.raillinknetworking.com"

    /** Downloader app code for installing/updating on Fire TV and Android TV. */
    const val DOWNLOADER_CODE = ""

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
        "stripe.com",
        "stripe.network",
        "paypal.com",
    )
}
