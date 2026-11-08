package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.di.AppContainer
import com.example.ui.MasroofiApp
import com.example.ui.viewmodel.AiEntryViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ReportsViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.TransactionsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer.getInstance(applicationContext)
        setContent {
            val homeViewModel: HomeViewModel = viewModel(factory = container.viewModelFactory)
            val transactionsViewModel: TransactionsViewModel = viewModel(factory = container.viewModelFactory)
            val aiEntryViewModel: AiEntryViewModel = viewModel(factory = container.viewModelFactory)
            val reportsViewModel: ReportsViewModel = viewModel(factory = container.viewModelFactory)
            val settingsViewModel: SettingsViewModel = viewModel(factory = container.viewModelFactory)

            MasroofiApp(
                homeViewModel = homeViewModel,
                transactionsViewModel = transactionsViewModel,
                aiEntryViewModel = aiEntryViewModel,
                reportsViewModel = reportsViewModel,
                settingsViewModel = settingsViewModel
            )
        }
    }
}
