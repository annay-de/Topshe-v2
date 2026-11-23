package org.annay.topshe

/**
 * What the two speech models heard: Whisper (English) and the Bengali model.
 * A brain trained on both (prompt "hearing": "dual-v1") gets both lines and picks the one that
 * makes sense; older brains get the English line only.
 * forBrain() MUST match make_dataset_v4.hearing() in training.
 */
data class Hearing(val en: String, val bn: String) {
    fun forBrain(dual: Boolean): String = if (dual) "english: $en\nbangla: $bn" else en

    /** For the activity log. */
    fun shown(): String = if (bn.isBlank()) en else "$en  |  $bn"

    fun isBlank() = en.isBlank() && bn.isBlank()

    /** Drops the wake word if the recording caught it ("Topshe, ..." / "টপসে, ..."). */
    fun withoutWakeWord() = Hearing(
        en.replace(EN_WAKE, "").trim(),
        bn.replace(BN_WAKE, "").trim(),
    )

    companion object {
        private val EN_WAKE = Regex("^(hey |ok )?(top ?sh(e|ay|ey|ae|ee)|topsy|tapshe|top se)[,.!]?\\s*", RegexOption.IGNORE_CASE)
        private val BN_WAKE = Regex("^(এই |আচ্ছা )?(টপসে|তপসে|টোপসে|টপশি|টপসি|তবসি|টপ সে)[,।!]?\\s*")

        /** Text has Bengali letters (used to pick the Bengali voice). */
        fun hasBengali(s: String) = s.any { it in 'ঀ'..'৿' }
    }
}
