package com.example.di

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.MasroofiDatabase
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.FinanceRepository
import com.example.ui.viewmodel.AiEntryViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.ReportsViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SnackbarManager
import com.example.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Lightweight manual Dependency Injection container providing single shared instances of
 * [MasroofiDatabase], [AppPreferencesRepository], [FinanceRepository], and [SnackbarManager]
 * across all feature-scoped ViewModels without requiring Hilt or Koin.
 */
class AppContainer(
    val database: MasroofiDatabase,
    val preferencesRepository: AppPreferencesRepository,
    val financeRepository: FinanceRepository,
    val snackbarManager: SnackbarManager = SnackbarManager(),
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when {
                modelClass.isAssignableFrom(HomeViewModel::class.java) -> {
                    HomeViewModel(
                        repository = financeRepository,
                        snackbarManager = snackbarManager,
                        defaultDispatcher = defaultDispatcher,
                        ioDispatcher = ioDispatcher
                    ) as T
                }
                modelClass.isAssignableFrom(TransactionsViewModel::class.java) -> {
                    TransactionsViewModel(
                        repository = financeRepository,
                        snackbarManager = snackbarManager,
                        defaultDispatcher = defaultDispatcher
                    ) as T
                }
                modelClass.isAssignableFrom(AiEntryViewModel::class.java) -> {
                    AiEntryViewModel(
                        repository = financeRepository,
                        snackbarManager = snackbarManager
                    ) as T
                }
                modelClass.isAssignableFrom(ReportsViewModel::class.java) -> {
                    ReportsViewModel(
                        repository = financeRepository,
                        snackbarManager = snackbarManager,
                        defaultDispatcher = defaultDispatcher
                    ) as T
                }
                modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                    SettingsViewModel(
                        repository = financeRepository,
                        snackbarManager = snackbarManager,
                        ioDispatcher = ioDispatcher
                    ) as T
                }
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppContainer? = null

        fun getInstance(context: Context): AppContainer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDefaultContainer(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDefaultContainer(
            appContext: Context,
            defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): AppContainer {
            val db = MasroofiDatabase.getInstance(appContext)
            val prefsRepo = AppPreferencesRepository(appContext)
            val financeRepo = FinanceRepository(
                dao = db.dao(),
                preferencesRepository = prefsRepo,
                ioDispatcher = ioDispatcher,
                defaultDispatcher = defaultDispatcher
            )
            return AppContainer(
                database = db,
                preferencesRepository = prefsRepo,
                financeRepository = financeRepo,
                defaultDispatcher = defaultDispatcher,
                ioDispatcher = ioDispatcher
            )
        }
    }
}
