package at.greenchili.panoptes.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartLayoutTest {
    private val std = Raster.standard(null)

    private fun keineUeberlappung(l: StartLayout) = l.seiten.forEach { s ->
        s.forEach { e -> assertTrue("$e passt", Raster.passt(s, e.x, e.y, e.b, e.h, ohne = e.id)) }
    }

    @Test fun standardFuerAlleStartansichten() {
        for (a in listOf(null, "panoptes", "fahren", "fahrzeug", "kompakt", "cockpit")) {
            val l = Raster.standard(a)
            keineUeberlappung(l)
            assertEquals(Raster.PANOPTES, l.dock.first())
            assertEquals(1, l.seiten[0].count { it.kachel == Kachel.FAHRZEUG })
            val ks = l.seiten.flatten().mapNotNull { it.kachel }
            assertEquals("jede Kachel höchstens einmal ($a)", ks.size, ks.toSet().size)
        }
        assertEquals("cockpit", Raster.standard("cockpit").hintergrund)
    }

    @Test fun verschiebenNurAufFreienPlatz() {
        var l = Raster.seiteHinzufuegen(std)
        val uhr = l.seiten[1].first { it.kachel == Kachel.UHR }
        assertSame("belegt", l, Raster.verschieben(l, uhr.id, 1, 0, 0))
        assertSame("außerhalb", l, Raster.verschieben(l, uhr.id, 1, 5, 0))
        l = Raster.verschieben(l, uhr.id, 2, 3, 2)
        val neu = Raster.finde(l, uhr.id)!!
        assertEquals(2, neu.first); assertEquals(3, neu.second.x); assertEquals(2, neu.second.y)
        assertTrue(l.seiten[1].none { it.id == uhr.id })
        keineUeberlappung(l)
    }

    @Test fun groesseSchaltetDurchUndRespektiertNachbarn() {
        val leer = StartLayout(listOf(emptyList()), emptyList(), "panoptes", 0)
        var l = Raster.widgetHinzufuegen(leer, 0, Kachel.UHR)
        val id = l.seiten[0][0].id
        assertEquals(2 to 1, l.seiten[0][0].let { it.b to it.h })
        val gesehen = mutableSetOf<Pair<Int, Int>>()
        repeat(Raster.GROESSEN.size) { l = Raster.groesseWechseln(l, id); gesehen += Raster.finde(l, id)!!.second.let { it.b to it.h } }
        assertEquals(Raster.GROESSEN.toSet(), gesehen)
        // Nachbar blockiert: Größe springt nur auf passende Werte
        l = Raster.widgetHinzufuegen(StartLayout(listOf(emptyList()), emptyList(), "panoptes", 0), 0, Kachel.UHR)
        l = Raster.widgetHinzufuegen(l, 0, Kachel.AKKU) // landet bei x=2
        val uhr = l.seiten[0].first { it.kachel == Kachel.UHR }.id
        repeat(10) { l = Raster.groesseWechseln(l, uhr); keineUeberlappung(l) }
    }

    @Test fun appsUndWidgetsHinzufuegenUndEntfernen() {
        var l = StartLayout(listOf(emptyList()), emptyList(), "panoptes", 0)
        l = Raster.widgetHinzufuegen(l, 0, Kachel.NAVI)
        assertSame("Widget nur einmal", l, Raster.widgetHinzufuegen(l, 0, Kachel.NAVI))
        assertSame("ungültiges Paket", l, Raster.appHinzufuegen(l, 0, "kein paket"))
        repeat(16) { i -> l = Raster.appHinzufuegen(l, 0, "com.example.a$i") }
        assertEquals(17, l.seiten[0].size) // 18 Zellen, Navi belegt 2
        assertSame("voll", l, Raster.appHinzufuegen(l, 0, "com.example.voll"))
        val erste = l.seiten[0].first { it.typ == ElementTyp.APP }.id
        l = Raster.entfernen(l, erste)
        assertEquals(16, l.seiten[0].size)
        keineUeberlappung(l)
    }

    @Test fun ordner() {
        var l = StartLayout(listOf(emptyList()), emptyList(), "panoptes", 0)
        l = Raster.appHinzufuegen(l, 0, "com.a.eins"); l = Raster.appHinzufuegen(l, 0, "com.a.zwei")
        val (eins, zwei) = l.seiten[0].map { it.id }
        assertSame("leerer Name", l, Raster.neuerOrdner(l, eins, "  "))
        l = Raster.neuerOrdner(l, eins, "Musik")
        val ordner = l.seiten[0].first { it.typ == ElementTyp.ORDNER }
        assertEquals(listOf("com.a.eins"), ordner.apps)
        l = Raster.inOrdner(l, zwei, ordner.id)
        assertEquals(1, l.seiten[0].size)
        assertEquals(listOf("com.a.eins", "com.a.zwei"), Raster.finde(l, ordner.id)!!.second.apps)
        l = Raster.umbenennen(l, ordner.id, "Unterhaltung")
        assertEquals("Unterhaltung", Raster.finde(l, ordner.id)!!.second.name)
        l = Raster.ausOrdner(l, ordner.id, "com.a.eins")
        assertEquals(2, l.seiten[0].size)
        l = Raster.ausOrdner(l, ordner.id, "com.a.zwei")
        assertNull("leerer Ordner verschwindet", Raster.finde(l, ordner.id))
        assertEquals(2, l.seiten[0].count { it.typ == ElementTyp.APP })
    }

    @Test fun seiten() {
        var l = std
        repeat(10) { l = Raster.seiteHinzufuegen(l) }
        assertEquals(Raster.MAX_SEITEN, l.seiten.size)
        repeat(10) { l = Raster.seiteEntfernen(l, 0) }
        assertEquals("eine Seite bleibt immer", 1, l.seiten.size)
    }

    @Test fun dockUndLeiste() {
        var l = std
        assertSame(l, Raster.dockHinzufuegen(l, Raster.PANOPTES))
        l = Raster.dockHinzufuegen(l, "com.spotify.music"); l = Raster.dockHinzufuegen(l, "com.waze")
        l = Raster.dockVerschieben(l, "com.waze", -1)
        assertEquals(listOf(Raster.PANOPTES) + Raster.PROJEKTION + listOf("com.waze", "com.spotify.music"), l.dock)
        assertSame(l, Raster.dockVerschieben(l, Raster.PANOPTES, -1))
        l = Raster.dockEntfernen(l, Raster.PANOPTES)
        assertFalse(Raster.PANOPTES in l.dock)
        repeat(20) { i -> l = Raster.dockHinzufuegen(l, "com.x.a$i") }
        assertEquals(Raster.MAX_DOCK, l.dock.size)
        repeat(20) { i -> l = Raster.schnellHinzufuegen(l, "com.y.b$i") }
        assertEquals(Raster.MAX_SCHNELL, l.schnell.size)
        l = Raster.schnellVerschieben(l, "com.y.b1", -1)
        assertEquals("com.y.b1", l.schnell[0])
    }

    @Test fun dateiRundUndGeprueft() {
        var l = Raster.seiteHinzufuegen(std)
        l = Raster.appHinzufuegen(l, 2, "com.spotify.music")
        l = Raster.hintergrund(l, "cockpit").copy(zeigeKlima = false, schnell = listOf("com.waze"))
        val zurueck = Raster.fromJson(Raster.toJson(l))
        assertEquals(l, zurueck)

        assertNull(Raster.fromJson("kaputt{"))
        assertNull(Raster.fromJson("""{"format":"anderes","version":1}"""))
        assertNull(Raster.fromJson("""{"format":"panoptes-launcher-layout","version":99}"""))
        // Überlappung, unbekannte Kachel, böses Paket, doppelte Id und doppelte Kachel fallen weg
        val roh = """{"format":"panoptes-launcher-layout","version":1,"hintergrund":"<script>","dock":["com.ok.app","../x","com.ok.app"],
          "seiten":[[{"id":"w1","typ":"widget","kachel":"uhr","x":0,"y":0,"b":2,"h":1},
                     {"id":"w2","typ":"widget","kachel":"akku","x":1,"y":0,"b":2,"h":1},
                     {"id":"w3","typ":"widget","kachel":"hupe","x":3,"y":0,"b":1,"h":1},
                     {"id":"a4","typ":"app","paket":"rm -rf","x":5,"y":0},
                     {"id":"w1","typ":"widget","kachel":"navi","x":0,"y":2,"b":1,"h":1},
                     {"id":"w5","typ":"widget","kachel":"uhr","x":4,"y":2,"b":1,"h":1},
                     {"id":"a6","typ":"app","paket":"com.ok.app","x":9,"y":0}]]}"""
        val g = Raster.fromJson(roh)
        assertNotNull(g)
        assertEquals(listOf("w1"), g!!.seiten[0].map { it.id })
        assertEquals(listOf("com.ok.app"), g.dock)
        assertEquals("panoptes", g.hintergrund)
    }
}
