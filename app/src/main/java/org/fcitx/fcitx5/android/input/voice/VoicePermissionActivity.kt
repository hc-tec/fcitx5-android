package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.toast

class VoicePermissionActivity : AppCompatActivity() {
    private var permissionDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceInputPermission.hasRecordAudioPermission(this)) {
            finish()
            return
        }

        permissionDialog =
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.voice_input_permission_title)
                .setMessage(R.string.voice_input_permission_message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RequestCode)
                }
                .setOnCancelListener { finish() }
                .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RequestCode) {
            return
        }

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            toast(R.string.voice_input_permission_granted)
            finish()
            return
        }

        toast(R.string.voice_input_permission_denied)
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            VoiceInputPermission.launchAppSettings(this)
        }
        finish()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        permissionDialog = null
        super.onDestroy()
    }

    companion object {
        private const val RequestCode = 9001
    }
}
