package org.skepsun.kototoro.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import androidx.preference.PreferenceManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy

class AppSettingsExtensionInstallPoliciesTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val storedPreferences = mutableMapOf<String, Any?>()

    @BeforeEach
    fun setUp() {
        mockkStatic(PreferenceManager::class)
        every { PreferenceManager.getDefaultSharedPreferences(context) } returns preferences
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>()
        every { context.resources } returns mockk<Resources> {
            every { getStringArray(any()) } returns emptyArray()
        }
        every { preferences.contains(any()) } returns false
        every { preferences.getBoolean(any(), any()) } answers { secondArg() }
        every { preferences.getInt(any(), any()) } answers { secondArg() }
        every { preferences.getLong(any(), any()) } answers { secondArg() }
        every { preferences.getFloat(any(), any()) } answers { secondArg() }
        every { preferences.getString(any(), any()) } answers { secondArg() }
        every { preferences.getStringSet(any(), any()) } answers {
            val key = firstArg<String>()
            val defaultVal = secondArg<Set<String>?>()
            @Suppress("UNCHECKED_CAST")
            (storedPreferences[key] as? Set<String>) ?: defaultVal
        }
        every { preferences.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers {
            val key = firstArg<String>()
            val value = secondArg<Set<String>?>()
            if (value != null) {
                storedPreferences[key] = value
            } else {
                storedPreferences.remove(key)
            }
            editor
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(PreferenceManager::class)
        storedPreferences.clear()
    }

    @Test
    fun `default install policy is ASK_EVERY_TIME`() {
        val settings = AppSettings(context)
        settings.getExtensionInstallPolicy("LEGADO") shouldBe ExtensionInstallPolicy.ASK_EVERY_TIME
    }

    @Test
    fun `setExtensionInstallPolicy updates individual policy`() {
        val settings = AppSettings(context)
        settings.setExtensionInstallPolicy("LEGADO", ExtensionInstallPolicy.INSTALL_AND_ENABLE)

        settings.getExtensionInstallPolicy("LEGADO") shouldBe ExtensionInstallPolicy.INSTALL_AND_ENABLE
        settings.getExtensionInstallPolicy("MIHON") shouldBe ExtensionInstallPolicy.ASK_EVERY_TIME

        // Setting back to ASK_EVERY_TIME removes override
        settings.setExtensionInstallPolicy("LEGADO", ExtensionInstallPolicy.ASK_EVERY_TIME)
        settings.getExtensionInstallPolicy("LEGADO") shouldBe ExtensionInstallPolicy.ASK_EVERY_TIME
    }

    @Test
    fun `setAllExtensionInstallPolicies applies policy to all specified types`() {
        val settings = AppSettings(context)
        val types = listOf("LEGADO", "MIHON", "CLOUDSTREAM")

        settings.setAllExtensionInstallPolicies(types, ExtensionInstallPolicy.INSTALL_AND_ENABLE)

        types.forEach { type ->
            settings.getExtensionInstallPolicy(type) shouldBe ExtensionInstallPolicy.INSTALL_AND_ENABLE
        }

        // Setting all to ASK_EVERY_TIME clears overrides
        settings.setAllExtensionInstallPolicies(types, ExtensionInstallPolicy.ASK_EVERY_TIME)

        types.forEach { type ->
            settings.getExtensionInstallPolicy(type) shouldBe ExtensionInstallPolicy.ASK_EVERY_TIME
        }
    }
}
