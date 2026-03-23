package org.monash.ceqa

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.monash.ceqa.models.Asset

class ScanLogAdapter(
    private val assets: MutableList<Asset>,
    var fontSizeSp: Float = FONT_SIZE_MEDIUM
) : RecyclerView.Adapter<ScanLogAdapter.ViewHolder>() {

    companion object {
        const val FONT_SIZE_SMALL  = 12f
        const val FONT_SIZE_MEDIUM = 15f
        const val FONT_SIZE_LARGE  = 17f

        private const val COLOR_TIME          = "#66BB6A"
        private const val COLOR_ASSET         = "#00E676"
        private const val COLOR_FOUND         = "#69F0AE"
        private const val COLOR_NEW           = "#40C4FF"
        private const val COLOR_DEPT          = "#2E7D32"
        private const val COLOR_ROW_EVEN      = "#0B0E0B"
        private const val COLOR_ROW_ODD       = "#0D120D"
        private const val COLOR_ROW_SELECTED  = "#1A3320"

        private const val COLOR_STICKER_YELLOW = "#FDD835"
        private const val COLOR_STICKER_WHITE  = "#F5F5F5"
        private const val COLOR_STICKER_GREEN  = "#66BB6A"
    }

    // ── Selection state ───────────────────────────────────────────────────────

    var selectionMode: Boolean = false
        private set

    private val selectedPositions = mutableSetOf<Int>()

    var onEnterSelectionMode: (() -> Unit)? = null
    var onSelectionChanged: ((count: Int) -> Unit)? = null

    fun enterSelectionMode(position: Int) {
        selectionMode = true
        selectedPositions.clear()
        selectedPositions.add(position)
        notifyDataSetChanged()
        onEnterSelectionMode?.invoke()
        onSelectionChanged?.invoke(selectedPositions.size)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) selectedPositions.remove(position)
        else selectedPositions.add(position)
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)
    }

    fun getSelectedAssetNumbers(): Set<String> =
        selectedPositions
            .mapNotNull { assets.getOrNull(it) }
            .filter { !it.isSessionHeader }  // session rows are never deletable
            .map { it.assetNumber }
            .toSet()

    val selectedCount: Int get() = selectedPositions.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect:      CheckBox = view.findViewById(R.id.cbSelect)
        val tvTime:        TextView = view.findViewById(R.id.tvLogTime)
        val tvAssetNumber: TextView = view.findViewById(R.id.tvLogAssetNumber)
        val tvType:        TextView = view.findViewById(R.id.tvLogType)
        val tvDetails:     TextView = view.findViewById(R.id.tvLogDetails)
        val viewDot:       View     = view.findViewById(R.id.viewStickerDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val asset = assets[position]

        holder.tvTime.textSize        = fontSizeSp
        holder.tvAssetNumber.textSize = fontSizeSp
        holder.tvType.textSize        = (fontSizeSp - 2f).coerceAtLeast(8f)
        holder.tvDetails.textSize     = (fontSizeSp - 1f).coerceAtLeast(8f)

        // ── Session header row ─────────────────────────────────────────────────
        if (asset.isSessionHeader) {
            holder.cbSelect.visibility = View.GONE
            holder.viewDot.visibility  = View.GONE
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.setOnClickListener(null)
            holder.itemView.isLongClickable = false

            val location = if (asset.department.isNotBlank()) "\n📍 ${asset.department}" else ""
            holder.tvTime.text = "[${asset.timestamp.ifEmpty { "--:--:--" }}]"
            holder.tvTime.setTextColor(Color.parseColor("#80CBC4"))
            holder.tvAssetNumber.text = "👤 ${asset.assetNumber}$location"
            holder.tvAssetNumber.isSingleLine = false
            holder.tvAssetNumber.setTextColor(Color.parseColor("#80CBC4"))
            holder.tvType.text = "SESSION"
            holder.tvType.setTextColor(Color.parseColor("#4DB6AC"))
            holder.tvDetails.visibility = View.GONE
            holder.itemView.setBackgroundColor(Color.parseColor("#0D1F1E"))
            return
        }

        // ── Normal scan row ────────────────────────────────────────────────────

        // Checkbox
        if (selectionMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked  = selectedPositions.contains(position)
        } else {
            holder.cbSelect.visibility = View.GONE
        }

        // Clicks
        holder.itemView.setOnLongClickListener {
            if (!selectionMode) enterSelectionMode(holder.adapterPosition)
            true
        }
        holder.itemView.setOnClickListener {
            if (selectionMode) toggleSelection(holder.adapterPosition)
        }

        // Content
        holder.tvTime.text = "[${asset.timestamp.ifEmpty { "--:--:--" }}]"
        holder.tvTime.setTextColor(Color.parseColor(COLOR_TIME))
        holder.tvAssetNumber.text = asset.assetNumber
        holder.tvAssetNumber.setTextColor(Color.parseColor(COLOR_ASSET))

        // Sticker dot
        val dotColor = when (asset.routinePeriod) {
            12   -> COLOR_STICKER_YELLOW
            24   -> COLOR_STICKER_WHITE
            60   -> COLOR_STICKER_GREEN
            else -> null
        }
        if (dotColor != null) {
            holder.viewDot.background.colorFilter =
                PorterDuffColorFilter(Color.parseColor(dotColor), PorterDuff.Mode.SRC_IN)
            holder.viewDot.visibility = View.VISIBLE
        } else {
            holder.viewDot.visibility = View.GONE
        }

        if (asset.isNew) {
            holder.tvType.text = "NEW"
            holder.tvType.setTextColor(Color.parseColor(COLOR_NEW))
            val details = listOf(asset.department, asset.make, asset.model, asset.serial)
                .filter { it.isNotBlank() }.joinToString(" │ ")
            if (details.isNotBlank()) {
                holder.tvDetails.text = details
                holder.tvDetails.setTextColor(Color.parseColor(COLOR_DEPT))
                holder.tvDetails.visibility = View.VISIBLE
            } else {
                holder.tvDetails.visibility = View.GONE
            }
        } else {
            holder.tvType.text = "FOUND"
            holder.tvType.setTextColor(Color.parseColor(COLOR_FOUND))
            if (asset.department.isNotBlank()) {
                holder.tvDetails.text = asset.department
                holder.tvDetails.setTextColor(Color.parseColor(COLOR_DEPT))
                holder.tvDetails.visibility = View.VISIBLE
            } else {
                holder.tvDetails.visibility = View.GONE
            }
        }

        holder.itemView.setBackgroundColor(Color.parseColor(
            when {
                selectedPositions.contains(position) -> COLOR_ROW_SELECTED
                position % 2 == 0                    -> COLOR_ROW_EVEN
                else                                 -> COLOR_ROW_ODD
            }
        ))
    }

    override fun getItemCount(): Int = assets.size

    fun addItem(asset: Asset) {
        assets.add(asset)
        notifyItemInserted(assets.size - 1)
    }

    fun recordCount(): Int = assets.count { !it.isSessionHeader }

    fun refreshFontSize() = notifyDataSetChanged()
}
