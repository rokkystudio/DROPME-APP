package com.rokkystudio.dropme.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Отменяет активную входящую передачу при смахивании уведомления или выборе действия отмены.
 */
class IncomingTransferActionReceiver : BroadcastReceiver() {
    /**
     * Передаёт запрос отмены соответствующей HTTP-сессии координатору приёма.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingTransferCoordinator.ACTION_CANCEL) {
            return
        }
        val id = intent.getIntExtra(IncomingTransferCoordinator.EXTRA_TRANSFER_ID, -1)
        if (id >= 0) {
            IncomingTransferCoordinator.cancel(context, id)
        }
    }
}
