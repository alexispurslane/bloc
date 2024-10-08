package io.github.alexispurslane.neo.viewmodels

import android.util.Log
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alexispurslane.neo.data.AccountsRepository
import io.github.alexispurslane.neo.data.RoomsRepository
import io.github.alexispurslane.neo.data.models.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import javax.inject.Inject

data class HomeUiState(
    val userInfo: User? = null,
    val currentServerId: String? = null,
    val lastServerChannels: Map<String, String> = emptyMap(),
    val client: MatrixClient? = null,
    val isNavDrawerOrientationRtl: Boolean = true
)

@HiltViewModel
class HomeScreenViewModel @Inject constructor(
    private val accountsRepository: AccountsRepository,
    private val roomsRepository: RoomsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val rooms
        get() = roomsRepository.rooms

    init {
        viewModelScope.launch {
            accountsRepository.userSessionFlow.collect {
                if (it?.userId != null) {
                    _uiState.update { prevState ->
                        prevState.copy(
                            isNavDrawerOrientationRtl = it.preferences["isNavDrawerOrientationRtl"]?.toBooleanStrictOrNull() ?: true,
                        )
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            accountsRepository.matrixClientFlow.collectLatest { mc ->
                mc?.loginState?.collectLatest { loginState ->
                    if (loginState == MatrixClient.LoginState.LOGGED_IN) {
                        accountsRepository.userSessionFlow.collect { userSession ->
                            if (userSession != null) {
                                Log.d("Home Screen", userSession.toString())
                                accountsRepository.fetchUserInformation(accountsRepository.userId(userSession.userId, userSession.instanceApiUrl)).onSuccess { userInfo ->
                                    _uiState.update {
                                        val lastServer = userSession.preferences["lastServerId"]
                                        val lastChannel =
                                            lastServer?.let { userSession.preferences[it] }
                                        Log.d("Home Screen", "last server: $lastServer, last channel: $lastChannel")
                                        it.copy(
                                            client = mc,
                                            userInfo = userInfo,
                                            currentServerId = it.currentServerId ?: lastServer,
                                            lastServerChannels = userSession.preferences,
                                        )
                                    }
                                }.onFailure {
                                    Log.e("Home Screen", it.stackTraceToString())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectServer(newId: String?) {
        _uiState.update {
            it.copy(
                currentServerId = newId,
            )
        }
    }

    fun selectChannel(newServerId: String, newChannelId: String?) {
        selectServer(newServerId)
        saveLast(
            newServerId,
            newChannelId ?: ""
        )
    }

    fun saveLast(currentServerId: String, currentChannelId: String) {
        viewModelScope.launch {
            accountsRepository.savePreferences(
                mapOf(
                    "lastServerId" to currentServerId,
                    currentServerId to currentChannelId
                )
            )
        }
    }
}