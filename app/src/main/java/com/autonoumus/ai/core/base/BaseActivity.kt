package com.autonoumus.ai.core.base

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.viewbinding.ViewBinding

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    private var _binding: VB? = null
    protected val binding: VB
        get() = _binding ?: throw IllegalStateException("Binding cannot be accessed before onCreate")

    protected val bindingOrNull: VB?
        get() = _binding

    abstract fun inflateBinding(inflater: LayoutInflater): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        _binding = inflateBinding(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        initViews()
        observeViewModel()
    }

    protected open fun applyWindowInsets() {
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
    }

    protected open fun initViews() {}
    protected open fun observeViewModel() {}

    override fun onDestroy() {
        super.onDestroy()
        // Note: We intentionally do NOT clear _binding here.
        // In Android Activities, the Activity instance and its Views share the exact same lifecycle
        // and are garbage collected together. Retaining _binding prevents crashes during subclass
        // teardown, asynchronous callbacks, and video/animation cleanup.
    }
}
