package org.skepsun.kototoro.video.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Controls a DLNA device via SOAP (UPnP AVTransport).
 */
object DlnaController {

    private const val TAG = "DlnaController"
    private val SOAP_CONTENT_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    private const val AV_TRANSPORT_URN = "urn:schemas-upnp-org:service:AVTransport:1"

    suspend fun setAVTransportURI(
        client: OkHttpClient,
        device: DlnaDevice,
        mediaUrl: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val escapedUrl = mediaUrl
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="$AV_TRANSPORT_URN">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>$escapedUrl</CurrentURI>
                        <CurrentURIMetaData></CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        sendSoapAction(client, device.controlUrl, "SetAVTransportURI", body)
    }

    suspend fun play(client: OkHttpClient, device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Play xmlns:u="$AV_TRANSPORT_URN">
                            <InstanceID>0</InstanceID>
                            <Speed>1</Speed>
                        </u:Play>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            sendSoapAction(client, device.controlUrl, "Play", body)
        }

    suspend fun pause(client: OkHttpClient, device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Pause xmlns:u="$AV_TRANSPORT_URN">
                            <InstanceID>0</InstanceID>
                        </u:Pause>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            sendSoapAction(client, device.controlUrl, "Pause", body)
        }

    suspend fun stop(client: OkHttpClient, device: DlnaDevice): Boolean =
        withContext(Dispatchers.IO) {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Stop xmlns:u="$AV_TRANSPORT_URN">
                            <InstanceID>0</InstanceID>
                        </u:Stop>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            sendSoapAction(client, device.controlUrl, "Stop", body)
        }

    /**
     * Seeks to the given position on the DLNA device.
     * @param positionMs position in milliseconds
     */
    suspend fun seek(client: OkHttpClient, device: DlnaDevice, positionMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val totalSec = (positionMs / 1000).coerceAtLeast(0)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            val target = "%d:%02d:%02d".format(h, m, s)
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:Seek xmlns:u="$AV_TRANSPORT_URN">
                            <InstanceID>0</InstanceID>
                            <Unit>REL_TIME</Unit>
                            <Target>$target</Target>
                        </u:Seek>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()
            Log.d(TAG, "Seeking to $target ($positionMs ms)")
            sendSoapAction(client, device.controlUrl, "Seek", body)
        }

    private fun sendSoapAction(
        client: OkHttpClient,
        controlUrl: String,
        action: String,
        soapBody: String,
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(controlUrl)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"$AV_TRANSPORT_URN#$action\"")
                .post(soapBody.toRequestBody(SOAP_CONTENT_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                Log.w(TAG, "SOAP $action failed: ${response.code}")
            } else {
                Log.d(TAG, "SOAP $action succeeded on $controlUrl")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "SOAP $action error: ${e.javaClass.simpleName}")
            false
        }
    }
}
