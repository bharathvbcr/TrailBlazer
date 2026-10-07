package com.trailblazer.core.sos

/** One step of a signal timeline: light/sound [on] for [durationMs]. */
data class Pulse(val on: Boolean, val durationMs: Long)

object Morse {
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
        'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    )

    /** The code for [text] as dots and dashes, letters separated by spaces; unknown characters are dropped. */
    fun code(text: String): String =
        text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" / ") { w ->
            w.mapNotNull { table[it] }.joinToString(" ")
        }

    /**
     * Timeline with standard spacing: dot 1 unit, dash 3, gap inside a letter 1, between letters 3,
     * between words 7. SOS is sent as a single prosign (no letter gaps), then a word gap before repeating.
     */
    fun timeline(text: String, unitMs: Long = 200L, prosign: Boolean = false): List<Pulse> {
        require(unitMs > 0)
        val out = ArrayList<Pulse>()
        val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (word in words) {
            val letters = word.mapNotNull { table[it] }
            for ((li, letter) in letters.withIndex()) {
                for ((si, symbol) in letter.withIndex()) {
                    out += Pulse(true, if (symbol == '.') unitMs else 3 * unitMs)
                    if (si < letter.length - 1) out += Pulse(false, unitMs)
                }
                if (li < letters.size - 1) out += Pulse(false, if (prosign) unitMs else 3 * unitMs)
            }
            out += Pulse(false, 7 * unitMs)
        }
        return out
    }

    val SOS: List<Pulse> get() = timeline("SOS", prosign = true)

    /** International distress whistle: three long blasts, then a pause. */
    fun whistleTimeline(blastMs: Long = 1500L, gapMs: Long = 700L, pauseMs: Long = 4000L): List<Pulse> =
        listOf(
            Pulse(true, blastMs), Pulse(false, gapMs),
            Pulse(true, blastMs), Pulse(false, gapMs),
            Pulse(true, blastMs), Pulse(false, pauseMs),
        )
}
