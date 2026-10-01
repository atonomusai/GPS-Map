package com.autonoumus.ai.presentation.templates

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.autonoumus.ai.R
import com.autonoumus.ai.core.util.LocationHelper
import com.autonoumus.ai.core.util.OsmGeocodingHelper
import com.autonoumus.ai.core.util.WorldLocationProvider
import com.autonoumus.ai.data.local.PreferenceManager
import com.autonoumus.ai.databinding.LayoutBottomSheetCustomLocationBinding
import com.autonoumus.ai.presentation.map.PickLocationMapActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.autonoumus.ai.domain.model.SavedLocationTemplate
import com.autonoumus.ai.domain.repository.SavedLocationTemplateRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CustomLocationStampBottomSheetDialog : BottomSheetDialogFragment() {

    @Inject
    lateinit var preferenceManager: PreferenceManager

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var savedLocationTemplateRepository: SavedLocationTemplateRepository

    private var _binding: LayoutBottomSheetCustomLocationBinding? = null
    private val binding get() = _binding!!

    private var countryAdapter: LocationTileAdapter? = null
    private var cityAdapter: LocationTileAdapter? = null

    var onLocationApplied: ((country: String, address: String, lat: String, lon: String) -> Unit)? = null
    var onTemplateSaved: ((SavedLocationTemplate) -> Unit)? = null
    var isSaveOnlyMode: Boolean = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fillFromLiveGps()
        } else {
            context?.let { ctx ->
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                com.autonoumus.ai.core.util.PermissionSettingsDialog.handleDenied(
                    context = ctx,
                    shouldShowRationale = shouldShowRationale,
                    deniedMessage = "Location permission is required to fill your current GPS position"
                )
            }
        }
    }

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val country = data.getStringExtra(PickLocationMapActivity.EXTRA_COUNTRY).orEmpty()
            val address = data.getStringExtra(PickLocationMapActivity.EXTRA_ADDRESS).orEmpty()
            val hasRawLat = data.hasExtra(PickLocationMapActivity.EXTRA_RAW_LAT)
            val hasRawLon = data.hasExtra(PickLocationMapActivity.EXTRA_RAW_LON)
            val latFormatted = if (hasRawLat) {
                OsmGeocodingHelper.formatLatitude(data.getDoubleExtra(PickLocationMapActivity.EXTRA_RAW_LAT, 0.0), decimals = 6)
            } else {
                data.getStringExtra(PickLocationMapActivity.EXTRA_LAT_FORMATTED).orEmpty()
            }
            val lonFormatted = if (hasRawLon) {
                OsmGeocodingHelper.formatLongitude(data.getDoubleExtra(PickLocationMapActivity.EXTRA_RAW_LON, 0.0), decimals = 6)
            } else {
                data.getStringExtra(PickLocationMapActivity.EXTRA_LON_FORMATTED).orEmpty()
            }

            if (country.isNotBlank()) {
                binding.actvCountryRegion.setText(country, false)
                clearCountryError()
                cityAdapter?.updateCountryFilter(country)
                updateCityFieldEnabledState()
            }
            if (address.isNotBlank()) {
                binding.actvCityAddress.setText(address, false)
            }
            if (latFormatted.isNotBlank()) {
                binding.etLatitude.setText(latFormatted)
            }
            if (lonFormatted.isNotBlank()) {
                binding.etLongitude.setText(lonFormatted)
            }

            validateFields()
            Toast.makeText(requireContext(), "Location updated from OpenStreetMap", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "CustomLocationStampBottomSheetDialog"
        private const val ARG_COUNTRY = "arg_country"
        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"

        fun newInstance(
            initialCountry: String? = null,
            initialAddress: String? = null,
            initialLat: String? = null,
            initialLon: String? = null
        ): CustomLocationStampBottomSheetDialog {
            return CustomLocationStampBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    initialCountry?.let { putString(ARG_COUNTRY, it) }
                    initialAddress?.let { putString(ARG_ADDRESS, it) }
                    initialLat?.let { putString(ARG_LAT, it) }
                    initialLon?.let { putString(ARG_LON, it) }
                }
            }
        }
    }

    override fun getTheme(): Int = R.style.Theme_GSMCAMER_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetCustomLocationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let { sheet ->
                sheet.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_bottom_sheet_dialog)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.findViewById<View>(com.google.android.material.R.id.container)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            window.findViewById<View>(com.google.android.material.R.id.coordinator)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            window.findViewById<View>(com.google.android.material.R.id.touch_outside)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCountryAutocomplete()
        setupCityAutocomplete()
        setupSavedData()
        setupListeners()
        updateCityFieldEnabledState()
        validateFields()
    }

    private fun isCountryProvided(): Boolean {
        return !binding.actvCountryRegion.text.isNullOrBlank()
    }

    private fun isCountryValid(): Boolean {
        val country = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()
        return WorldLocationProvider.isCountryValid(country)
    }

    private fun showCountryRequiredError(message: String = "Please select a country from the search list") {
        binding.layoutCountryContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input_error)
        binding.tvCountryError.text = message
        binding.tvCountryError.visibility = View.VISIBLE
        binding.actvCountryRegion.requestFocus()
        binding.actvCountryRegion.showDropDown()
    }

    private fun clearCountryError() {
        binding.actvCountryRegion.error = null
        binding.layoutCountryContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input)
        binding.tvCountryError.visibility = View.GONE
    }

    private fun showCoordinatesError(message: String) {
        binding.layoutLatContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input_error)
        binding.layoutLonContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input_error)
        binding.tvCoordinatesError.text = message
        binding.tvCoordinatesError.visibility = View.VISIBLE
    }

    private fun clearCoordinatesError() {
        binding.layoutLatContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input)
        binding.layoutLonContainer.setBackgroundResource(R.drawable.bg_custom_stamp_input)
        binding.tvCoordinatesError.visibility = View.GONE
    }

    private fun updateCityFieldEnabledState() {
        val hasCountry = isCountryProvided()
        val isValidCountry = isCountryValid()
        val country = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()

        if (hasCountry && isValidCountry) {
            binding.layoutCityContainer.alpha = 1.0f
            binding.actvCityAddress.isEnabled = true
            binding.actvCityAddress.isFocusable = true
            binding.actvCityAddress.isFocusableInTouchMode = true
            binding.layoutCityContainer.setOnClickListener(null)
            cityAdapter?.updateCountryFilter(country)
        } else {
            binding.layoutCityContainer.alpha = 0.45f
            binding.actvCityAddress.isEnabled = false
            binding.actvCityAddress.isFocusable = false
            binding.actvCityAddress.isFocusableInTouchMode = false
            binding.layoutCityContainer.setOnClickListener {
                if (!hasCountry) {
                    showCountryRequiredError("Please select a country from the search list")
                } else {
                    showCountryRequiredError("Please choose a valid country from our search list")
                }
            }
            cityAdapter?.updateCountryFilter(null)
        }
    }

    private fun setupCountryAutocomplete() {
        val context = requireContext()
        val adapter = LocationTileAdapter(context, isCityMode = false)
        countryAdapter = adapter
        binding.actvCountryRegion.setAdapter(adapter)
        binding.actvCountryRegion.setDropDownBackgroundResource(R.drawable.bg_popup_dropdown_tile)
        binding.actvCountryRegion.setDropDownAnchor(binding.layoutCountryContainer.id)
        val offsetPx = (4 * resources.displayMetrics.density).toInt()
        binding.actvCountryRegion.dropDownVerticalOffset = offsetPx
        binding.actvCountryRegion.dropDownHorizontalOffset = 0

        binding.actvCountryRegion.setOnClickListener {
            clearCountryError()
            binding.actvCountryRegion.showDropDown()
        }

        binding.actvCountryRegion.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                clearCountryError()
                binding.actvCountryRegion.showDropDown()
            } else {
                val currentText = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()
                if (currentText.isNotEmpty() && !WorldLocationProvider.isCountryValid(currentText)) {
                    showCountryRequiredError("Please select a valid country from the search list")
                }
            }
        }

        binding.actvCountryRegion.doAfterTextChanged { editable ->
            val countryText = editable?.toString()?.trim().orEmpty()
            if (countryText.isNotEmpty() && WorldLocationProvider.isCountryValid(countryText)) {
                clearCountryError()
            }
            updateCityFieldEnabledState()
            validateFields()
        }

        binding.actvCountryRegion.setOnItemClickListener { _, _, position, _ ->
            val suggestion = adapter.getItem(position) ?: return@setOnItemClickListener
            val selectedCountry = suggestion.country.ifBlank { suggestion.primaryTitle }
            binding.actvCountryRegion.setText(selectedCountry, false)
            clearCountryError()

            // Filter city adapter specifically for this selected country
            cityAdapter?.updateCountryFilter(selectedCountry)
            updateCityFieldEnabledState()

            val currentAddress = binding.actvCityAddress.text?.toString()?.trim().orEmpty()
            val belongsToCountry = WorldLocationProvider.searchCities(currentAddress, selectedCountry, requireContext()).isNotEmpty()
            if (!belongsToCountry || currentAddress.isEmpty()) {
                binding.actvCityAddress.setText("", false)
            }

            if (suggestion.latitudeFormatted.isNotBlank() && suggestion.latitudeFormatted != "0.0000° N") {
                binding.etLatitude.setText(suggestion.latitudeFormatted)
                binding.etLongitude.setText(suggestion.longitudeFormatted)
                clearCoordinatesError()
            }

            validateFields()

            // Move focus to city field and pop open city suggestions for this country
            binding.actvCityAddress.requestFocus()
            binding.actvCityAddress.post {
                if (_binding != null) {
                    cityAdapter?.updateCountryFilter(selectedCountry)
                    cityAdapter?.filter?.filter("")
                    binding.actvCityAddress.showDropDown()
                }
            }
        }
    }

    private fun setupCityAutocomplete() {
        val context = requireContext()
        val currentCountry = binding.actvCountryRegion.text?.toString()?.trim()
        val adapter = LocationTileAdapter(context, isCityMode = true, countryFilter = currentCountry)
        cityAdapter = adapter
        binding.actvCityAddress.setAdapter(adapter)
        binding.actvCityAddress.setDropDownBackgroundResource(R.drawable.bg_popup_dropdown_tile)
        binding.actvCityAddress.setDropDownAnchor(binding.layoutCityContainer.id)
        val offsetPx = (4 * resources.displayMetrics.density).toInt()
        binding.actvCityAddress.dropDownVerticalOffset = offsetPx
        binding.actvCityAddress.dropDownHorizontalOffset = 0

        // Intercept click: require country first
        binding.actvCityAddress.setOnClickListener {
            if (!isCountryProvided()) {
                showCountryRequiredError("Please select a country from the search list first")
            } else if (!isCountryValid()) {
                showCountryRequiredError("Please choose a valid country from our search list")
            } else {
                clearCountryError()
                val country = binding.actvCountryRegion.text.toString().trim()
                cityAdapter?.updateCountryFilter(country)
                cityAdapter?.filter?.filter(binding.actvCityAddress.text)
                binding.actvCityAddress.showDropDown()
            }
        }

        // Intercept focus: require country first
        binding.actvCityAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!isCountryProvided()) {
                    showCountryRequiredError("Please select a country from the search list first")
                    binding.actvCityAddress.clearFocus()
                } else if (!isCountryValid()) {
                    showCountryRequiredError("Please choose a valid country from our search list")
                    binding.actvCityAddress.clearFocus()
                } else {
                    clearCountryError()
                    val country = binding.actvCountryRegion.text.toString().trim()
                    cityAdapter?.updateCountryFilter(country)
                    cityAdapter?.filter?.filter(binding.actvCityAddress.text)
                    binding.actvCityAddress.showDropDown()
                }
            }
        }

        binding.actvCityAddress.setOnItemClickListener { _, _, position, _ ->
            val suggestion = adapter.getItem(position) ?: return@setOnItemClickListener
            binding.actvCityAddress.setText(suggestion.address, false)

            if (binding.actvCountryRegion.text.isNullOrBlank() && suggestion.country.isNotBlank()) {
                binding.actvCountryRegion.setText(suggestion.country, false)
                clearCountryError()
                cityAdapter?.updateCountryFilter(suggestion.country)
                updateCityFieldEnabledState()
            }

            if (suggestion.latitudeFormatted.isNotBlank() && suggestion.latitudeFormatted != "0.0000° N") {
                binding.etLatitude.setText(suggestion.latitudeFormatted)
                binding.etLongitude.setText(suggestion.longitudeFormatted)
                clearCoordinatesError()
            }

            validateFields()
        }
    }

    private fun setupSavedData() {
        val argCountry = arguments?.getString(ARG_COUNTRY)?.trim()
        val argAddress = arguments?.getString(ARG_ADDRESS)?.trim()
        val argLat = arguments?.getString(ARG_LAT)?.trim()
        val argLon = arguments?.getString(ARG_LON)?.trim()

        if (!argCountry.isNullOrBlank()) {
            val normalized = WorldLocationProvider.normalizeCountry(argCountry)
            val displayCountry = if (WorldLocationProvider.isCountryValid(normalized)) normalized else argCountry
            binding.actvCountryRegion.setText(displayCountry, false)
            clearCountryError()
            cityAdapter?.updateCountryFilter(displayCountry)
        }
        if (!argAddress.isNullOrBlank()) {
            binding.actvCityAddress.setText(argAddress, false)
        }
        if (!argLat.isNullOrBlank()) {
            binding.etLatitude.setText(argLat)
        }
        if (!argLon.isNullOrBlank()) {
            binding.etLongitude.setText(argLon)
        }

        updateCityFieldEnabledState()
    }

    private fun setupListeners() {
        binding.actvCountryRegion.doAfterTextChanged { validateFields() }
        binding.actvCityAddress.doAfterTextChanged { validateFields() }
        binding.etLatitude.doAfterTextChanged {
            checkCoordinatesValiditySilently()
            validateFields()
        }
        binding.etLongitude.doAfterTextChanged {
            checkCoordinatesValiditySilently()
            validateFields()
        }

        // GPS Target Reticle quick preset -> fills accurate live device GPS location
        binding.btnTargetGps.setOnClickListener {
            if (locationHelper.hasLocationPermission()) {
                fillFromLiveGps()
            } else {
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                if (preferenceManager.hasRequestedLocationPermission && !shouldShowRationale) {
                    context?.let { ctx ->
                        com.autonoumus.ai.core.util.PermissionSettingsDialog.show(
                            context = ctx,
                            message = "Location permission is disabled. Please enable it in Settings to fill your current GPS position."
                        )
                    }
                } else {
                    preferenceManager.hasRequestedLocationPermission = true
                    locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
        }

        // Pick exactly on map card -> launches OpenStreetMap Picker
        binding.cardPickOnMap.setOnClickListener {
            val intent = Intent(requireContext(), PickLocationMapActivity::class.java)
            val currentLat = parseCoordinateToDouble(binding.etLatitude.text?.toString())
            val currentLon = parseCoordinateToDouble(binding.etLongitude.text?.toString())
            if (currentLat != null && currentLon != null) {
                intent.putExtra(PickLocationMapActivity.EXTRA_INITIAL_LAT, currentLat)
                intent.putExtra(PickLocationMapActivity.EXTRA_INITIAL_LON, currentLon)
            }
            mapPickerLauncher.launch(intent)
        }

        binding.tvOpenMap.setOnClickListener {
            binding.cardPickOnMap.performClick()
        }

        // Customize / Save Template button (validates all fields including country search-list check and boundary validation)
        binding.btnCustomizeTemplate.setOnClickListener {
            val country = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()
            if (country.isEmpty()) {
                showCountryRequiredError("Please select a country from the search list")
                Toast.makeText(requireContext(), "Please select a country from our search list", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!WorldLocationProvider.isCountryValid(country)) {
                showCountryRequiredError("Please select a valid country from the search list")
                Toast.makeText(requireContext(), "Country must be selected from our worldwide search list", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val address = binding.actvCityAddress.text?.toString()?.trim().orEmpty()
            if (address.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter city and street address", Toast.LENGTH_SHORT).show()
                binding.actvCityAddress.requestFocus()
                return@setOnClickListener
            }

            val latStr = binding.etLatitude.text?.toString()?.trim().orEmpty()
            val lonStr = binding.etLongitude.text?.toString()?.trim().orEmpty()
            val lat = parseCoordinateToDouble(latStr)
            val lon = parseCoordinateToDouble(lonStr)

            if (lat == null || lon == null) {
                showCoordinatesError("Please enter valid numeric latitude and longitude")
                Toast.makeText(requireContext(), "Please enter valid latitude and longitude", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Strict Country Boundary Validation
            val isWithinBoundary = WorldLocationProvider.isLocationWithinCountry(lat, lon, country, context)
            if (!isWithinBoundary) {
                showCoordinatesError("Coordinates are outside the boundary of $country. Please enter correct lat/long.")
                Toast.makeText(
                    requireContext(),
                    "Please enter correct latitude and longitude according to $country",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            clearCountryError()
            clearCoordinatesError()

            val savedTemplate = SavedLocationTemplate(
                id = java.util.UUID.randomUUID().toString(),
                name = country,
                country = country,
                address = address,
                latitude = latStr,
                longitude = lonStr,
                templateName = preferenceManager.stampTemplate,
                createdAt = System.currentTimeMillis()
            )

            lifecycleScope.launch {
                savedLocationTemplateRepository.saveTemplate(savedTemplate)
                if (!isSaveOnlyMode) {
                    preferenceManager.isCustomLocationOverrideEnabled = true
                    preferenceManager.customLocationCountry = country
                    preferenceManager.customLocationAddress = address
                    preferenceManager.customLocationLatitude = latStr
                    preferenceManager.customLocationLongitude = lonStr
                }
                Toast.makeText(requireContext(), "Custom location set: $country", Toast.LENGTH_SHORT).show()
                onTemplateSaved?.invoke(savedTemplate)
                onLocationApplied?.invoke(country, address, latStr, lonStr)
                dismiss()
            }
        }
    }

    private fun checkCoordinatesValiditySilently() {
        val country = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()
        val lat = parseCoordinateToDouble(binding.etLatitude.text?.toString())
        val lon = parseCoordinateToDouble(binding.etLongitude.text?.toString())
        if (country.isNotEmpty() && WorldLocationProvider.isCountryValid(country) && lat != null && lon != null) {
            if (WorldLocationProvider.isLocationWithinCountry(lat, lon, country, context)) {
                clearCoordinatesError()
            }
        }
    }

    private fun fillFromLiveGps() {
        lifecycleScope.launch {
            val userLocation = locationHelper.getRealLiveLocationData()
            val b = _binding ?: return@launch
            val lat = userLocation.rawLatitude
            val lon = userLocation.rawLongitude
            if (lat == null || lon == null) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "GPS position currently unavailable", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val latFmt = OsmGeocodingHelper.formatLatitude(lat, decimals = 6)
            val lonFmt = OsmGeocodingHelper.formatLongitude(lon, decimals = 6)

            // Resolve real country and street address accurately
            var country = userLocation.countryName
            var address = userLocation.streetAddress

            if (country.isBlank() || !WorldLocationProvider.isCountryValid(country)) {
                try {
                    val geocoder = android.location.Geocoder(requireContext(), java.util.Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        country = addr.countryName.orEmpty()
                        val locality = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                        address = addr.getAddressLine(0) ?: addr.thoroughfare ?: locality
                    }
                } catch (e: Exception) {}
            }

            val validCountry = if (WorldLocationProvider.isCountryValid(country)) {
                WorldLocationProvider.normalizeCountry(country)
            } else {
                country
            }

            b.actvCountryRegion.setText(validCountry, false)
            clearCountryError()
            clearCoordinatesError()
            cityAdapter?.updateCountryFilter(validCountry)
            updateCityFieldEnabledState()

            b.actvCityAddress.setText(address, false)
            b.etLatitude.setText(latFmt)
            b.etLongitude.setText(lonFmt)
            context?.let { ctx ->
                Toast.makeText(ctx, "Current GPS location filled", Toast.LENGTH_SHORT).show()
            }
            validateFields()
        }
    }

    private fun parseCoordinateToDouble(coordStr: String?): Double? {
        if (coordStr.isNullOrBlank()) return null
        val clean = coordStr.replace("°", "").replace("N", "", true).replace("E", "", true).trim()
        val isNegative = coordStr.contains("S", true) || coordStr.contains("W", true)
        val parsed = clean.replace("S", "", true).replace("W", "", true).trim().toDoubleOrNull() ?: return null
        return if (isNegative) -Math.abs(parsed) else parsed
    }

    private fun isFormValid(): Boolean {
        val country = binding.actvCountryRegion.text?.toString()?.trim().orEmpty()
        val address = binding.actvCityAddress.text?.toString()?.trim().orEmpty()
        val lat = binding.etLatitude.text?.toString()?.trim().orEmpty()
        val lon = binding.etLongitude.text?.toString()?.trim().orEmpty()

        return country.isNotEmpty() && address.isNotEmpty() && lat.isNotEmpty() && lon.isNotEmpty()
    }

    private fun validateFields() {
        val valid = isFormValid()
        binding.btnCustomizeTemplate.isEnabled = valid
        binding.btnCustomizeTemplate.alpha = if (valid) 1.0f else 0.45f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
