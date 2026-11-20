package online.rushcounty.aligner.data

/**
 * Who uses a site. Markers are shown or hidden a whole category at a time.
 * Letters match the `cats` field written by `tools/build_tiles.py`; a site can carry several.
 */
enum class Category(val code: Char, val label: String) {
    PUBLIC_SAFETY('P', "Police, fire, EMS"),
    GOVERNMENT('G', "Government"),
    RAILROAD('R', "Railroad"),
    UTILITY('U', "Utilities"),
    SCHOOL('S', "Schools"),
    HOSPITAL('H', "Hospitals"),
    FARM('F', "Farm"),
    CELL('C', "Cell & towers"),
    BROADCAST('B', "Broadcast"),
    PAGING('N', "Paging"),
    MICROWAVE('M', "Microwave links"),
    BUSINESS('O', "Other business"),
    AIRPORT('A', "Airports"),
    /** Anything without FCC categories: targets typed in or imported by hand. */
    MINE('X', "My own");

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun of(t: Target): List<Category> {
            val known = t.cats.mapNotNull { byCode[it] }
            return when {
                known.isNotEmpty() -> known
                t.type == TargetType.AIRPORT -> listOf(AIRPORT)
                else -> listOf(MINE)
            }
        }

        /** A site stays on while any one of its categories is on. */
        fun shown(t: Target, hidden: String): Boolean = hidden.isEmpty() || of(t).any { it.code !in hidden }

        /** Tiles built before categories existed only carry the coarse import tags. */
        fun fromTags(tags: String): String = buildString {
            if ('P' in tags) append('P')
            if ('T' in tags) append('C')
            if ('B' in tags) append('O')
            if (isEmpty() && 'M' in tags) append('M')
        }

        /** Categories present in [targets] with how many sites each has, in menu order. */
        fun counts(targets: List<Target>): List<Pair<Category, Int>> {
            val n = IntArray(entries.size)
            targets.forEach { t -> of(t).forEach { n[it.ordinal]++ } }
            return entries.filter { n[it.ordinal] > 0 }.map { it to n[it.ordinal] }
        }
    }
}
