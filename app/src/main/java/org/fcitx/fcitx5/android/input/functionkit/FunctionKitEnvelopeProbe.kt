/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.os.SystemClock
import org.json.JSONObject

/**
 * In-process probe for end-to-end tests.
 *
 * Records inbound/outbound Function Kit envelopes so tests can assert handshake and API flows without
 * depending on WebView accessibility text.
 */
internal object FunctionKitEnvelopeProbe {
    enum class Direction {
        Inbound,
        Outbound
    }

    data class Event(
        val direction: Direction,
        val kitId: String,
        val type: String,
        val envelope: String,
        val timestampEpochMs: Long
    )

    private const val MaxEvents = 2048
    private val lock = java.lang.Object()
    private val events = ArrayDeque<Event>()

    fun clear() {
        synchronized(lock) {
            events.clear()
            lock.notifyAll()
        }
    }

    fun recordInbound(envelope: JSONObject) = record(Direction.Inbound, envelope)

    fun recordOutbound(envelope: JSONObject) = record(Direction.Outbound, envelope)

    fun await(
        kitId: String,
        direction: Direction,
        type: String,
        timeoutMs: Long
    ): Event {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                events.firstOrNull { it.kitId == kitId && it.direction == direction && it.type == type }?.let {
                    return it
                }
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) {
                    break
                }
                lock.wait(remaining)
            }
        }
        throw AssertionError("Timed out waiting for envelope. kitId=$kitId direction=$direction type=$type")
    }

    private fun record(
        direction: Direction,
        envelope: JSONObject
    ) {
        val kitId = envelope.optString("kitId")
        val type = envelope.optString("type")
        if (kitId.isBlank() || type.isBlank()) {
            return
        }

        val event =
            Event(
                direction = direction,
                kitId = kitId,
                type = type,
                envelope = envelope.toString(),
                timestampEpochMs = System.currentTimeMillis()
            )
        synchronized(lock) {
            while (events.size >= MaxEvents) {
                events.removeFirst()
            }
            events.addLast(event)
            lock.notifyAll()
        }
    }
}
