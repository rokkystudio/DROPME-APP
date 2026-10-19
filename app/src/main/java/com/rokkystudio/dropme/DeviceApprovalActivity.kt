package com.rokkystudio.dropme

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rokkystudio.dropme.device.IncomingApprovalCoordinator

/**
 * Показывает решение по первому входящему запросу неизвестного DROPME-устройства.
 */
class DeviceApprovalActivity : AppCompatActivity() {
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val request = IncomingApprovalCoordinator.get(requestId)
        if (request == null) {
            finish()
            return
        }

        val rememberCheckBox = CheckBox(this).apply {
            text = getString(R.string.approval_remember)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(TextView(this@DeviceApprovalActivity).apply {
                text = getString(
                    if (request.purpose == PURPOSE_SHARE) {
                        R.string.approval_message_share
                    } else {
                        R.string.approval_message_files
                    },
                    request.deviceName,
                    request.remoteIp,
                    request.deviceId,
                )
                textSize = 15f
            })
            request.itemName?.takeIf { it.isNotBlank() }?.let { itemName ->
                addView(TextView(this@DeviceApprovalActivity).apply {
                    text = getString(R.string.approval_item_name, itemName)
                    textSize = 13f
                    setPadding(0, dp(12), 0, 0)
                })
            }
            addView(rememberCheckBox)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.approval_title)
            .setView(content)
            .setPositiveButton(R.string.approval_allow) { _, _ ->
                resolve(true, rememberCheckBox.isChecked)
            }
            .setNegativeButton(R.string.approval_deny) { _, _ ->
                resolve(false, rememberCheckBox.isChecked)
            }
            .setOnCancelListener {
                resolve(false, false)
            }
            .create()

        dialog.setOnDismissListener {
            if (!isFinishing) {
                finish()
            }
        }
        dialog.show()
    }

    private fun resolve(allowed: Boolean, remember: Boolean) {
        IncomingApprovalCoordinator.resolve(
            context = this,
            requestId = requestId,
            allowed = allowed,
            remember = remember,
        )
        finish()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val PURPOSE_SHARE = "share"

        fun createIntent(context: Context, requestId: String): Intent {
            return Intent(context, DeviceApprovalActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }
}
