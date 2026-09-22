package com.rokkystudio.dropme.ui

import android.content.Context
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.storage.StorageAccessState
import com.rokkystudio.dropme.storage.StorageRootEntry

/**
 * Показывает корни хранилища с цветным индикатором доступа.
 */
class StorageRootsScreen(
    private val context: Context,
    private val listView: ListView,
    private val onRootSelected: (StorageRootEntry) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)

    fun show(roots: List<StorageRootEntry>) {
        val adapter = object : ArrayAdapter<StorageRootEntry>(
            context,
            R.layout.storage_root_row,
            R.id.storageRootTitle,
            roots,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(R.layout.storage_root_row, parent, false)
                val root = getItem(position) ?: return view
                val iconView = view.findViewById<ImageView>(R.id.storageRootIcon)
                val titleView = view.findViewById<TextView>(R.id.storageRootTitle)
                val stateView = view.findViewById<TextView>(R.id.storageRootState)

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
                return view
            }
        }

        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let(onRootSelected)
        }
    }
}
