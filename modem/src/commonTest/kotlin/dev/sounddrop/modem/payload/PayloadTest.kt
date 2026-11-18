package dev.sounddrop.modem.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadTest {
    @Test
    fun everyVariantRoundTrips() {
        val payloads =
            listOf(
                Payload.Text("Hallo Kraków 👋"),
                Payload.Link("https://kotlinlang.org/docs/multiplatform.html"),
                Payload.Wifi("Café; \"Guest\"", "p@ss:w\\rd,1"),
                Payload.Wifi("OpenNet", "", Payload.Wifi.Security.NONE),
            )
        for (payload in payloads) {
            val decoded = Payload.decode(Payload.typeOf(payload), Payload.encode(payload))
            assertEquals(payload, decoded)
        }
    }

    @Test
    fun wifiUsesTheQrCodeFormat() {
        assertEquals(
            "WIFI:T:WPA;S:Home;P:secret;;",
            Payload.encode(Payload.Wifi("Home", "secret")).decodeToString(),
        )
    }

    @Test
    fun unknownTypeIsRejected() {
        assertNull(Payload.decode(200, byteArrayOf(1, 2, 3)))
    }
}
