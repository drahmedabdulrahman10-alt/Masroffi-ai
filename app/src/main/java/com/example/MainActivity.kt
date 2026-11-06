package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MasroofiApp
import com.example.ui.viewmodel.MasroofiViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MasroofiViewModel = viewModel(
                factory = MasroofiViewModel.Factory(applicationContext)
            )
            MasroofiApp(viewModel = viewModel)
        }
    }
}
