/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.voice.SherpaOnnxModelPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppPrefsVoiceFeatureMigrationTest {
    @Test
    fun `voice build repairs long press when upgrading from standard package`() {
        assumeTrue(BuildConfig.WITH_VOICE_INPUT)

        val prefs =
            newPrefs(
                "migrated_voice_space_long_press_default_on" to true,
                "migrated_voice_space_long_press_backfill_on" to true,
                "migrated_voice_toolbar_button_retired" to true,
                "space_long_press_behavior" to SpaceLongPressBehavior.ShowPicker.name,
                "show_voice_input_button" to true
            )

        applyMigrations(prefs)

        assertEquals(
            SpaceLongPressBehavior.VoiceInput,
            prefs.keyboard.spaceKeyLongPressBehavior.getValue()
        )
        assertFalse(prefs.keyboard.showVoiceInputButton.getValue())
        assertEquals(true, prefs.internal.lastKnownVoiceFeatureEnabled.getValue())
    }

    @Test
    fun `voice build preserves explicit non-default long press behavior on upgrade`() {
        assumeTrue(BuildConfig.WITH_VOICE_INPUT)

        val prefs =
            newPrefs(
                "migrated_voice_space_long_press_default_on" to true,
                "migrated_voice_space_long_press_backfill_on" to true,
                "migrated_voice_toolbar_button_retired" to true,
                "space_long_press_behavior" to SpaceLongPressBehavior.Enumerate.name
            )

        applyMigrations(prefs)

        assertEquals(
            SpaceLongPressBehavior.Enumerate,
            prefs.keyboard.spaceKeyLongPressBehavior.getValue()
        )
        assertEquals(true, prefs.internal.lastKnownVoiceFeatureEnabled.getValue())
    }

    @Test
    fun `voice build retires fast ctc preference to auto`() {
        assumeTrue(BuildConfig.WITH_VOICE_INPUT)

        val prefs =
            newPrefs(
                "built_in_sherpa_model" to SherpaOnnxModelPreference.FastCtc.name
            )

        applyMigrations(prefs)

        assertEquals(
            SherpaOnnxModelPreference.Auto,
            prefs.keyboard.builtInSherpaModel.getValue()
        )
    }

    private fun newPrefs(vararg seedEntries: Pair<String, Any?>): AppPrefs {
        val sharedPreferences = InMemorySharedPreferences(linkedMapOf(*seedEntries))
        return AppPrefs(sharedPreferences)
    }

    private fun applyMigrations(prefs: AppPrefs) {
        val method = AppPrefs::class.java.getDeclaredMethod("applyMigrations")
        method.isAccessible = true
        method.invoke(prefs)
    }

    private class InMemorySharedPreferences(
        initial: Map<String, Any?> = emptyMap()
    ) : SharedPreferences {
        private val values = LinkedHashMap(initial)

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)

        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(
            private val values: LinkedHashMap<String, Any?>
        ) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = applyChange(key, values?.toSet())

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

            override fun remove(key: String?): SharedPreferences.Editor = applyChange(key, null)

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true

            override fun apply() {}

            private fun applyChange(
                key: String?,
                value: Any?
            ): SharedPreferences.Editor {
                if (key != null) {
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
                return this
            }
        }
    }
}
