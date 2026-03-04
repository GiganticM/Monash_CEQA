package org.monash.ceqa

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        private const val COLOR_TIME     = "#66BB6A"
        private const val COLOR_ASSET    = "#00E676"
        private const val COLOR_FOUND    = "#69F0AE"
        private const val COLOR_NEW      = "#40C4FF"
        private const val COLOR_DEPT     = "#2E7D32"
        private const val COLOR_ROW_EVEN = "#0B0E0B"
        private const val COLOR_ROW_ODD  = "#0D120D"
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime:        TextView = view.findViewById(R.id.tvLogTime)
        val tvAssetNumber: TextView = view.findViewById(R.id.tvLogAssetNumber)
        val tvType:        TextView = view.findViewById(R.id.tvLogType)
        val tvDetails:     TextView = view.findViewById(R.id.tvLogDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val asset = assets[position]

        // Apply font sizes — details line is slightly smaller than primary
        holder.tvTime.textSize        = fontSizeSp
        holder.tvAssetNumber.textSize = fontSizeSp
        holder.tvType.textSize        = (fontSizeSp - 2f).coerceAtLeast(8f)
        holder.tvDetails.textSize     = (fontSizeSp - 1f).coerceAtLeast(8f)

        holder.tvTime.text = "[${asset.timestamp.ifEmpty { "--:--:--" }}]"
        holder.tvTime.setTextColor(Color.parseColor(COLOR_TIME))

        holder.tvAssetNumber.text = asset.assetNumber
        holder.tvAssetNumber.setTextColor(Color.parseColor(COLOR_ASSET))

        if (asset.isNew) {
            holder.tvType.text = "NEW"
            holder.tvType.setTextColor(Color.parseColor(COLOR_NEW))

            val details = listOf(asset.department, asset.make, asset.model, asset.serial)
                .filter { it.isNotBlank() }
                .joinToString(" │ ")


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

        holder.itemView.setBackgroundColor(
            Color.parseColor(if (position % 2 == 0) COLOR_ROW_EVEN else COLOR_ROW_ODD)
        )
    }

    override fun getItemCount(): Int = assets.size

    fun addItem(asset: Asset) {
        assets.add(asset)
        notifyItemInserted(assets.size - 1)
    }

    fun recordCount(): Int = assets.size

    /** Call after changing [fontSizeSp] to refresh all visible rows. */
    fun refreshFontSize() = notifyDataSetChanged()
}
