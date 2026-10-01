package com.autonoumus.ai.presentation.settings

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseActivity
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.ActivityMapTypeBinding
import com.autonoumus.ai.domain.model.MapTypeOption
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MapTypeActivity : BaseActivity<ActivityMapTypeBinding>() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    private var selectedMapType: MapTypeOption = MapTypeOption.HYBRID

    companion object {
        const val EXTRA_SELECTED_MAP_TYPE = "extra_selected_map_type"
    }

    override fun inflateBinding(inflater: LayoutInflater): ActivityMapTypeBinding {
        return ActivityMapTypeBinding.inflate(inflater)
    }

    override fun initViews() {
        loadCurrentMapType()
        setupToolbar()
        setupListeners()
        updateSelectionUi()
    }

    private fun loadCurrentMapType() {
        selectedMapType = try {
            MapTypeOption.valueOf(preferenceManager.mapType)
        } catch (e: IllegalArgumentException) {
            MapTypeOption.HYBRID
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        binding.cardMapNormal.setOnClickListener {
            selectedMapType = MapTypeOption.NORMAL
            updateSelectionUi()
        }

        binding.cardMapHybrid.setOnClickListener {
            selectedMapType = MapTypeOption.HYBRID
            updateSelectionUi()
        }

        binding.cardMapSatellite.setOnClickListener {
            selectedMapType = MapTypeOption.SATELLITE
            updateSelectionUi()
        }

        binding.cardMapTerrain.setOnClickListener {
            selectedMapType = MapTypeOption.TERRAIN
            updateSelectionUi()
        }

        binding.btnSave.setOnClickListener {
            preferenceManager.mapType = selectedMapType.name
            Toast.makeText(this, "Map Type set to ${selectedMapType.title}", Toast.LENGTH_SHORT).show()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_MAP_TYPE, selectedMapType.name)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun updateSelectionUi() {
        // Normal
        if (selectedMapType == MapTypeOption.NORMAL) {
            binding.cardMapNormal.setBackgroundResource(R.drawable.bg_map_card_selected)
            binding.tvMapNormal.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.cardMapNormal.setBackgroundResource(R.drawable.bg_map_card_unselected)
            binding.tvMapNormal.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        // Hybrid
        if (selectedMapType == MapTypeOption.HYBRID) {
            binding.cardMapHybrid.setBackgroundResource(R.drawable.bg_map_card_selected)
            binding.tvMapHybrid.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.cardMapHybrid.setBackgroundResource(R.drawable.bg_map_card_unselected)
            binding.tvMapHybrid.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        // Satellite
        if (selectedMapType == MapTypeOption.SATELLITE) {
            binding.cardMapSatellite.setBackgroundResource(R.drawable.bg_map_card_selected)
            binding.tvMapSatellite.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.cardMapSatellite.setBackgroundResource(R.drawable.bg_map_card_unselected)
            binding.tvMapSatellite.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        // Terrain
        if (selectedMapType == MapTypeOption.TERRAIN) {
            binding.cardMapTerrain.setBackgroundResource(R.drawable.bg_map_card_selected)
            binding.tvMapTerrain.setTextColor(ContextCompat.getColor(this, R.color.primary))
        } else {
            binding.cardMapTerrain.setBackgroundResource(R.drawable.bg_map_card_unselected)
            binding.tvMapTerrain.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }
}
