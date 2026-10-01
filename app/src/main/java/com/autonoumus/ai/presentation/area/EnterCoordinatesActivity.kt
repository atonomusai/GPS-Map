package com.autonoumus.ai.presentation.area

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.autonoumus.ai.R
import com.autonoumus.ai.databinding.ActivityEnterCoordinatesBinding
import org.osmdroid.util.GeoPoint
import java.util.ArrayList

class EnterCoordinatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnterCoordinatesBinding
    private val pointsList = ArrayList<GeoPoint>()
    private lateinit var adapter: AddedCoordinatesAdapter

    private var isDmsMode = false
    private var isLatNorth = true
    private var isLonEast = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnterCoordinatesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            windowInsets
        }

        // Retrieve existing points if passed
        val incomingPoints = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_POINTS, GeoPoint::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<GeoPoint>(EXTRA_POINTS)
        }
        if (incomingPoints != null) {
            pointsList.addAll(incomingPoints)
        }

        setupToolbar()
        setupTabs()
        setupDmsDirectionButtons()
        setupRecyclerView()
        setupActions()
        updateUiState()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener {
            if (pointsList.isNotEmpty()) {
                pointsList.clear()
                adapter.notifyDataSetChanged()
                updateUiState()
            }
        }
    }

    private fun setupTabs() {
        binding.tabDecimal.setOnClickListener {
            if (isDmsMode) {
                isDmsMode = false
                binding.tabDecimal.setBackgroundResource(R.drawable.bg_tab_selected)
                binding.tabDecimal.setTextColor(Color.WHITE)

                binding.tabDms.setBackgroundResource(android.R.color.transparent)
                binding.tabDms.setTextColor(Color.parseColor("#64748B"))

                binding.layoutDecimalInputs.visibility = View.VISIBLE
                binding.layoutDmsInputs.visibility = View.GONE
                adapter.setDmsMode(false)
            }
        }

        binding.tabDms.setOnClickListener {
            if (!isDmsMode) {
                isDmsMode = true
                binding.tabDms.setBackgroundResource(R.drawable.bg_tab_selected)
                binding.tabDms.setTextColor(Color.WHITE)

                binding.tabDecimal.setBackgroundResource(android.R.color.transparent)
                binding.tabDecimal.setTextColor(Color.parseColor("#64748B"))

                binding.layoutDecimalInputs.visibility = View.GONE
                binding.layoutDmsInputs.visibility = View.VISIBLE
                adapter.setDmsMode(true)
            }
        }
    }

    private fun setupDmsDirectionButtons() {
        binding.btnLatDir.setOnClickListener {
            isLatNorth = !isLatNorth
            binding.btnLatDir.text = if (isLatNorth) "N" else "S"
        }

        binding.btnLonDir.setOnClickListener {
            isLonEast = !isLonEast
            binding.btnLonDir.text = if (isLonEast) "E" else "W"
        }
    }

    private fun setupRecyclerView() {
        adapter = AddedCoordinatesAdapter(
            points = pointsList,
            isDmsMode = isDmsMode,
            onEditClick = { index, label, point ->
                showEditDialog(index, label, point)
            },
            onDeleteClick = { index, label ->
                showDeleteDialog(index, label)
            }
        )

        binding.rvAddedPoints.layoutManager = LinearLayoutManager(this)
        binding.rvAddedPoints.adapter = adapter
    }

    private fun setupActions() {
        binding.btnAddPoint.setOnClickListener {
            addCoordinatePoint()
        }

        binding.btnCalculateArea.setOnClickListener {
            if (pointsList.size < 3) {
                Toast.makeText(this, "Please add at least 3 points to calculate area", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val resultIntent = Intent().apply {
                putParcelableArrayListExtra(EXTRA_POINTS, pointsList)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun addCoordinatePoint() {
        var geoPoint: GeoPoint? = null

        if (!isDmsMode) {
            // Decimal Degrees Mode
            val latStr = binding.etDecimalLatitude.text.toString().trim()
            val lonStr = binding.etDecimalLongitude.text.toString().trim()

            var isValid = true

            val lat = latStr.toDoubleOrNull()
            if (lat == null || lat < -90.0 || lat > 90.0) {
                binding.tvDecimalLatError.text = "Enter a valid latitude (-90° to 90°)"
                binding.tvDecimalLatError.visibility = View.VISIBLE
                binding.etDecimalLatitude.setBackgroundResource(R.drawable.bg_input_field_error)
                isValid = false
            } else {
                binding.tvDecimalLatError.visibility = View.GONE
                binding.etDecimalLatitude.setBackgroundResource(R.drawable.bg_input_field)
            }

            val lon = lonStr.toDoubleOrNull()
            if (lon == null || lon < -180.0 || lon > 180.0) {
                binding.tvDecimalLonError.text = "Enter a valid longitude (-180° to 180°)"
                binding.tvDecimalLonError.visibility = View.VISIBLE
                binding.etDecimalLongitude.setBackgroundResource(R.drawable.bg_input_field_error)
                isValid = false
            } else {
                binding.tvDecimalLonError.visibility = View.GONE
                binding.etDecimalLongitude.setBackgroundResource(R.drawable.bg_input_field)
            }

            if (isValid && lat != null && lon != null) {
                geoPoint = GeoPoint(lat, lon)
                binding.etDecimalLatitude.text.clear()
                binding.etDecimalLongitude.text.clear()
            }
        } else {
            // DMS Mode
            val latDeg = binding.etDmsLatDeg.text.toString().trim().toDoubleOrNull()
            val latMin = binding.etDmsLatMin.text.toString().trim().toDoubleOrNull() ?: 0.0
            val latSec = binding.etDmsLatSec.text.toString().trim().toDoubleOrNull() ?: 0.0

            val lonDeg = binding.etDmsLonDeg.text.toString().trim().toDoubleOrNull()
            val lonMin = binding.etDmsLonMin.text.toString().trim().toDoubleOrNull() ?: 0.0
            val lonSec = binding.etDmsLonSec.text.toString().trim().toDoubleOrNull() ?: 0.0

            if (latDeg == null || latDeg < 0 || latDeg > 90 || latMin < 0 || latMin >= 60 || latSec < 0 || latSec >= 60) {
                Toast.makeText(this, "Please enter valid latitude DMS values", Toast.LENGTH_SHORT).show()
                return
            }

            if (lonDeg == null || lonDeg < 0 || lonDeg > 180 || lonMin < 0 || lonMin >= 60 || lonSec < 0 || lonSec >= 60) {
                Toast.makeText(this, "Please enter valid longitude DMS values", Toast.LENGTH_SHORT).show()
                return
            }

            var finalLat = latDeg + (latMin / 60.0) + (latSec / 3600.0)
            if (!isLatNorth) finalLat = -finalLat

            var finalLon = lonDeg + (lonMin / 60.0) + (lonSec / 3600.0)
            if (!isLonEast) finalLon = -finalLon

            geoPoint = GeoPoint(finalLat, finalLon)

            binding.etDmsLatDeg.text.clear()
            binding.etDmsLatMin.text.clear()
            binding.etDmsLatSec.text.clear()
            binding.etDmsLonDeg.text.clear()
            binding.etDmsLonMin.text.clear()
            binding.etDmsLonSec.text.clear()
        }

        if (geoPoint != null) {
            pointsList.add(geoPoint)
            adapter.notifyItemInserted(pointsList.size - 1)
            updateUiState()
        }
    }

    private fun showEditDialog(index: Int, label: String, point: GeoPoint) {
        val dialog = EditCoordinateBottomSheetDialog.newInstance(index, label, point)
        dialog.onCoordinateUpdated = { updateIndex, updatedPoint ->
            pointsList[updateIndex] = updatedPoint
            adapter.notifyItemChanged(updateIndex)
            updateUiState()
        }
        dialog.show(supportFragmentManager, EditCoordinateBottomSheetDialog.TAG)
    }

    private fun showDeleteDialog(index: Int, label: String) {
        val dialog = DeleteCoordinateBottomSheetDialog.newInstance(index, label)
        dialog.onCoordinateDeleted = { deleteIndex ->
            pointsList.removeAt(deleteIndex)
            adapter.notifyDataSetChanged()
            updateUiState()
        }
        dialog.show(supportFragmentManager, DeleteCoordinateBottomSheetDialog.TAG)
    }

    private fun updateUiState() {
        val count = pointsList.size
        binding.tvPointsHeader.text = "Added Points ($count)"
        binding.btnClearAll.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.tvEmptyState.visibility = if (count == 0) View.VISIBLE else View.GONE

        if (count >= 3) {
            binding.tvCalculateBtnText.text = "Calculate Area ($count Points)"
            binding.btnCalculateArea.alpha = 1.0f
        } else {
            binding.tvCalculateBtnText.text = "Calculate Area (Need ${3 - count} more)"
            binding.btnCalculateArea.alpha = 0.7f
        }
    }

    companion object {
        const val EXTRA_POINTS = "extra_points"

        fun createIntent(context: Context, existingPoints: ArrayList<GeoPoint>? = null): Intent {
            return Intent(context, EnterCoordinatesActivity::class.java).apply {
                if (existingPoints != null) {
                    putParcelableArrayListExtra(EXTRA_POINTS, existingPoints)
                }
            }
        }
    }
}
