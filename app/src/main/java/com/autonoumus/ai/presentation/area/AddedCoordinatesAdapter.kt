package com.autonoumus.ai.presentation.area

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.core.util.AreaCalculator
import com.autonoumus.ai.databinding.ItemAddedCoordinateBinding
import org.osmdroid.util.GeoPoint
import java.util.Locale

class AddedCoordinatesAdapter(
    private val points: MutableList<GeoPoint>,
    private var isDmsMode: Boolean = false,
    private val onEditClick: (Int, String, GeoPoint) -> Unit,
    private val onDeleteClick: (Int, String) -> Unit
) : RecyclerView.Adapter<AddedCoordinatesAdapter.CoordinateViewHolder>() {

    class CoordinateViewHolder(val binding: ItemAddedCoordinateBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoordinateViewHolder {
        val binding = ItemAddedCoordinateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CoordinateViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CoordinateViewHolder, position: Int) {
        val point = points[position]
        val label = getPointLabel(position)

        holder.binding.tvPointBadge.text = label
        holder.binding.tvPointTitle.text = "Point $label"

        if (isDmsMode) {
            val (latDms, lonDms) = AreaCalculator.convertDecimalToDms(point.latitude, point.longitude)
            holder.binding.tvPointCoordinates.text = "$latDms, $lonDms"
        } else {
            val latStr = String.format(Locale.US, "%.5f° %s", Math.abs(point.latitude), if (point.latitude >= 0) "N" else "S")
            val lonStr = String.format(Locale.US, "%.5f° %s", Math.abs(point.longitude), if (point.longitude >= 0) "E" else "W")
            holder.binding.tvPointCoordinates.text = "$latStr, $lonStr"
        }

        holder.binding.btnEditPoint.setOnClickListener {
            onEditClick(position, label, point)
        }

        holder.binding.btnDeletePoint.setOnClickListener {
            onDeleteClick(position, label)
        }
    }

    override fun getItemCount(): Int = points.size

    fun setDmsMode(dms: Boolean) {
        if (isDmsMode != dms) {
            isDmsMode = dms
            notifyDataSetChanged()
        }
    }

    companion object {
        fun getPointLabel(index: Int): String {
            return if (index < 26) {
                ('A' + index).toString()
            } else {
                "P${index + 1}"
            }
        }
    }
}
