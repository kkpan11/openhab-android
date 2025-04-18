/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.model.Item
import org.openhab.habdroid.model.toOH2IconResource
import org.openhab.habdroid.ui.widget.WidgetImageView
import org.openhab.habdroid.util.determineDataUsagePolicy

class ItemPickerAdapter(context: Context, private val itemClickListener: ItemClickListener?) :
    RecyclerView.Adapter<ItemPickerAdapter.ItemViewHolder>(),
    View.OnClickListener {
    private val filteredItems = ArrayList<Item>()
    private val allItems = ArrayList<Item>()
    private val inflater = LayoutInflater.from(context)
    private var highlightedPosition = -1

    interface ItemClickListener {
        fun onItemClicked(item: Item)
    }

    fun setItems(items: List<Item>) {
        filteredItems.clear()
        filteredItems.addAll(items)
        filteredItems.sortWith { lhs, rhs -> lhs.name.compareTo(rhs.name, ignoreCase = true) }
        allItems.clear()
        allItems.addAll(filteredItems)
        notifyDataSetChanged()
    }

    fun filter(filter: String) {
        filteredItems.clear()
        val searchTerm = filter.lowercase(Locale.getDefault())
        allItems.filterTo(filteredItems) { item ->
            searchTerm in item.name.lowercase(Locale.getDefault()) ||
                searchTerm in item.label?.lowercase(Locale.getDefault()).orEmpty() ||
                searchTerm in item.type.toString().lowercase(Locale.getDefault())
        }
        notifyDataSetChanged()
    }

    fun clear() {
        filteredItems.clear()
        notifyDataSetChanged()
    }

    fun findPositionForName(name: String): Int = filteredItems.indexOfFirst { item -> item.name == name }

    fun highlightItem(position: Int) {
        highlightedPosition = position
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder = ItemViewHolder(inflater, parent)

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(filteredItems[position])
        holder.itemView.setOnClickListener(if (itemClickListener != null) this else null)

        if (position == highlightedPosition) {
            holder.itemView.playPressAnimationAndCallBack {
                highlightedPosition = -1
            }
        }
    }

    override fun getItemCount(): Int = filteredItems.size

    override fun onClick(view: View) {
        val holder = view.tag as ItemViewHolder
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            itemClickListener?.onItemClicked(filteredItems[position])
        }
    }

    class ItemViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        RecyclerView.ViewHolder(inflater.inflate(R.layout.itempickerlist_item, parent, false)) {
        private val itemNameView: TextView = itemView.findViewById(R.id.itemName)
        private val itemLabelView: TextView = itemView.findViewById(R.id.itemLabel)
        private val iconView: WidgetImageView = itemView.findViewById(R.id.itemIcon)
        private val itemTypeView: TextView = itemView.findViewById(R.id.itemType)

        init {
            itemView.tag = this
        }

        fun bind(item: Item) {
            itemNameView.text = item.name
            itemLabelView.text = item.label
            itemTypeView.text = item.type.toString()

            val context = itemView.context
            val connection = ConnectionFactory.primaryUsableConnection?.connection
            val icon = item.category.toOH2IconResource()
            if (icon != null && connection != null) {
                iconView.setImageUrl(
                    connection,
                    icon.toUrl(context, context.determineDataUsagePolicy(connection).loadIconsWithState),
                    timeoutMillis = 2000
                )
            } else {
                iconView.applyFallbackDrawable()
            }
        }
    }
}
