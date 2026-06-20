package com.example.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.local.entity.RouterProfileEntity
import com.example.presentation.adapter.RouterProfileAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class RouterManagerFragment : Fragment() {

    private val viewModel: RouterManagerViewModel by viewModel()
    private lateinit var adapter: RouterProfileAdapter
    private lateinit var recyclerRouters: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var fabAdd: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_router_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerRouters = view.findViewById(R.id.recycler_routers)
        tvEmpty = view.findViewById(R.id.tv_empty_routers)
        fabAdd = view.findViewById(R.id.fab_add_router)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = RouterProfileAdapter(
            onSetDefault = { router -> viewModel.setDefaultRouter(router.id) },
            onEdit = { router ->
                val bundle = Bundle().apply {
                    putLong("routerId", router.id)
                }
                findNavController().navigate(R.id.nav_router_form_fragment, bundle)
            },
            onDelete = { router -> confirmDeleteRouter(router) }
        )
        recyclerRouters.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@RouterManagerFragment.adapter
        }
    }

    private fun setupListeners() {
        fabAdd.setOnClickListener {
            val bundle = Bundle().apply {
                putLong("routerId", -1L)
            }
            findNavController().navigate(R.id.nav_router_form_fragment, bundle)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.routers.collectLatest { list ->
                    adapter.submitList(list)
                    tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun confirmDeleteRouter(router: RouterProfileEntity) {
        com.example.util.DialogHelper.showCustomDialog(
            context = requireContext(),
            title = "\u062D\u0630\u0641 \u0645\u0644\u0641 \u0627\u0644\u062A\u0639\u0631\u064A\u0641",
            message = "\u0647\u0644 \u0623\u0646\u062A \u0645\u062A\u0623\u0643\u062F \u0645\u0646 \u0631\u063A\u0628\u062A\u0643 \u0641\u064A \u062D\u0630\u0641 \u0645\u0644\u0641 '${router.name}'\u061F",
            dialogType = com.example.util.DialogHelper.DialogType.WARNING,
            positiveButtonText = "\u062D\u0630\u0641",
            positiveAction = { viewModel.deleteRouter(router) },
            negativeButtonText = "\u0625\u0644\u063A\u0627\u0621"
        )
    }
}
