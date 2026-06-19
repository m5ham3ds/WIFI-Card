package com.example.presentation.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.example.R

class LockedFragment : Fragment() {

    private lateinit var btnUninstall: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_locked, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnUninstall = view.findViewById(R.id.btn_uninstall)
        btnUninstall.setOnClickListener {
            // Close the app when uninstall is clicked since we can't programmatically uninstall ourselves easily from here without permissions.
            requireActivity().finishAffinity()
        }
    }
}
