package com.example.presentation.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.RouterProfileEntity
import com.example.presentation.adapter.LogAdapter
import com.example.service.TestService
import com.example.widget.ConnectionStatusView
import com.example.widget.CustomProgressBar
import com.example.widget.StatisticCard
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModel()

    private lateinit var spinnerRouter: Spinner
    private lateinit var tvRouterInfo: TextView
    private lateinit var etPrefix: TextInputEditText
    private lateinit var etLength: TextInputEditText
    private lateinit var etCharset: TextInputEditText
    private lateinit var etCount: TextInputEditText
    private lateinit var connectionStatusView: ConnectionStatusView

    private lateinit var cardSent: StatisticCard
    private lateinit var cardSuccess: StatisticCard
    private lateinit var cardFailed: StatisticCard

    private lateinit var layoutProgress: View
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: CustomProgressBar

    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnClearLog: MaterialButton

    private lateinit var logAdapter: LogAdapter
    private lateinit var recyclerLog: RecyclerView

    private var routerList: List<RouterProfileEntity> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeViews(view)
        setupLogRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun initializeViews(view: View) {
        spinnerRouter = view.findViewById(R.id.spinner_router)
        tvRouterInfo = view.findViewById(R.id.tv_router_info)
        etPrefix = view.findViewById(R.id.et_prefix)
        etLength = view.findViewById(R.id.et_length)
        etCharset = view.findViewById(R.id.et_charset)
        etCount = view.findViewById(R.id.et_count)
        connectionStatusView = view.findViewById(R.id.connection_status_view)

        cardSent = view.findViewById(R.id.card_sent)
        cardSuccess = view.findViewById(R.id.card_success)
        cardFailed = view.findViewById(R.id.card_failed)

        layoutProgress = view.findViewById(R.id.layout_progress)
        tvProgress = view.findViewById(R.id.tv_progress)
        progressBar = view.findViewById(R.id.progress_bar)

        btnStart = view.findViewById(R.id.btn_start_test)
        btnStop = view.findViewById(R.id.btn_stop_test)
        btnClearLog = view.findViewById(R.id.btn_clear_log_extra)
    }

    private fun setupLogRecyclerView() {
        recyclerLog = view?.findViewById(R.id.recycler_log) ?: return
        logAdapter = LogAdapter()
        recyclerLog.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }
    }

    private fun setupListeners() {
        spinnerRouter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in routerList.indices) {
                    val router = routerList[position]
                    viewModel.selectRouter(router.id)
                    tvRouterInfo.text = "IP: ${router.ip} | Path: ${router.loginPath}"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnStart.setOnClickListener {
            val prefix = etPrefix.text?.toString() ?: ""
            val length = etLength.text?.toString()?.toIntOrNull() ?: 6
            val charset = etCharset.text?.toString() ?: "0123456789"
            val count = etCount.text?.toString()?.toIntOrNull() ?: 10

            // Check if connected to Wi-Fi!
            if (viewModel.connectionState.value == HomeViewModel.ConnectionState.DISCONNECTED) {
                com.example.util.DialogHelper.showCustomDialog(
                    context = requireContext(),
                    title = "\u062A\u0646\u0628\u064A\u0647: \u063A\u064A\u0631 \u0645\u062A\u0635\u0644 \u0628\u0627\u0644\u0648\u0627\u064A \u0641\u0627\u064A",
                    message = "\u0623\u0646\u062A \u063A\u064A\u0631 \u0645\u062A\u0635\u0644 \u0628\u0634\u0628\u0643\u0629 \u0627\u0644\u0648\u0627\u064A \u0641\u0627\u064A \u062D\u0627\u0644\u064A\u0627\u064B. \u064A\u0631\u062C\u0649 \u0627\u0644\u0627\u062A\u0635\u0627\u0644 \u0628\u0627\u0644\u0634\u0628\u0643\u0629 \u0644\u0643\u064A \u062A\u062A\u0645\u0643\u0646 \u0645\u0646 \u0627\u0644\u0648\u0635\u0648\u0644 \u0644\u0635\u0641\u062D\u0629 \u062A\u0633\u062C\u064A\u0644 \u062F\u062E\u0648\u0644 \u0627\u0644\u0631\u0627\u0648\u062A\u0631 \u0648\u0627\u062E\u062A\u0628\u0627\u0631 \u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0628\u0646\u062C\u0627\u062D.",
                    dialogType = com.example.util.DialogHelper.DialogType.ERROR,
                    positiveButtonText = "\u062D\u0633\u0646\u0627\u064B\u060C \u0641\u0647\u0645\u062A"
                )
                return@setOnClickListener
            }

            if (viewModel.isServiceRunning().value) {
                com.example.util.DialogHelper.showCustomDialog(
                    context = requireContext(),
                    title = "\u0639\u0645\u0644\u064A\u0629 \u062C\u0627\u0631\u064A\u0629 \u0628\u0627\u0644\u0641\u0639\u0644",
                    message = "\u0647\u0646\u0627\u0643 \u0639\u0645\u0644\u064A\u0629 \u0641\u062D\u0635 \u062C\u0627\u0631\u064A\u0629 \u062D\u0627\u0644\u064A\u0627\u064B \u0639\u0644\u0649 \u0627\u0644\u0631\u0627\u0648\u062A\u0631 \u0627\u0644\u0645\u062E\u062A\u0627\u0631. \u0647\u0644 \u062A\u0648\u062F \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0639\u0645\u0644\u064A\u0629 \u0627\u0644\u062C\u0627\u0631\u064A\u0629 \u0648\u0627\u0644\u0628\u062F\u0621 \u0645\u062C\u062F\u062F\u0627\u064B \u0628\u0639\u0645\u0644\u064A\u0629 \u0641\u062D\u0635 \u062C\u062F\u064A\u062F\u0629\u060C \u0623\u0645 \u062A\u0648\u062F \u0627\u0644\u0645\u062A\u0627\u0628\u0639\u0629 \u0648\u0645\u0631\u0627\u0642\u0628\u0629 \u0627\u0644\u0641\u062D\u0635 \u0627\u0644\u062D\u0627\u0644\u064A\u061F",
                    dialogType = com.example.util.DialogHelper.DialogType.WARNING,
                    positiveButtonText = "\u0625\u0644\u063A\u0627\u0621 \u0648\u0627\u0644\u0628\u062F\u0621 \u0645\u0646 \u062C\u062F\u064A\u062F",
                    positiveAction = {
                        // 1. Cancel previous service
                        val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                            action = TestService.ACTION_CANCEL
                        }
                        requireContext().startService(serviceIntent)

                        // 2. Start a new test
                        viewModel.generateAndStart(
                            prefix = prefix,
                            length = length,
                            count = count,
                            charset = charset,
                            delayMs = 2000L
                        )
                    },
                    negativeButtonText = "\u0627\u0644\u0645\u062A\u0627\u0628\u0639\u0629 \u0648\u0645\u0631\u0627\u0642\u0628\u0629 \u0627\u0644\u0641\u062D\u0635 \u0627\u0644\u062D\u0627\u0644\u064A",
                    negativeAction = {
                        // Go to test fragment monitoring page
                        val routerId = viewModel.selectedRouterId.value
                        val bundle = Bundle().apply {
                            putLong("routerId", routerId)
                            putStringArray("cardList", emptyArray())
                            putLong("delayMs", 2000L)
                        }
                        try {
                            findNavController().navigate(R.id.action_home_fragment_to_test_fragment, bundle)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to navigate to test fragment safely")
                        }
                    },
                    neutralButtonText = "\u0625\u063A\u0644\u0627\u0642"
                )
            } else {
                viewModel.generateAndStart(
                    prefix = prefix,
                    length = length,
                    count = count,
                    charset = charset,
                    delayMs = 2000L
                )
            }
        }

        btnStop.setOnClickListener {
            com.example.util.DialogHelper.showCustomDialog(
                context = requireContext(),
                title = "\u062A\u0623\u0643\u064A\u062F \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0641\u062D\u0635 \u0627\u0644\u062C\u0627\u0631\u064A",
                message = "\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u0631\u063A\u0628\u062A\u0643 \u0641\u064A \u0625\u0644\u063A\u0627\u0621 \u0639\u0645\u0644\u064A\u0629 \u0627\u0644\u0641\u062D\u0635 \u0627\u0644\u062C\u0627\u0631\u064A\u0629 \u0644\u0644\u0628\u0637\u0627\u0642\u0627\u062A\u061F \u0633\u064A\u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u0641\u062D\u0635 \u0648\u062A\u062C\u0631\u0628\u0629 \u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0643\u0644\u064A\u0627\u064B.",
                dialogType = com.example.util.DialogHelper.DialogType.WARNING,
                positiveButtonText = "\u0646\u0639\u0645\u060C \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u0641\u062D\u0635",
                positiveAction = {
                    val serviceIntent = Intent(requireContext(), TestService::class.java).apply {
                        action = TestService.ACTION_CANCEL
                    }
                    requireContext().startService(serviceIntent)
                    com.example.util.ToastHelper.showSuccessToast(requireContext(), "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0639\u0645\u0644\u064A\u0629 \u0627\u0644\u0641\u062D\u0635 \u0628\u0646\u062C\u0627\u062D!")
                },
                negativeButtonText = "\u0644\u0627\u060C \u0627\u0633\u062A\u0645\u0631\u0627\u0631 \u0627\u0644\u0639\u0645\u0644"
            )
        }

        btnClearLog.setOnClickListener {
            com.example.util.DialogHelper.showCustomDialog(
                context = requireContext(),
                title = "\u062A\u0623\u0643\u064A\u062F \u062D\u0630\u0641 \u0627\u0644\u0633\u062C\u0644\u0627\u062A",
                message = "\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u0631\u063A\u0628\u062A\u0643 \u0641\u064A \u062D\u0630\u0641 \u062C\u0645\u064A\u0639 \u0633\u062C\u0644\u0627\u062A \u0627\u0644\u0641\u062D\u0635 \u0648\u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0648\u062A\u0635\u0641\u064A\u0631 \u0627\u0644\u0625\u062D\u0635\u0627\u0626\u064A\u0627\u062A \u0628\u0627\u0644\u0643\u0627\u0645\u0644\u061F \u0644\u0627 \u064A\u0645\u0643\u0646 \u0627\u0644\u062A\u0631\u0627\u062C\u0639 \u0639\u0646 \u0647\u0630\u0627 \u0627\u0644\u0625\u062C\u0631\u0627\u0621.",
                dialogType = com.example.util.DialogHelper.DialogType.WARNING,
                positiveButtonText = "\u0646\u0639\u0645\u060C \u0627\u062D\u0630\u0641 \u0648\u0635\u0641\u0631 \u0627\u0644\u0625\u062D\u0635\u0627\u0626\u064A\u0627\u062A",
                positiveAction = {
                    viewModel.clearLogs()
                    com.example.util.ToastHelper.showSuccessToast(requireContext(), "\u062A\u0645 \u0645\u0633\u062D \u062C\u0645\u064A\u0639 \u0627\u0644\u0633\u062C\u0644\u0627\u062A \u0648\u062A\u0635\u0641\u064A\u0631 \u0627\u0644\u0625\u0639\u062F\u0627\u062F\u0627\u062A \u0628\u0646\u062C\u0627\u062D!")
                },
                negativeButtonText = "\u0625\u0644\u063A\u0627\u0621 \u0648\u0625\u0628\u0642\u0627\u0621"
            )
        }

        view?.findViewById<View>(R.id.btn_manage_routers)?.setOnClickListener {
            try {
                findNavController().navigate(R.id.nav_router_manager_fragment)
            } catch (e: Exception) {
                Timber.e(e, "Failed navigating to router manager fragment safely")
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                
                launch {
                    val settings = viewModel.getInitialSettings()
                    etPrefix.setText(settings.prefix)
                    etLength.setText(settings.length.toString())
                    etCount.setText(settings.count.toString())
                    etCharset.setText(settings.charset)

                    var isFirstRouterLoad = true
                    viewModel.routers.collectLatest { routers ->
                        val ctx = context ?: return@collectLatest
                        routerList = routers
                        val names = routers.map { it.name }
                        val adapter = ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_item,
                            names
                        ).apply {
                            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                        spinnerRouter.adapter = adapter
                        
                        // Select default router if exists
                        if (isFirstRouterLoad && routers.isNotEmpty()) {
                            val defaultId = settings.defaultRouterId
                            val index = routers.indexOfFirst { it.id == defaultId }
                            if (index >= 0) {
                                spinnerRouter.setSelection(index)
                            }
                            isFirstRouterLoad = false
                        }
                    }
                }

                launch {
                    viewModel.connectionState.collectLatest { state ->
                        connectionStatusView.setConnected(state == HomeViewModel.ConnectionState.CONNECTED)
                    }
                }

                launch {
                    viewModel.logEntries.collectLatest { logs ->
                        logAdapter.submitList(logs) {
                            if (logs.isNotEmpty()) {
                                recyclerLog.post { recyclerLog.scrollToPosition(logs.size - 1) }
                            }
                        }
                    }
                }

                launch {
                    viewModel.statistics.collectLatest { stats ->
                        val ctx = context ?: return@collectLatest
                        cardSent.bind("\u0627\u0644\u0625\u062C\u0645\u0627\u0644\u064A \u0627\u0644\u0645\u0648\u0644\u062F", stats.total.toString())
                        cardSuccess.bind("\u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0627\u0644\u0646\u0627\u062C\u062D\u0629", stats.success.toString(), ContextCompat.getColor(ctx, android.R.color.holo_green_dark))
                        cardFailed.bind("\u0627\u0644\u0628\u0637\u0627\u0642\u0627\u062A \u0627\u0644\u0641\u0627\u0634\u0644\u0629", stats.failure.toString(), ContextCompat.getColor(ctx, android.R.color.holo_red_dark))

                        if (stats.total > 0) {
                            val pct = (stats.success + stats.failure) * 100 / stats.total
                            tvProgress.text = "$pct%"
                            progressBar.setProgressWithAnimation(pct)
                        } else {
                            tvProgress.text = "0%"
                            progressBar.progress = 0
                        }
                    }
                }

                launch {
                    viewModel.uiState.collectLatest { state ->
                        when (state) {
                            is HomeViewModel.UiState.Testing -> {
                                btnStart.isEnabled = true
                                btnStop.isEnabled = true
                                layoutProgress.visibility = View.VISIBLE
                            }
                            else -> {
                                btnStart.isEnabled = true
                                btnStop.isEnabled = false
                                layoutProgress.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    viewModel.errorFlow.collectLatest { err ->
                        val ctx = context ?: return@collectLatest
                        com.example.util.ToastHelper.showErrorToast(ctx, err)
                    }
                }

                launch {
                    viewModel.startTestEvent.collect { config ->
                        val ctx = context ?: return@collect
                        val serviceIntent = Intent(ctx, TestService::class.java).apply {
                            action = TestService.ACTION_START
                            putExtra(TestService.EXTRA_ROUTER_ID, config.routerId)
                            putStringArrayListExtra(TestService.EXTRA_CARD_LIST, config.cardList)
                            putExtra(TestService.EXTRA_DELAY_MS, config.delayMs)
                        }
                        try {
                            ContextCompat.startForegroundService(ctx, serviceIntent)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to start TestService in foreground")
                            com.example.util.ToastHelper.showErrorToast(ctx, "\u0641\u0634\u0644 \u0628\u062F\u0621 \u062E\u062F\u0645\u0629 \u0627\u0644\u0645\u0632\u0627\u0645\u0646\u0629 \u0628\u0627\u0644\u062E\u0644\u0641\u064A\u0629: ${e.localizedMessage}")
                        }

                        // Navigate to TestFragment using bundle for maximum compile safety
                        val bundle = Bundle().apply {
                            putLong("routerId", config.routerId)
                            putStringArray("cardList", config.cardList.toTypedArray())
                            putLong("delayMs", config.delayMs)
                        }
                        if (isAdded) {
                            try {
                                findNavController().navigate(R.id.action_home_fragment_to_test_fragment, bundle)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to navigate to test fragment safely")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startMonitoringConnection(requireContext())
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopMonitoringConnection()
        
        val prefix = etPrefix.text?.toString() ?: ""
        val length = etLength.text?.toString()?.toIntOrNull() ?: 6
        val charset = etCharset.text?.toString() ?: "0123456789"
        val count = etCount.text?.toString()?.toIntOrNull() ?: 10
        val routerId = viewModel.selectedRouterId.value
        
        viewModel.saveSettingsQuickly(prefix, length, count, charset, routerId)
    }
}
