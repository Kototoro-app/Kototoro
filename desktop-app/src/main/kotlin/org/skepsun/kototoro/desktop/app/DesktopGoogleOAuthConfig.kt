package org.skepsun.kototoro.desktop.app

import java.util.Properties

/** Build-owned desktop client configuration, independent of each user's Keychain/credential-manager tokens. */
internal object DesktopGoogleOAuthConfig {
    val clientSecret: String by lazy {
        val properties = Properties()
        DesktopGoogleOAuthConfig::class.java.getResourceAsStream("/google-drive-client.properties")?.use {
            properties.load(it)
        }
        properties.getProperty("client_secret", "").trim()
    }
}
