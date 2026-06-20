package com.example.presentation.test

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.R
import com.example.service.TestService
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.koin.androidx.viewmodel.ext.android.viewModel

class TestFragment : Fragment() {

    private val viewModel: TestViewModel by viewModel()
    private var activeErrorDialog: androidx.appcompat.app.AlertDialog? = null

    private lateinit var tvTestStatus: TextView
    private lateinit var tvActiveCard: TextView
    private lateinit var testProgressBar: LinearProgressIndicator
    private lateinit var ivLiveScreenshot: ImageView
    private lateinit var pbLoadState: ProgressBar
    private lateinit var btnPause: MaterialButton
    private lateinit var btnCancel: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupListeners()
        observeServiceState()
    }

    private fun initializeViews(view: View) {
        tvTestStatus = view.findViewById(R.id.tv_test_status)
        tvActiveCard = view.findViewById(R.id.tv_active_card)
        testProgressBar = view.findViewById(R.id.test_progress_bar)
        ivLiveScreenshot = view.findViewById(R.id.iv_live_screenshot)
        pbLoadState = view.findViewById(R.id.pb_load_state)
        btnPause = view.findViewById(R.id.btn_test_pause)
        btnCancel = view.findViewById(R.id.btn_test_cancel)
    }

    private fun setupListeners() {
        btnPause.setOnClickListener {
            val isCurrentlyPaused = viewModel.serviceState.value.isPaused
            val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                action = if (isCurrentlyPaused) TestService.ACTION_RESUME else TestService.ACTION_PAUSE
            }
            requireContext().startService(serviceIntent)
        }

        btnCancel.setOnClickListener {
            com.example.util.DialogHelper.showCustomDialog(
                context = requireContext(),
                title = "\u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631",
                message = "\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u0631\u063A\u0628\u062A\u0643 \u0641\u064A \u0625\u0644\u063A\u0627\u0621 \u0639\u0645\u0644\u064A\u0629 \u0641\u062D\u0635 \u0648\u062A\u062D\u0644\u064A\u0644 \u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0627\u0644\u062C\u0627\u0631\u064A\u0629\u061F",
                dialogType = com.example.util.DialogHelper.DialogType.WARNING,
                positiveButtonText = "\u0646\u0639\u0645\u060C \u0625\u0644\u063A\u0627\u0621",
                positiveAction = {
                    val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                        action = TestService.ACTION_CANCEL
                    }
                    requireContext().startService(serviceIntent)
                    findNavController().popBackStack()
                },
                negativeButtonText = "\u0644\u0627\u060C \u0627\u0633\u062A\u0645\u0631\u0627\u0631"
            )
        }
    }

    private fun observeServiceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                
                launch {
                    kotlinx.coroutines.flow.combine(
                        viewModel.serviceState,
                        com.example.service.TestService.isRunning
                    ) { state, isRunning ->
                        Pair(state, isRunning)
                    }.collectLatest { (state, isRunning) ->
                        // Status text
                        val statusText = when (state.status) {
                            "RUNNING" -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u062C\u0627\u0631\u064A \u0627\u0644\u0641\u062D\u0635 \u0627\u0644\u0646\u0634\u0637..."
                            "PAUSED" -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u0645\u0648\u0642\u0648\u0641 \u0645\u0624\u0642\u062A\u0627\u064B"
                            "DONE" -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u062A\u0645 \u0625\u0643\u0645\u0627\u0644 \u0627\u0644\u0641\u062D\u0635 \u0643\u0644\u064A\u0627\u064B"
                            "LOAD_ERROR" -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u0641\u0634\u0644 \u0641\u064A \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0635\u0641\u062D\u0629"
                            "CANCELLED" -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u062A\u0645 \u0627\u0644\u0625\u0644\u063A\u0627\u0621"
                            else -> "\u062D\u0627\u0644\u0629 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631: \u062A\u0647\u064A\u0623\u0629 \u0627\u0644\u0628\u064A\u0626\u0629..."
                        }
                        tvTestStatus.text = statusText
                        tvActiveCard.text = "\u0627\u0644\u0628\u0637\u0627\u0642\u0629 \u0627\u0644\u062D\u0627\u0644\u064A\u0629 \u0644\u0644\u0641\u062D\u0635: ${state.currentCard} (${state.progress}/${state.total})"

                        // Progress Indicator
                        if (state.total > 0) {
                            testProgressBar.max = state.total
                            testProgressBar.setProgress(state.progress, true)
                        } else {
                            testProgressBar.setProgress(0, false)
                        }

                         // Pause Button Label
                        btnPause.text = if (state.isPaused) "\u0627\u0633\u062A\u0626\u0646\u0627\u0641" else "\u0625\u064A\u0642\u0627\u0641 \u0645\u0624\u0642\u062A"

                        val isProcessActive = isRunning && (state.status == "RUNNING" || state.status == "PAUSED" || state.status == "LOAD_ERROR")
                        btnPause.isEnabled = isProcessActive
                        btnCancel.isEnabled = isProcessActive

                        // Handle live stream screenshot on background dispatcher
                        state.screenshotBytes?.let { bytes ->
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = 1
                                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                                }
                                val bitmap = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                } catch (e: Exception) {
                                    null
                                }
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        if (isAdded && view != null) {
                                            ivLiveScreenshot.setImageBitmap(bitmap)
                                        }
                                    }
                                }
                            }
                        }

                        if (state.status == "DONE") {
                            val ctx = context
                            if (ctx != null && isAdded) {
                                try {
                                    com.example.util.ToastHelper.showSuccessToast(ctx, "\u0627\u0643\u062A\u0645\u0644 \u0627\u0644\u0627\u062E\u062A\u0628\u0627\u0631 \u0627\u0644\u062C\u0627\u0631\u064A \u0628\u0646\u062C\u0627\u062D!")
                                    findNavController().popBackStack()
                                } catch (e: Exception) {
                                    Timber.e(e, "Failed to pop backstack in TestFragment")
                                }
                            }
                        }

                        if (state.status == "LOAD_ERROR") {
                            showLoadErrorDialog(state.error ?: "\u0641\u0634\u0644 \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0635\u0641\u062D\u0629")
                        } else {
                            activeErrorDialog?.dismiss()
                            activeErrorDialog = null
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeErrorDialog?.dismiss()
        activeErrorDialog = null
    }

    private fun showLoadErrorDialog(errorMessage: String) {
        if (activeErrorDialog != null && activeErrorDialog?.isShowing == true) {
            return
        }
        val ctx = context ?: return
        if (!isAdded) return

        activeErrorDialog = com.example.util.DialogHelper.showCustomDialog(
            context = ctx,
            title = "\u0641\u0634\u0644 \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0635\u0641\u062D\u0629",
            message = "\u0644\u0645 \u0646\u062A\u0645\u0643\u0646 \u0645\u0646 \u0627\u0644\u0648\u0635\u0648\u0644 \u0644\u0635\u0641\u062D\u0629 \u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062F\u062E\u0648\u0644 \u0644\u0644\u0631\u0627\u0648\u062A\u0631.\n\u064A\u0631\u062C\u0649 \u0627\u0644\u062A\u0623\u0643\u062F \u0645\u0646 \u0623\u0646\u0643 \u0645\u062A\u0635\u0644 \u0628\u0634\u0628\u0643\u0629 \u0627\u0644\u0648\u0627\u064A \u0641\u0627\u064A \u0644\u0644\u0631\u0627\u0648\u062A\u0631 \u0648\u0642\u0631\u064A\u0628 \u0645\u0646\u0647\u060C \u062B\u0645 \u0623\u0639\u062F \u0627\u0644\u0645\u062D\u0627\u0648\u0644\u0629.\n\n\u0627\u0644\u062E\u0637\u0623: $errorMessage",
            dialogType = com.example.util.DialogHelper.DialogType.WARNING,
            positiveButtonText = "\u0625\u0639\u0627\u062F\u0629 \u0627\u0644\u0645\u062D\u0627\u0648\u0644\u0629",
            positiveAction = {
                val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                    action = TestService.ACTION_RETRY_LOAD
                }
                requireContext().startService(serviceIntent)
                activeErrorDialog = null
            },
            negativeButtonText = "\u0625\u0644\u063A\u0627\u0621 \u0648\u0627\u0644\u0639\u0648\u062F\u0629",
            negativeAction = {
                val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                    action = TestService.ACTION_CANCEL
                }
                requireContext().startService(serviceIntent)
                activeErrorDialog = null
                findNavController().popBackStack()
            },
            isCancelable = false
        )
    }
}
