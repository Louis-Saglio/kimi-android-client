package fr.louissaglio.kimi.android.data.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceLabelsTest {

    @Test
    fun `distinct basenames keep the basename only`() {
        assertEquals(
            mapOf("/home/ubuntu/kimi" to "kimi", "/tmp/spike" to "spike"),
            workspaceLabelsFor(listOf("/home/ubuntu/kimi", "/tmp/spike")),
        )
    }

    @Test
    fun `colliding basenames prepend parents until the labels differ`() {
        assertEquals(
            mapOf("/home/a/app" to "a/app", "/home/b/app" to "b/app"),
            workspaceLabelsFor(listOf("/home/a/app", "/home/b/app")),
        )
    }

    @Test
    fun `identical cwds share one label`() {
        assertEquals(
            mapOf("/home/a/app" to "app"),
            workspaceLabelsFor(listOf("/home/a/app", "/home/a/app")),
        )
    }

    @Test
    fun `a nested collision resolves each cwd minimally`() {
        assertEquals(
            mapOf("/x/a/app" to "x/a/app", "/y/a/app" to "y/a/app", "/b/app" to "b/app"),
            workspaceLabelsFor(listOf("/x/a/app", "/y/a/app", "/b/app")),
        )
    }

    @Test
    fun `trailing slashes are trimmed before labeling`() {
        assertEquals(
            mapOf("/home/a/app/" to "a/app", "/home/b/app" to "b/app"),
            workspaceLabelsFor(listOf("/home/a/app/", "/home/b/app")),
        )
    }

    @Test
    fun `root and null cwds get no label`() {
        assertEquals(emptyMap<String, String>(), workspaceLabelsFor(listOf("/", null)))
    }

    @Test
    fun `a cwd that runs out of parents keeps its full path while the other extends`() {
        assertEquals(
            mapOf("/a/app" to "a/app", "/b/a/app" to "b/a/app"),
            workspaceLabelsFor(listOf("/a/app", "/b/a/app")),
        )
    }
}
