package org.fcitx.fcitx5.android.input.voice

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.ClipboardEditActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class FcitxImeSmokeInstrumentationTest {

    @Test
    fun clipboardEditActivity_bindsToFcitxIme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        val enableOutput = runShellCommand("ime enable $IME_ID")
        assertTrue(
            "Failed to enable Fcitx IME",
            enableOutput.isBlank() ||
                enableOutput.contains("already enabled") ||
                enableOutput.contains("now enabled")
        )
        runShellCommand("ime set $IME_ID")
        assertTrue(
            "Expected Fcitx IME to become default input method",
            runShellCommand("settings get secure default_input_method").trim() == IME_ID
        )

        ActivityScenario.launch<ClipboardEditActivity>(Intent(context, ClipboardEditActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val editText = activity.findViewById<EditText>(R.id.clipboard_edit_text)
                editText.requestFocus()
                val imm = activity.getSystemService(InputMethodManager::class.java)
                imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
            }

            SystemClock.sleep(1500)

            val inputMethodDump = runShellCommand("dumpsys input_method")
            assertTrue("Expected dumpsys input_method to reference current Fcitx IME", inputMethodDump.contains("mCurId=$IME_ID"))
            assertTrue(
                "Expected ClipboardEditActivity EditText to become the served view.\n$inputMethodDump",
                inputMethodDump.contains("mServedView=android.widget.EditText") ||
                    inputMethodDump.contains("mServedView=androidx.appcompat.widget.AppCompatEditText")
            )
        }
    }

    private fun runShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                reader.readText()
            }
        }
    }

    private companion object {
        private const val IME_ID = "org.fcitx.fcitx5.android.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
    }
}
