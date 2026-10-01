package com.autonoumus.ai.presentation.speedometer.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ItemSpeedometerTripBinding
import com.autonoumus.ai.databinding.ItemTripHistoryBinding
import com.autonoumus.ai.domain.model.TripRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TripHistoryAdapter(
    private var isSpeedUnitKmh: Boolean = true,
    private val onItemClick: (TripRecord) -> Unit = {}
) : ListAdapter<TripRecord, RecyclerView.ViewHolder>(TripDiffCallback()) {

    companion object {
        const val VIEW_TYPE_SPEEDOMETER = 1
        const val VIEW_TYPE_ROUTE_FINDER = 2
    }

    fun setUnit(isKmh: Boolean) {
        if (this.isSpeedUnitKmh != isKmh) {
            this.isSpeedUnitKmh = isKmh
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.tripSource == TripRecord.TRIP_SOURCE_ROUTE_FINDER) {
            VIEW_TYPE_ROUTE_FINDER
        } else {
            VIEW_TYPE_SPEEDOMETER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_ROUTE_FINDER) {
            val binding = ItemTripHistoryBinding.inflate(inflater, parent, false)
            RouteFinderViewHolder(binding)
        } else {
            val binding = ItemSpeedometerTripBinding.inflate(inflater, parent, false)
            SpeedometerViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val tripNumber = itemCount - position
        when (holder) {
            is SpeedometerViewHolder -> holder.bind(item, tripNumber)
            is RouteFinderViewHolder -> holder.bind(item)
        }
    }

    inner class SpeedometerViewHolder(
        private val binding: ItemSpeedometerTripBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TripRecord, tripNumber: Int) {
            // 1. Title: Trip #24 — Main Highway / Location
            val locationName = when {
                item.endAddress.isNotBlank() && item.endAddress != "Destination" && item.endAddress != "N/A" -> item.endAddress
                item.startAddress.isNotBlank() && item.startAddress != "Start Location" && item.startAddress != "N/A" -> item.startAddress
                item.topSpeedLocation.isNotBlank() -> item.topSpeedLocation
                else -> "Speedometer Trip"
            }
            binding.tvTripTitle.text = "Trip #$tripNumber — $locationName"

            // 2. Date: 19 Sep 2026 14:32
            val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
            binding.tvTripDate.text = dateFormat.format(Date(item.timestamp))

            // 3. Distance
            val distanceConverted = if (isSpeedUnitKmh) {
                item.distanceMeters / 1000.0
            } else {
                (item.distanceMeters / 1000.0) * 0.621371
            }
            val distUnit = if (isSpeedUnitKmh) "km" else "mi"
            binding.tvDistance.text = String.format(Locale.US, "%.1f %s", distanceConverted, distUnit)

            // 4. Duration: 00:42:15
            val hrs = item.durationSec / 3600
            val mins = (item.durationSec % 3600) / 60
            val secs = item.durationSec % 60
            binding.tvDuration.text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)

            // 5. Avg Speed
            val avgSpeed = if (isSpeedUnitKmh) item.avgSpeedKmh else item.avgSpeedKmh * 0.621371f
            val speedUnit = if (isSpeedUnitKmh) "km/h" else "mph"
            binding.tvAvgSpeed.text = String.format(Locale.US, "%.1f %s", avgSpeed, speedUnit)

            // 6. Max Speed
            val maxSpeed = if (isSpeedUnitKmh) item.maxSpeedKmh else item.maxSpeedKmh * 0.621371f
            binding.tvMaxSpeed.text = String.format(Locale.US, "Max Speed: %.1f %s", maxSpeed, speedUnit)

            // 7. Status Badge & Left Accent Strip (Factual Telemetry)
            val isOverSpeed: Boolean
            val isWarning: Boolean

            if (item.safePercent > 0 || item.warningPercent > 0 || item.overSpeedPercent > 0) {
                if (item.overSpeedPercent > 0 || item.limitExceededCount > 0) {
                    if (item.overSpeedPercent >= 5 || item.limitExceededCount > 0 || item.overSpeedPercent >= item.warningPercent) {
                        isOverSpeed = true
                        isWarning = false
                    } else {
                        isOverSpeed = false
                        isWarning = true
                    }
                } else if (item.warningPercent > 0) {
                    isOverSpeed = false
                    isWarning = true
                } else {
                    isOverSpeed = false
                    isWarning = false
                }
            } else {
                if (item.limitExceededCount > 0) {
                    isOverSpeed = true
                    isWarning = false
                } else if (item.maxSpeedKmh >= 90f) {
                    isOverSpeed = true
                    isWarning = false
                } else if (item.maxSpeedKmh >= 60f) {
                    isOverSpeed = false
                    isWarning = true
                } else {
                    isOverSpeed = false
                    isWarning = false
                }
            }

            when {
                isOverSpeed -> {
                    binding.viewStatusStrip.setBackgroundResource(R.drawable.bg_status_strip_left_red)
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_overspeed)
                    binding.tvStatusBadge.setTextColor(Color.parseColor("#DC2626"))
                    binding.tvStatusBadge.text = "Over Speed"
                }
                isWarning -> {
                    binding.viewStatusStrip.setBackgroundResource(R.drawable.bg_status_strip_left_amber)
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
                    binding.tvStatusBadge.setTextColor(Color.parseColor("#D97706"))
                    binding.tvStatusBadge.text = "Warning"
                }
                else -> {
                    binding.viewStatusStrip.setBackgroundResource(R.drawable.bg_status_strip_left_green)
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_badge_safe)
                    binding.tvStatusBadge.setTextColor(Color.parseColor("#16A34A"))
                    binding.tvStatusBadge.text = "Safe"
                }
            }

            // Click listener
            binding.cardSpeedometerTrip.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    inner class RouteFinderViewHolder(
        private val binding: ItemTripHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TripRecord) {
            binding.tvTripDateBadge.text = formatTripDateBadge(item.timestamp)
            binding.tvTripSourceBadge.text = "🧭 ROUTE FINDER"
            binding.tvTripSourceBadge.setTextColor(Color.parseColor("#4F46E5"))
            binding.tvTripSourceBadge.setBackgroundResource(R.drawable.bg_nearby_badge)

            // Duration
            binding.tvItemTotalTime.text = formatDuration(item.durationSec)

            // Distance
            val distanceConverted = if (isSpeedUnitKmh) {
                item.distanceMeters / 1000.0
            } else {
                (item.distanceMeters / 1000.0) * 0.621371
            }
            val distUnit = if (isSpeedUnitKmh) "km" else "mi"
            binding.tvItemTotalDistance.text = String.format(Locale.US, "%.1f %s", distanceConverted, distUnit)

            // Average Speed
            val avgSpeed = if (isSpeedUnitKmh) item.avgSpeedKmh else item.avgSpeedKmh * 0.621371f
            val speedUnit = if (isSpeedUnitKmh) "km/h" else "mph"
            binding.tvItemAvgSpeed.text = String.format(Locale.US, "%d %s", avgSpeed.toInt(), speedUnit)

            // Addresses
            binding.tvItemStartAddress.text = item.startAddress.uppercase(Locale.US)
            binding.tvItemEndAddress.text = item.endAddress.uppercase(Locale.US)

            binding.cardTripContainer.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatDuration(seconds: Long): String {
            return when {
                seconds < 60 -> "$seconds sec"
                seconds < 3600 -> {
                    val mins = seconds / 60
                    val secs = seconds % 60
                    if (secs > 0) "$mins min $secs s" else "$mins min"
                }
                else -> {
                    val hrs = seconds / 3600
                    val mins = (seconds % 3600) / 60
                    "${hrs}h ${mins}m"
                }
            }
        }

        private fun formatTripDateBadge(timestampMs: Long): String {
            val tripDate = Date(timestampMs)
            val nowCal = Calendar.getInstance()
            val tripCal = Calendar.getInstance().apply { time = tripDate }

            val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
            val formattedTime = timeFormat.format(tripDate)

            val isToday = nowCal.get(Calendar.YEAR) == tripCal.get(Calendar.YEAR) &&
                    nowCal.get(Calendar.DAY_OF_YEAR) == tripCal.get(Calendar.DAY_OF_YEAR)

            val isYesterday = nowCal.get(Calendar.YEAR) == tripCal.get(Calendar.YEAR) &&
                    nowCal.get(Calendar.DAY_OF_YEAR) - tripCal.get(Calendar.DAY_OF_YEAR) == 1

            return when {
                isToday -> "TODAY, $formattedTime"
                isYesterday -> "YESTERDAY, $formattedTime"
                else -> {
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                    "${dateFormat.format(tripDate).uppercase(Locale.US)}, $formattedTime"
                }
            }
        }
    }

    class TripDiffCallback : DiffUtil.ItemCallback<TripRecord>() {
        override fun areItemsTheSame(oldItem: TripRecord, newItem: TripRecord): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TripRecord, newItem: TripRecord): Boolean {
            return oldItem == newItem
        }
    }
}
