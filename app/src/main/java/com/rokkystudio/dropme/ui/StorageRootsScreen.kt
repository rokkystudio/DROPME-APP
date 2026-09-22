package com.rokkystudio.dropme.ui

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry

/**
 * Показывает доступные хранилища с цветным индикатором доступа.
 */
class StorageRootsScreen(
    private val context: Context,
    private val container: LinearLayout,
    private val onRootSelected: (StorageRootEntry) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)

    fun show(roots: List<StorageRootEntry>) {
        container.removeAllViews()
        roots.forEach { root ->
            val row = inflater.inflate(R.layout.storage_root_row, container, false)
            val iconView = row.findViewById<ImageView>(R.id.storageRootIcon)
            val titleView = row.findViewById<TextView>(R.id.storageRootTitle)
            val stateView = row.findViewById<TextView>(R.id.storageRootState)

            titleView.text = root.displayName
            val ready = root.accessState == StorageAccessState.READY
            stateView.text = context.getString(
                if (ready) R.string.storage_access_granted else R.string.storage_access_not_granted,
            )
            val color = ContextCompat.getColor(
                context,
                if (ready) R.color.status_connected else R.color.status_error,
            )
            iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            row.setOnClickListener {
                onRootSelected(root)
            }
            container.addView(row)
        }
    }
}
