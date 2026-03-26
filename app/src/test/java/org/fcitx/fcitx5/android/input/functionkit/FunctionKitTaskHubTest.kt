package org.fcitx.fcitx5.android.input.functionkit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitTaskHubTest {
    @Test
    fun mergesUpdatesBySeqAndTracksRunningAndHistory() {
        FunctionKitTaskHub.resetForTest()

        val taskId = "t-test"
        val kitId = "kit-a"
        val surface = "panel"

        val baseTask =
            JSONObject()
                .put("taskId", taskId)
                .put("kitId", kitId)
                .put("surface", surface)
                .put("kind", "ai.request")
                .put("status", "running")
                .put("createdAt", "2026-03-26T00:00:00Z")
                .put("updatedAt", "2026-03-26T00:00:01Z")
                .put("seq", 1)
                .put("cancellable", true)
                .put("progress", JSONObject().put("message", "running"))

        FunctionKitTaskHub.recordTaskUpdate(
            kitId = kitId,
            surface = surface,
            payload = JSONObject().put("task", baseTask)
        )

        val first = FunctionKitTaskHub.snapshot()
        assertEquals(1, first.running.size)
        assertEquals(0, first.history.size)
        assertEquals(taskId, first.running.first().taskId)
        assertEquals("running", first.running.first().status)
        assertTrue(first.running.first().summary.contains("running"))

        // Same seq should be ignored.
        val ignored = JSONObject(baseTask.toString()).put("status", "succeeded").put("seq", 1)
        FunctionKitTaskHub.recordTaskUpdate(
            kitId = kitId,
            surface = surface,
            payload = JSONObject().put("task", ignored)
        )

        val second = FunctionKitTaskHub.snapshot()
        assertEquals(1, second.running.size)
        assertEquals(0, second.history.size)

        // Higher seq should be accepted and move to history.
        val succeeded =
            JSONObject(baseTask.toString())
                .put("status", "succeeded")
                .put("seq", 2)
                .put("updatedAt", "2026-03-26T00:00:02Z")
                .put("result", JSONObject().put("summary", "ok"))

        FunctionKitTaskHub.recordTaskUpdate(
            kitId = kitId,
            surface = surface,
            payload = JSONObject().put("task", succeeded)
        )

        val third = FunctionKitTaskHub.snapshot()
        assertEquals(0, third.running.size)
        assertEquals(1, third.history.size)
        assertEquals(taskId, third.history.first().taskId)
    }
}

