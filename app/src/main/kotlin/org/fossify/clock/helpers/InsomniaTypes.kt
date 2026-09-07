package org.fossify.clock.helpers

/**
 * Insomnia-type taxonomy for the curated picks: content is tagged with the
 * kind of sleeper it helps, so recommendations can be filtered per type
 * (anxious / excited / physiological / noise-disturbed). Stored as raw
 * string keys so Gson round-trips stay stable across renames.
 */
object InsomniaTypes {

    const val KEY_ALL = "all"
    const val KEY_ANXIETY = "anxiety"
    const val KEY_EXCITEMENT = "excitement"
    const val KEY_PHYSICAL = "physical"
    const val KEY_NOISE = "noise"

    data class TypeDef(val key: String, val labelRes: Int, val descRes: Int)

    val types: List<TypeDef> = listOf(
        TypeDef(
            KEY_ANXIETY,
            org.fossify.clock.R.string.insomnia_anxiety,
            org.fossify.clock.R.string.insomnia_anxiety_desc
        ),
        TypeDef(
            KEY_EXCITEMENT,
            org.fossify.clock.R.string.insomnia_excitement,
            org.fossify.clock.R.string.insomnia_excitement_desc
        ),
        TypeDef(
            KEY_PHYSICAL,
            org.fossify.clock.R.string.insomnia_physical,
            org.fossify.clock.R.string.insomnia_physical_desc
        ),
        TypeDef(
            KEY_NOISE,
            org.fossify.clock.R.string.insomnia_noise,
            org.fossify.clock.R.string.insomnia_noise_desc
        )
    )

    fun labelKey(context: android.content.Context, key: String?): String {
        if (key == null || key == KEY_ALL) return ""
        return types.firstOrNull { it.key == key }
            ?.let { context.getString(it.labelRes) } ?: key
    }
}
