package org.skepsun.kototoro.core.util.ext

import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.TestContentSource
import java.io.IOException

class CloudFlareExceptionCauseTest {

    @Test
    fun `finds protected exception wrapped by io exception`() {
        val cloudFlare = CloudFlareProtectedException(
            url = "https://example.org/",
            source = TestContentSource,
            headers = Headers.headersOf(),
        )

        assertSame(cloudFlare, IOException("request failed", cloudFlare).findCloudFlareException())
    }
}
