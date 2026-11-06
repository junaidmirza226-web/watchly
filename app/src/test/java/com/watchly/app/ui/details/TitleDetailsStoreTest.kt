package com.watchly.app.ui.details

import com.watchly.app.data.model.MediaType
import com.watchly.app.data.model.Title
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the details hand-over store contract.
 *
 * These pin the semantics the 2026-11-06 back-navigation fix relies
 * on (bug: "back from details does not work"):
 *  - take() is a non-consuming read, so re-reading it during
 *    recomposition can never lose the selected title;
 *  - clear() empties the store, and is safe to call twice;
 *  - select() replaces any stale title wholesale, so an old pick can
 *    never resurface under a new one.
 *
 * No Android classes involved — runs with the standalone kotlinc +
 * JUnit console launcher (tools/run-jvm-tests.sh).
 */
class TitleDetailsStoreTest {

    private val darkKnight = Title(
        tmdbId = 155,
        name = "The Dark Knight",
        mediaType = MediaType.MOVIE,
        year = "2008"
    )
    private val other = Title(
        tvmazeId = 82,
        name = "Some Show",
        mediaType = MediaType.TV,
        year = "2011"
    )

    @BeforeEach
    fun setUp() = TitleDetailsStore.clear()

    @AfterEach
    fun tearDown() = TitleDetailsStore.clear()

    @Test
    fun `take does not consume - repeated reads return the same title`() {
        TitleDetailsStore.select(darkKnight)
        assertSame(darkKnight, TitleDetailsStore.take())
        // A recomposition (or the exit transition) re-reading the
        // store must still see the title.
        assertSame(darkKnight, TitleDetailsStore.take())
    }

    @Test
    fun `clear empties the store and is idempotent`() {
        TitleDetailsStore.select(darkKnight)
        TitleDetailsStore.clear()
        assertNull(TitleDetailsStore.take())
        TitleDetailsStore.clear()
        assertNull(TitleDetailsStore.take())
    }

    @Test
    fun `select replaces a stale title`() {
        TitleDetailsStore.select(darkKnight)
        TitleDetailsStore.select(other)
        assertEquals(other, TitleDetailsStore.take())
    }

    @Test
    fun `empty store returns null`() {
        assertNull(TitleDetailsStore.take())
    }
}
