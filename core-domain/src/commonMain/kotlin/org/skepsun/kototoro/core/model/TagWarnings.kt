package org.skepsun.kototoro.core.model

/**
 * Android's warn-list (`res/raw/tags_warnlist`): titles that describe extreme content a reader may not expect.
 * The list is shared so every platform tints the same chips; whether the tint is drawn stays a user setting.
 */
object TagWarnings {

    /** Warn titles as stored (already lowercase); comparison is exact, like Android's `title.lowercase() in dict`. */
    val titles: Set<String> by lazy {
        TITLE_DATA.lineSequence().map(String::trim).filter(String::isNotEmpty).toHashSet()
    }

    fun isWarned(title: String): Boolean = title.trim().lowercase() in titles

    // Android reads this list from a raw resource; the shared copy keeps both platforms on one set of titles.
    private const val TITLE_DATA = """
        amputation
        amputee
        anal birth
        anal torture
        bdsm
        beast
        beastiality
        bestiality
        birth
        blackmail
        blood
        body horror
        bondage
        boys' love
        brother
        bukkake
        cannibalism
        cbt
        choking
        coprophagia
        degradation
        diapers
        drugs
        egg laying
        electrical play
        electro
        electro play
        enema
        extreme
        father
        femdom
        force
        full censorship
        furry
        futanari
        gang rape
        gangbang
        gangbang rape
        gender bender
        girls' love
        guro
        gore
        human pet
        humiliation
        hypno
        incest
        inflation
        insect
        inseki
        knife play
        loli
        lolicon
        machine
        mind break
        mindbreak
        molestation
        mosaic
        mother
        mutilation
        necrophila
        necrophilia
        netorase
        netorare
        nipple torture
        non-consensual
        ntr
        orgasm denial
        parasite
        piercing
        prolapse
        prostitution
        public use
        puke
        puppy play
        rape
        ryona
        scar
        scat
        sexual violence
        shemale
        shota
        shotacon
        sister
        slave
        slavery
        snuff
        tentacles
        toddlercon
        torture
        trans
        transgender
        trap
        traps
        unbirth
        urination
        vaginal birth
        violent
        vomit
        vore
        watersports
        yaoi
        yuri
        гуро
        инцест
        копро
        тентакли
        трап
        футанари
        юри
        яой
    """
}