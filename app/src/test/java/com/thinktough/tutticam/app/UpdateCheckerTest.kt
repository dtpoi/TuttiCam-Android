package com.thinktough.tutticam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesVersionsByNumber() {
        assertTrue(UpdateChecker.isNewer("v1.1", "1.0"))
        assertTrue(UpdateChecker.isNewer("v1.10", "1.9"))
        assertTrue(UpdateChecker.isNewer("v2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v1.0.1", "1.0"))
    }

    @Test
    fun treatsSameOrOlderAsLatest() {
        assertFalse(UpdateChecker.isNewer("v1.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0"))
        assertFalse(UpdateChecker.isNewer("v1.0", "1.1"))
        assertFalse(UpdateChecker.isNewer("v0.9", "1.0"))
    }
}
