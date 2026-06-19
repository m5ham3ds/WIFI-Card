package com.example.presentation.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class SecurityFragment : Fragment() {

    private val viewModel: SecurityViewModel by viewModel()

    private lateinit var etPassword: EditText
    private lateinit var btnConfirm: Button
    private lateinit var tvError: TextView
    private lateinit var llInputContainer: View
    private lateinit var llVerifyingContainer: View
    private lateinit var tvVerifyingMsg: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_security, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etPassword = view.findViewById(R.id.et_password)
        btnConfirm = view.findViewById(R.id.btn_confirm)
        tvError = view.findViewById(R.id.tv_error)
        llInputContainer = view.findViewById(R.id.ll_input_container)
        llVerifyingContainer = view.findViewById(R.id.ll_verifying_container)
        tvVerifyingMsg = view.findViewById(R.id.tv_verifying_msg)

        btnConfirm.setOnClickListener {
            val pwd = etPassword.text.toString()
            viewModel.submitPassword(pwd)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        when (state) {
                            "input" -> {
                                llInputContainer.visibility = View.VISIBLE
                                llVerifyingContainer.visibility = View.GONE
                            }
                            "verifying" -> {
                                llInputContainer.visibility = View.GONE
                                llVerifyingContainer.visibility = View.VISIBLE
                                val isArabic = com.example.util.LocaleHelper.getPersistedLocale(requireContext()) == "ar"
                                tvVerifyingMsg.text = if (isArabic) "جاري التحقق من كلمة السر..." else "Verifying password..."
                                tvVerifyingMsg.setTextColor(android.graphics.Color.WHITE)
                                tvError.visibility = View.GONE
                            }
                            "success_verified" -> {
                                llInputContainer.visibility = View.GONE
                                llVerifyingContainer.visibility = View.VISIBLE
                                val isArabic = com.example.util.LocaleHelper.getPersistedLocale(requireContext()) == "ar"
                                tvVerifyingMsg.text = if (isArabic) "تم التأكد و جاري تحويلك الى الصفحة الرئيسية..." else "Verified! Redirecting to main page..."
                                tvVerifyingMsg.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                            }
                            "unlocked" -> {
                                findNavController().navigate(R.id.action_security_to_home)
                            }
                            "locked" -> {
                                findNavController().navigate(R.id.action_security_to_locked)
                            }
                        }
                    }
                }

                launch {
                    viewModel.errorEvent.collectLatest { err ->
                        if (err != null) {
                            tvError.text = err
                            tvError.visibility = View.VISIBLE
                            viewModel.clearError()
                        }
                    }
                }
            }
        }
    }
}
