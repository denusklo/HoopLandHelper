package com.denusklo.hooplandhelper.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCheckerTest {

    @Test
    fun `isRooted returns true when su output contains uid=0`() {
        val checker = RootChecker(runSuId = { "uid=0(root) gid=0(root)" })
        assertTrue(checker.isRooted())
    }

    @Test
    fun `isRooted returns false when su output does not contain uid=0`() {
        val checker = RootChecker(runSuId = { "uid=10123(u0_a123)" })
        assertFalse(checker.isRooted())
    }

    @Test
    fun `isRooted returns false when su throws exception`() {
        val checker = RootChecker(runSuId = { throw RuntimeException("su not found") })
        assertFalse(checker.isRooted())
    }
}
