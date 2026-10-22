package com.dawnpatrol.game

/**
 * The logbook: what this pilot has actually done.
 *
 * `best` and `bestScore` were the only history the game kept, which meant
 * a campaign left no trace you could look at. A squadron keeps a log, and
 * a log is the cheapest way to make forty sorties feel like one story
 * rather than forty disconnected attempts.
 *
 * Medals are one-off: specific feats, awarded once, never taken away.
 * Deliberately NOT a streak and NOT a daily - the rule this project keeps
 * is that nothing is ever lost by not playing.
 *
 * Stored as two short strings so the whole log round-trips through the
 * same SharedPreferences the rest of the save uses.
 */
class Sortie(
    val score: Int, val rows: Int, val sectors: Int,
    val landed: Boolean, val cause: String
) {
    fun encode(): String =
        "$score:$rows:$sectors:${if (landed) 1 else 0}:${cause.replace('|', ' ')}"

    companion object {
        fun decode(s: String): Sortie? {
            val p = s.split(':')
            if (p.size < 5) return null
            return Sortie(
                p[0].toIntOrNull() ?: return null,
                p[1].toIntOrNull() ?: return null,
                p[2].toIntOrNull() ?: return null,
                p[3] == "1",
                p.drop(4).joinToString(":"))
        }
    }
}

object Medals {
    const val CHAIN6 = 0
    const val CLEAN_SORTIE = 1
    const val SECTOR_NO_GUNS = 2
    const val LAST_BOMB = 3
    const val LONG_HAUL = 4
    const val ACE = 5
    const val COUNT = 6

    val NAME = arrayOf(
        "SIX IN A ROW", "NOT A SCRATCH", "SILENT TAKING",
        "LAST BOMB", "LONG HAUL", "ACE")
    val HOW = arrayOf(
        "A CHAIN OF 6",
        "LAND UNDAMAGED",
        "TAKE A SECTOR WITH BOMBS ONLY",
        "KILL WITH YOUR LAST BOMB",
        "LAND 3000 ROWS IN ONE SORTIE",
        "REACH THE TOP RANK")
}

class Logbook(private val store: Store) {

    val recent = ArrayList<Sortie>()
    val medals = HashSet<Int>()

    var totalSorties = 0; private set
    var totalRows = 0; private set
    var totalSectors = 0; private set
    var totalLandings = 0; private set

    init { load() }

    private fun load() {
        for (part in store.getString("log", "").split('|')) {
            if (part.isNotEmpty()) Sortie.decode(part)?.let { recent.add(it) }
        }
        for (part in store.getString("medals", "").split(',')) {
            part.toIntOrNull()?.let { if (it in 0 until Medals.COUNT) medals.add(it) }
        }
        totalSorties = store.getFloat("tSorties", 0f).toInt()
        totalRows = store.getFloat("tRows", 0f).toInt()
        totalSectors = store.getFloat("tSectors", 0f).toInt()
        totalLandings = store.getFloat("tLandings", 0f).toInt()
    }

    fun save() {
        // capped: a log that grows forever is a save file that grows forever
        store.putString("log", recent.take(MAX).joinToString("|") { it.encode() })
        store.putString("medals", medals.joinToString(","))
        store.putFloat("tSorties", totalSorties.toFloat())
        store.putFloat("tRows", totalRows.toFloat())
        store.putFloat("tSectors", totalSectors.toFloat())
        store.putFloat("tLandings", totalLandings.toFloat())
        store.flush()
    }

    fun record(s: Sortie) {
        recent.add(0, s)
        while (recent.size > MAX) recent.removeAt(recent.size - 1)
        totalSorties++
        totalRows += s.rows
        totalSectors += s.sectors
        if (s.landed) totalLandings++
    }

    /** @return the medals awarded by THIS call, for the end card to shout. */
    fun award(vararg ids: Int): List<Int> {
        val fresh = ids.filter { it !in medals }
        medals.addAll(fresh)
        return fresh
    }

    companion object { const val MAX = 20 }
}
