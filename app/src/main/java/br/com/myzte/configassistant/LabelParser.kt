package br.com.myzte.configassistant

data class WifiCredentials(
    val ssid: String?,
    val password: String?
)

object LabelParser {

    private val ssidLabels = listOf(
        "SSID", "WIFI NAME", "WI-FI NAME", "WIRELESS NAME",
        "NOME DA REDE", "REDE WI-FI"
    )

    private val passwordLabels = listOf(
        "WIFI PASSWORD", "WI-FI PASSWORD", "WIRELESS PASSWORD",
        "WIFI KEY", "WI-FI KEY", "WLAN KEY", "WPA KEY",
        "PASSWORD", "PASS", "SENHA", "CHAVE WI-FI"
    )

    fun parse(rawText: String): WifiCredentials {
        val lines = rawText.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

        val ssid = findValue(lines, ssidLabels)
        val password = findValue(lines, passwordLabels)

        return WifiCredentials(
            ssid = ssid?.takeIf { it.length in 1..64 },
            password = password?.takeIf { it.length in 8..63 }
        )
    }

    private fun findValue(lines: List<String>, labels: List<String>): String? {
        for (index in lines.indices) {
            val line = lines[index]
            val upper = line.uppercase()

            for (label in labels.sortedByDescending { it.length }) {
                if (!upper.contains(label)) continue

                val afterLabel = line.substringAfter(label, "")
                    .trim()
                    .trim(':', '=', '-', ' ', '\t')

                if (afterLabel.isNotBlank() && !looksLikeAnotherLabel(afterLabel)) {
                    return clean(afterLabel)
                }

                val next = lines.getOrNull(index + 1)
                if (next != null && !looksLikeAnotherLabel(next)) {
                    return clean(next)
                }
            }
        }
        return null
    }

    private fun looksLikeAnotherLabel(value: String): Boolean {
        val upper = value.uppercase()
        return (ssidLabels + passwordLabels).any { upper.startsWith(it) }
    }

    private fun clean(value: String): String =
        value.trim().trim('"', '\'', ':', '=', ';', ',', '.', ' ')
}
