package io.github.meko123456.ridetogether.android.ui

import io.github.meko123456.ridetogether.model.Member
import kotlin.test.Test
import kotlin.test.assertEquals

class ShownNameTest {

    @Test
    fun `this phone's own row says so and everyone else's is just their name`() {
        assertEquals("Nino (you)", shownName(Member("uid-n", "Nino"), selfId = "uid-n"))
        assertEquals("Ana", shownName(Member("uid-a", "Ana"), selfId = "uid-n"))
    }

    @Test
    fun `a rider already called You is not called You twice`() {
        // The in-memory backend's own name, where nobody else ever sees it.
        assertEquals("You", shownName(Member("me", "You"), selfId = "me"))
    }
}
