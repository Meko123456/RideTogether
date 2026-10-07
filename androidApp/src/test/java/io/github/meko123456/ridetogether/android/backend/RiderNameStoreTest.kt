package io.github.meko123456.ridetogether.android.backend

import io.github.meko123456.ridetogether.model.Member
import kotlin.test.Test
import kotlin.test.assertEquals

class RiderNameStoreTest {

    @Test
    fun `a name is trimmed and cut where the database rules cut it`() {
        assertEquals("Nino", RiderNameStore.tidy("  Nino  "))
        assertEquals("x".repeat(Member.MAX_NAME_LENGTH), RiderNameStore.tidy("x".repeat(60)))
    }

    @Test
    fun `an emoji across the cut is left out whole, not kept in half`() {
        // 🔥 is two chars; take(40) kept the first, which the other riders saw as "?".
        assertEquals("x".repeat(39), RiderNameStore.tidy("x".repeat(39) + "🔥"))
    }
}
