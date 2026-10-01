package com.autonoumus.ai.presentation.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autonoumus.ai.core.base.BaseBottomSheetDialogFragment
import com.autonoumus.ai.databinding.BottomSheetSelectSourceBinding

class SelectSourceBottomSheetDialog : BaseBottomSheetDialogFragment<BottomSheetSelectSourceBinding>() {

    private var onTakePhotoVideoListener: (() -> Unit)? = null
    private var onChooseGalleryListener: (() -> Unit)? = null

    override fun inflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): BottomSheetSelectSourceBinding {
        return BottomSheetSelectSourceBinding.inflate(inflater, container, false)
    }

    fun setListeners(
        onTakePhotoVideo: () -> Unit,
        onChooseGallery: () -> Unit
    ) {
        this.onTakePhotoVideoListener = onTakePhotoVideo
        this.onChooseGalleryListener = onChooseGallery
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.cardTakePhotoVideo.setOnClickListener {
            dismiss()
            onTakePhotoVideoListener?.invoke()
        }

        binding.cardChooseGallery.setOnClickListener {
            dismiss()
            onChooseGalleryListener?.invoke()
        }
    }

    companion object {
        const val TAG = "SelectSourceBottomSheet"

        fun newInstance(
            onTakePhotoVideo: () -> Unit,
            onChooseGallery: () -> Unit
        ): SelectSourceBottomSheetDialog {
            return SelectSourceBottomSheetDialog().apply {
                setListeners(onTakePhotoVideo, onChooseGallery)
            }
        }
    }
}
