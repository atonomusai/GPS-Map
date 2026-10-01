package com.autonoumus.ai.presentation.common.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import com.autonoumus.ai.R
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.BottomSheetPermissionRequestBinding

enum class PermissionRationaleType {
    LOCATION,
    CAMERA,
    TRIP_LOCATION
}

class PermissionRequestBottomSheetDialog : BaseBottomSheetDialogFragment<BottomSheetPermissionRequestBinding>() {

    private var permissionType: PermissionRationaleType = PermissionRationaleType.LOCATION
    private var onAllowListener: (() -> Unit)? = null
    private var onContinueWithoutListener: (() -> Unit)? = null
    var onDismissListener: (() -> Unit)? = null

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetPermissionRequestBinding {
        return BottomSheetPermissionRequestBinding.inflate(inflater, container, false)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_PERMISSION_TYPE)?.let {
            permissionType = runCatching { PermissionRationaleType.valueOf(it) }.getOrDefault(PermissionRationaleType.LOCATION)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindUiForType()

        binding.btnAllowPermission.setOnClickListener {
            dismiss()
            onAllowListener?.invoke()
        }

        binding.btnContinueWithoutPermission.setOnClickListener {
            dismiss()
            onContinueWithoutListener?.invoke()
        }
    }

    private fun bindUiForType() {
        when (permissionType) {
            PermissionRationaleType.LOCATION -> {
                binding.ivPermissionIcon.setImageResource(R.drawable.ic_location)
                binding.tvPermissionTitle.text = "Enable Location for\nGeotagging"
                binding.tvPermissionDescription.text = "To stamp real-time address, coordinates, and map location on your photos, allow location access."
                binding.btnAllowPermission.text = "Allow Location Access"
                binding.btnContinueWithoutPermission.text = "Continue without Location"
            }
            PermissionRationaleType.CAMERA -> {
                binding.ivPermissionIcon.setImageResource(R.drawable.ic_permission_camera)
                binding.tvPermissionTitle.text = "Enable Camera for\nCapturing"
                binding.tvPermissionDescription.text = "To capture photos and record videos with real-time GPS stamp overlays, allow camera access."
                binding.btnAllowPermission.text = "Allow Camera Access"
                binding.btnContinueWithoutPermission.text = "Continue without Camera"
            }
            PermissionRationaleType.TRIP_LOCATION -> {
                // Google Play prominent disclosure: tracking keeps using location while the app is
                // minimised / the screen is off (foreground service), so say so before the prompt.
                binding.ivPermissionIcon.setImageResource(R.drawable.ic_location)
                binding.tvPermissionTitle.text = "Location for\nTrip Tracking"
                binding.tvPermissionDescription.text = "To measure your speed, distance and route, " +
                    getString(R.string.app_name) + " collects your precise location while a trip is running, " +
                    "including when the app is minimised or the screen is off. Tracking starts only when " +
                    "you tap Start, shows a notification the whole time, and stops when you tap Stop. " +
                    "Trip data is saved only on your device."
                binding.btnAllowPermission.text = "Continue"
                binding.btnContinueWithoutPermission.text = "Not now"
            }
        }
    }

    companion object {
        const val TAG = "PermissionRequestBottomSheetDialog"
        private const val ARG_PERMISSION_TYPE = "arg_permission_type"

        fun showLocationRationale(
            fragmentManager: FragmentManager,
            onAllow: () -> Unit,
            onContinueWithout: (() -> Unit)? = null
        ): PermissionRequestBottomSheetDialog {
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) {
                return existing as PermissionRequestBottomSheetDialog
            }
            return PermissionRequestBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERMISSION_TYPE, PermissionRationaleType.LOCATION.name)
                }
                this.onAllowListener = onAllow
                this.onContinueWithoutListener = onContinueWithout
                show(fragmentManager, TAG)
            }
        }

        fun showTripLocationRationale(
            fragmentManager: FragmentManager,
            onAllow: () -> Unit
        ): PermissionRequestBottomSheetDialog {
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) {
                return existing as PermissionRequestBottomSheetDialog
            }
            return PermissionRequestBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERMISSION_TYPE, PermissionRationaleType.TRIP_LOCATION.name)
                }
                this.onAllowListener = onAllow
                show(fragmentManager, TAG)
            }
        }

        fun showCameraRationale(
            fragmentManager: FragmentManager,
            onAllow: () -> Unit,
            onContinueWithout: (() -> Unit)? = null
        ): PermissionRequestBottomSheetDialog {
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) {
                return existing as PermissionRequestBottomSheetDialog
            }
            return PermissionRequestBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PERMISSION_TYPE, PermissionRationaleType.CAMERA.name)
                }
                this.onAllowListener = onAllow
                this.onContinueWithoutListener = onContinueWithout
                show(fragmentManager, TAG)
            }
        }
    }
}
