package com.anthonyla.paperize.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSyncPlannerTest {

    @Test
    fun `complete scan adds new and removes missing uris`() {
        val plan = FolderSyncPlanner.plan(
            storedUris = setOf("a", "b", "c"),
            scannedUris = setOf("b", "c", "d"),
            scanComplete = true
        )
        assertEquals(setOf("d"), plan.toAdd)
        assertEquals(setOf("a"), plan.toRemove)
    }

    @Test
    fun `incomplete scan adds new uris but removes nothing`() {
        val plan = FolderSyncPlanner.plan(
            storedUris = setOf("a", "b"),
            scannedUris = setOf("b", "x"),
            scanComplete = false
        )
        assertEquals(setOf("x"), plan.toAdd)
        assertEquals(emptySet<String>(), plan.toRemove)
    }

    @Test
    fun `unchanged folder yields empty plan`() {
        val uris = setOf("a", "b")
        val plan = FolderSyncPlanner.plan(uris, uris, scanComplete = true)
        assertEquals(emptySet<String>(), plan.toAdd)
        assertEquals(emptySet<String>(), plan.toRemove)
    }

    @Test
    fun `complete empty scan removes everything`() {
        val plan = FolderSyncPlanner.plan(setOf("a", "b"), emptySet(), scanComplete = true)
        assertEquals(setOf("a", "b"), plan.toRemove)
    }
}
