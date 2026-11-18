package dev.sounddrop.modem.payload

/**
 * Application content carried by a frame. The header's `type` byte selects the variant, the
 * payload bytes hold its encoding.
 */
public sealed interface Payload {
    /** Plain text. */
    public data class Text(
        /** The message. */
        val text: String,
    ) : Payload

    /** A web link the receiver can open. */
    public data class Link(
        /** The address, normally `https://…`. */
        val url: String,
    ) : Payload

    /**
     * Wi-Fi credentials, encoded in the same `WIFI:T:WPA;S:<ssid>;P:<password>;;` format that QR
     * codes use.
     */
    public data class Wifi(
        /** Network name. */
        val ssid: String,
        /** Network password; empty for open networks. */
        val password: String,
        /** Authentication type. */
        val security: Security = Security.WPA,
    ) : Payload {
        /** Network authentication type. */
        public enum class Security { WPA, WEP, NONE }
    }

    /** A small line drawing on a [SketchCodec.GRID] × [SketchCodec.GRID] grid. */
    public data class Sketch(
        /** Strokes in drawing order, each a list of grid points. */
        val strokes: List<List<SketchPoint>>,
    ) : Payload

    public companion object {
        internal const val TYPE_TEXT = 0
        internal const val TYPE_LINK = 1
        internal const val TYPE_WIFI = 2
        internal const val TYPE_SKETCH = 3

        /** Parses a received payload, or returns `null` for unknown types or malformed data. */
        public fun decode(
            type: Int,
            bytes: ByteArray,
        ): Payload? =
            when (type) {
                TYPE_TEXT -> Text(bytes.decodeToString())
                TYPE_LINK -> Link(bytes.decodeToString())
                TYPE_WIFI -> WifiFormat.parse(bytes.decodeToString())
                TYPE_SKETCH -> SketchCodec.decode(bytes)?.let(::Sketch)
                else -> null
            }
    }
}

/** On-air type byte of this payload, stored in the frame header. */
public val Payload.type: Int
    get() =
        when (this) {
            is Payload.Text -> Payload.TYPE_TEXT
            is Payload.Link -> Payload.TYPE_LINK
            is Payload.Wifi -> Payload.TYPE_WIFI
            is Payload.Sketch -> Payload.TYPE_SKETCH
        }

/** Serialises this payload. The result may exceed the frame limit; callers must check. */
public fun Payload.encode(): ByteArray =
    when (this) {
        is Payload.Text -> text.encodeToByteArray()
        is Payload.Link -> url.encodeToByteArray()
        is Payload.Wifi -> WifiFormat.format(this).encodeToByteArray()
        is Payload.Sketch -> SketchCodec.encode(strokes)
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
