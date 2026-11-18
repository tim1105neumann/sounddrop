package dev.sounddrop.modem.payload

/**
 * Application content carried by a frame. The header's `type` byte selects the variant, the
 * payload bytes hold its encoding.
 */
public sealed interface Payload {
    /** Plain text. */
    public data class Text(
        val text: String,
    ) : Payload

    /** A web link the receiver can open. */
    public data class Link(
        val url: String,
    ) : Payload

    /**
     * Wi-Fi credentials, encoded in the same `WIFI:T:WPA;S:<ssid>;P:<password>;;` format that QR
     * codes use.
     */
    public data class Wifi(
        val ssid: String,
        val password: String,
        val security: Security = Security.WPA,
    ) : Payload {
        /** Network authentication type. */
        public enum class Security { WPA, WEP, NONE }
    }

    public companion object {
        /** On-air type of [payload]. */
        public fun typeOf(payload: Payload): Int =
            when (payload) {
                is Text -> TYPE_TEXT
                is Link -> TYPE_LINK
                is Wifi -> TYPE_WIFI
            }

        /** Serialises [payload]; the result may exceed the frame limit, callers must check. */
        public fun encode(payload: Payload): ByteArray =
            when (payload) {
                is Text -> payload.text.encodeToByteArray()
                is Link -> payload.url.encodeToByteArray()
                is Wifi -> WifiFormat.format(payload).encodeToByteArray()
            }

        /** Parses a received payload, or returns `null` for unknown types or malformed data. */
        public fun decode(
            type: Int,
            bytes: ByteArray,
        ): Payload? =
            when (type) {
                TYPE_TEXT -> Text(bytes.decodeToString())
                TYPE_LINK -> Link(bytes.decodeToString())
                TYPE_WIFI -> WifiFormat.parse(bytes.decodeToString())
                else -> null
            }

        private const val TYPE_TEXT = 0
        private const val TYPE_LINK = 1
        private const val TYPE_WIFI = 2
    }
}

/** The de-facto standard Wi-Fi string used by QR codes, including its backslash escaping. */
internal object WifiFormat {
    private const val SPECIAL = "\\;,:\""

    fun format(wifi: Payload.Wifi): String =
        buildString {
            append("WIFI:T:").append(wifi.security.name)
            append(";S:").append(escape(wifi.ssid))
            if (wifi.security != Payload.Wifi.Security.NONE) append(";P:").append(escape(wifi.password))
            append(";;")
        }

    fun parse(text: String): Payload.Wifi? {
        if (!text.startsWith("WIFI:")) return null
        val fields = splitFields(text.removePrefix("WIFI:"))
        val ssid = fields["S"] ?: return null
        val security =
            Payload.Wifi.Security.entries
                .firstOrNull { it.name == fields["T"] }
                ?: Payload.Wifi.Security.NONE
        return Payload.Wifi(ssid, fields["P"].orEmpty(), security)
    }

    private fun escape(value: String): String =
        buildString {
            for (c in value) {
                if (c in SPECIAL) append('\\')
                append(c)
            }
        }

    private fun splitFields(body: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val current = StringBuilder()
        var escaped = false
        for (c in body) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }

                c == '\\' -> escaped = true

                c == ';' -> {
                    val field = current.toString()
                    val colon = field.indexOf(':')
                    if (colon > 0) fields[field.substring(0, colon)] = field.substring(colon + 1)
                    current.clear()
                }

                else -> current.append(c)
            }
        }
        return fields
    }
}
