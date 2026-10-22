package com.dawnpatrol.game

/**
 * The pilots who have flown these aeroplanes, and the ones who did not
 * come back.
 *
 * READ THE WARNING IN BACKLOG.md BEFORE CHANGING THIS. The argument for a
 * squadron roster is "losing a pilot on their eleventh sortie hurts, and
 * hurt is what brings people back" - and that is one word away from the
 * punishment pattern this project refuses. So it is built as a STORY
 * LAYER AND NEVER A STAKE:
 *
 *   - nothing earned is lost with a pilot. Rank, the front, orders,
 *     medals and the logbook all belong to the squadron, not the man.
 *   - the next pilot is already named and waiting, one tap away.
 *   - the memorial is something you can look at, never something that
 *     nags you or counts down.
 *
 * A pilot's name and their sortie count are the whole of it. That is
 * enough to make the eleventh sortie feel different from the first
 * without making the twelfth a punishment.
 */
class Pilot(val name: String, var sorties: Int = 0) {
    fun encode() = "$name:$sorties"
    companion object {
        fun decode(s: String): Pilot? {
            val i = s.lastIndexOf(':')
            if (i <= 0) return null
            return Pilot(s.substring(0, i), s.substring(i + 1).toIntOrNull() ?: 0)
        }
    }
}

object Names {
    private val FIRST = arrayOf(
        "ALBERT", "CECIL", "DUNCAN", "EDWIN", "FRANK", "GEORGE", "HAROLD",
        "IVOR", "JAMES", "KEITH", "LIONEL", "MORRIS", "NIGEL", "OLIVER",
        "PERCY", "RUPERT", "STANLEY", "THOMAS", "VICTOR", "WALTER")
    private val LAST = arrayOf(
        "ASHWORTH", "BRAMLEY", "CARLYLE", "DENHOLM", "EASTWOOD", "FAIRWEATHER",
        "GRANTHAM", "HOLLIS", "INGRAM", "JARVIS", "KENDRICK", "LOWRY",
        "MERRICK", "NORBURY", "PRESCOTT", "QUILTER", "RADCLIFFE", "SHELDRAKE",
        "THORNBURY", "WYNDHAM")

    fun pick(seed: Int): String {
        val h = World.hash32(seed * 7919 + 4421)
        val a = FIRST[(h % FIRST.size.toUInt()).toInt()]
        val b = LAST[((h shr 9) % LAST.size.toUInt()).toInt()]
        return a.substring(0, 1) + " " + b
    }
}

class Squadron(private val store: Store) {

    var current: Pilot = Pilot(Names.pick(0))
    val fallen = ArrayList<Pilot>()
    var lost = 0; private set

    init { load() }

    private fun load() {
        val c = store.getString("pilot", "")
        Pilot.decode(c)?.let { current = it }
        for (p in store.getString("fallen", "").split('|')) {
            if (p.isNotEmpty()) Pilot.decode(p)?.let { fallen.add(it) }
        }
        lost = store.getFloat("lost", 0f).toInt()
    }

    fun save() {
        store.putString("pilot", current.encode())
        store.putString("fallen", fallen.take(MAX).joinToString("|") { it.encode() })
        store.putFloat("lost", lost.toFloat())
        store.flush()
    }

    /**
     * The pilot did not come back. Nothing they earned goes with them -
     * the squadron keeps the rank, the ground, the orders and the medals.
     * A new pilot is already on the strength.
     */
    fun standDown() {
        fallen.add(0, current)
        while (fallen.size > MAX) fallen.removeAt(fallen.size - 1)
        lost++
        current = Pilot(Names.pick(lost * 31 + current.sorties))
        save()
    }

    fun flewOne() { current.sorties++ }

    companion object { const val MAX = 12 }
}


/**
 * The enemy ace. One of him, remembered, with your tally on his fuselage.
 *
 * Kill him and a new ace inherits the grudge and the tally resets - the
 * squadron opposite has more than one good pilot, and so does yours.
 */
class Rival(private val store: Store) {
    var name: String = ""
    var tally = 0          // YOUR machines he has downed
    var kills = 0          // how many of him you have downed
    private var seed = 0

    init {
        name = store.getString("rivalName", "")
        tally = store.getFloat("rivalTally", 0f).toInt()
        kills = store.getFloat("rivalKills", 0f).toInt()
        seed = store.getFloat("rivalSeed", 1f).toInt()
        if (name.isEmpty()) { name = pickName(); save() }
    }

    private fun pickName(): String {
        val h = World.hash32(seed * 1597 + 83)
        val gothic = arrayOf("VON RICHTER", "VON HAUSEN", "KESSLER", "BRANDT",
            "VON STROHM", "HOFFMANN", "WERNER", "VON DRACH", "KELLER", "REIMER")
        return gothic[(h % gothic.size.toUInt()).toInt()]
    }

    fun save() {
        store.putString("rivalName", name)
        store.putFloat("rivalTally", tally.toFloat())
        store.putFloat("rivalKills", kills.toFloat())
        store.putFloat("rivalSeed", seed.toFloat())
        store.flush()
    }

    /** He got you. */
    fun scored() { tally++; save() }

    /** You got him. A new man takes the grudge and the tally resets. */
    fun downed() {
        kills++
        seed++
        name = pickName()
        tally = 0
        save()
    }
}
