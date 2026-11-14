package com.watchly.app.ui.person

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for the person hand-over store contract (cycle 11,
 * §3.3) — the same semantics TitleDetailsStore pins, because the
 * person route relies on the identical remember-capture +
 * pop-then-clear navigation pattern:
 *  - take() is a non-consuming read;
 *  - clear() empties the store and is safe to call twice;
 *  - select() replaces any stale person wholesale.
 *
 * No Android classes involved — runs with the standalone kotlinc +
 * JUnit console launcher (tools/run-jvm-tests.sh).
 */
class PersonStoreTest {

    @BeforeEach
    fun setUp() = PersonStore.clear()

    @AfterEach
    fun tearDown() = PersonStore.clear()

    @Test
    fun `take does not consume - repeated reads return the same person`() {
        PersonStore.select(94724, "N. T. Rama Rao Jr.")
        assertEquals(
            SelectedPerson(94724, "N. T. Rama Rao Jr."),
            PersonStore.take()
        )
        assertEquals(
            SelectedPerson(94724, "N. T. Rama Rao Jr."),
            PersonStore.take()
        )
    }

    @Test
    fun `clear empties the store and is idempotent`() {
        PersonStore.select(94724, "N. T. Rama Rao Jr.")
        PersonStore.clear()
        assertNull(PersonStore.take())
        PersonStore.clear()
        assertNull(PersonStore.take())
    }

    @Test
    fun `select replaces a stale person`() {
        PersonStore.select(94724, "N. T. Rama Rao Jr.")
        PersonStore.select(10859, "Ram Charan")
        assertEquals(SelectedPerson(10859, "Ram Charan"), PersonStore.take())
    }
}
