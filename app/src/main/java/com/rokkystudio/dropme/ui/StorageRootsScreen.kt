package com.rokkystudio.dropme.ui

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import badfox.logic.neoui.NeoIconButtonView
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry

/**
 * Показывает опубликованные пути с отдельным управлением доступностью и удалением пользовательских папок.
 */
class StorageRootsScreen(
    private val context: Context,
    private val container: LinearLayout,
    private val onEnabledChanged: (StorageRootEntry, Boolean) -> Unit,
    private val onRootSelected: (StorageRootEntry) -> Unit,
    private val onRemoveRoot: (StorageRootEntry) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)

    fun show(roots: List<StorageRootEntry>) {
        container.removeAllViews()
        roots.forEach { root ->
            val row = inflater.inflate(R.layout.storage_root_row, container, false)
            val enabledView = row.findViewById<CheckBox>(R.id.storageRootEnabled)
            val iconView = row.findViewById<ImageView>(R.id.storageRootIcon)
            val titleView = row.findViewById<TextView>(R.id.storageRootTitle)
            val stateView = row.findViewById<TextView>(R.id.storageRootState)
            val removeButton = row.findViewById<NeoIconButtonView>(R.id.storageRootRemove)

            titleView.text = root.displayName
            enabledView.isChecked = root.isEnabled

            val ready = root.accessState == StorageAccessState.READY
            stateView.text = context.getString(
                if (ready) R.string.storage_access_granted else R.string.storage_access_not_granted,
            )
            val color = ContextCompat.getColor(
                context,
                if (ready) R.color.status_connected else R.color.status_error,
            )
            iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN)

            enabledView.setOnClickListener {
                onEnabledChanged(root, enabledView.isChecked)
            }
            row.setOnClickListener {
                if (!ready) {
                    onRootSelected(root)
                }
            }

            removeButton.visibility = if (root.canRemove) View.VISIBLE else View.GONE
            removeButton.setOnClickListener {
                onRemoveRoot(root)
            }

            container.addView(row)
        }
    }
}
