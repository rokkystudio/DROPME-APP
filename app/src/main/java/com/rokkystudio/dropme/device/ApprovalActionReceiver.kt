package com.rokkystudio.dropme.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обрабатывает действия approval-notification без открытия приложения.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            return
        }

        when (intent.action) {
            ACTION_ALLOW -> IncomingApprovalCoordinator.resolve(
                context = context,
                requestId = requestId,
                allowed = true,
                remember = false,
            )

            ACTION_DENY -> IncomingApprovalCoordinator.resolve(
                context = context,
                requestId = requestId,
                allowed = false,
                remember = false,
            )
        }
    }

    companion object {
        const val ACTION_ALLOW = "com.rokkystudio.dropme.action.APPROVAL_ALLOW"
        const val ACTION_DENY = "com.rokkystudio.dropme.action.APPROVAL_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
