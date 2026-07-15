package com.rokkystudio.dropme.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.rokkystudio.dropme.R
import com.rokkystudio.dropme.network.WindowsServer

/**
 * Отображает список найденных Windows-серверов для выбора получателя Share.
 */
class ShareServerPickerScreen(
    private val context: Context,
    private val container: LinearLayout? = null,
    private val listView: ListView? = null,
    private val onServerSelected: (WindowsServer) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)

    /**
     * Показывает список серверов и обрабатывает выбор пользователя.
     */
    fun show(servers: List<WindowsServer>) {
        when {
            container != null -> {
                container.removeAllViews()
                servers.forEach { server ->
                    val row = inflater.inflate(R.layout.share_server_row, container, false)
                    bindRow(row, server)
                    row.setOnClickListener {
                        onServerSelected(server)
                    }
                    container.addView(row)
                }
                container.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
            }

            listView != null -> {
                val adapter = object : ArrayAdapter<WindowsServer>(
                    context,
                    android.R.layout.simple_list_item_2,
                    android.R.id.text1,
                    servers,
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        val server = getItem(position) ?: return view
                        val titleView = view.findViewById<TextView>(android.R.id.text1)
                        val subtitleView = view.findViewById<TextView>(android.R.id.text2)
                        titleView.text = context.getString(R.string.share_server_row_title, server.deviceName)
                        subtitleView.text = context.getString(R.string.share_server_row_subtitle, server.host, server.tcpPort)
                        return view
                    }
                }

                listView.adapter = adapter
                listView.setOnItemClickListener { _, _, position, _ ->
                    adapter.getItem(position)?.let(onServerSelected)
                }
            }
        }
    }

    private fun bindRow(row: View, server: WindowsServer) {
        row.findViewById<TextView>(R.id.shareServerRowTitle).text =
            context.getString(R.string.share_server_row_title, server.deviceName)
        row.findViewById<TextView>(R.id.shareServerRowSubtitle).text =
            context.getString(R.string.share_server_row_subtitle, server.host, server.tcpPort)
    }
}

