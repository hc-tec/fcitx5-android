/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.functionkit

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionKitPermissionPolicyTest {
    @Test
    fun `ai chat status permission follows ai chat toggle`() {
        val disabledPermissions =
            FunctionKitPermissionPolicy.grantedPermissions(
                requestedPermissions = listOf("ai.chat", "ai.chat.status.request"),
                prefs = fakeFunctionKitPrefs(allowAiChat = false)
            )
        assertFalse("ai.chat" in disabledPermissions)
        assertFalse("ai.chat.status.request" in disabledPermissions)

        val enabledPermissions =
            FunctionKitPermissionPolicy.grantedPermissions(
                requestedPermissions = listOf("ai.chat", "ai.chat.status.request"),
                prefs = fakeFunctionKitPrefs(allowAiChat = true)
            )
        assertEquals(listOf("ai.chat", "ai.chat.status.request"), enabledPermissions)
    }

    @Test
    fun `agent permissions still require remote inference and agent access`() {
        assertFalse(
            FunctionKitPermissionPolicy.isEnabled(
                "ai.agent.list",
                fakeFunctionKitPrefs(remoteInferenceEnabled = false, allowAiAgentAccess = true)
            )
        )
        assertFalse(
            FunctionKitPermissionPolicy.isEnabled(
                "ai.agent.run",
                fakeFunctionKitPrefs(remoteInferenceEnabled = true, allowAiAgentAccess = false)
            )
        )
        assertTrue(
            FunctionKitPermissionPolicy.isEnabled(
                "ai.agent.run",
                fakeFunctionKitPrefs(remoteInferenceEnabled = true, allowAiAgentAccess = true)
            )
        )
    }

    private fun fakeFunctionKitPrefs(
        allowAiChat: Boolean = true,
        remoteInferenceEnabled: Boolean = false,
        allowAiAgentAccess: Boolean = true
    ): AppPrefs.FunctionKit {
        val sharedPreferences =
            InMemorySharedPreferences(
                mutableMapOf(
                    "function_kit_permission_ai_chat" to allowAiChat,
                    "function_kit_remote_inference_enabled" to remoteInferenceEnabled,
                    "function_kit_permission_ai_agent_access" to allowAiAgentAccess
                )
            )
        return AppPrefs(sharedPreferences).FunctionKit()
    }

    private class InMemorySharedPreferences(
        private val values: MutableMap<String, Any?>
    ) : SharedPreferences {
        override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

        override fun getBoolean(
            key: String?,
            defValue: Boolean
        ): Boolean = (key?.let(values::get) as? Boolean) ?: defValue

        override fun getInt(
            key: String?,
            defValue: Int
        ): Int = (key?.let(values::get) as? Int) ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long
        ): Long = (key?.let(values::get) as? Long) ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float
        ): Float = (key?.let(values::get) as? Float) ?: defValue

        override fun getString(
            key: String?,
            defValue: String?
        ): String? = (key?.let(values::get) as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = ((key?.let(values::get) as? Set<String>)?.toMutableSet()) ?: defValues

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        private class Editor(
            private val values: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putBoolean(
                key: String?,
                value: Boolean
            ): SharedPreferences.Editor = applyChange(key, value)

            override fun putInt(
                key: String?,
                value: Int
            ): SharedPreferences.Editor = applyChange(key, value)

            override fun putLong(
                key: String?,
                value: Long
            ): SharedPreferences.Editor = applyChange(key, value)

            override fun putFloat(
                key: String?,
                value: Float
            ): SharedPreferences.Editor = applyChange(key, value)

            override fun putString(
                key: String?,
                value: String?
            ): SharedPreferences.Editor = applyChange(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = applyChange(key, values?.toSet())

            override fun remove(key: String?): SharedPreferences.Editor = applyChange(key, null)

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                pending.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    values.clear()
                }
                pending.forEach { (key, value) ->
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
            }

            private fun applyChange(
                key: String?,
                value: Any?
            ): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                }
                return this
            }
        }
    }
}
