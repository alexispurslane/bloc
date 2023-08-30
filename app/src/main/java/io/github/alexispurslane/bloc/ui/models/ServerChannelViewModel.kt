package io.github.alexispurslane.bloc.ui.models

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alexispurslane.bloc.Either
import io.github.alexispurslane.bloc.data.RevoltAccountsRepository
import io.github.alexispurslane.bloc.data.RevoltChannelsRepository
import io.github.alexispurslane.bloc.data.RevoltMessagesRepository
import io.github.alexispurslane.bloc.data.RevoltServersRepository
import io.github.alexispurslane.bloc.data.network.models.RevoltChannel
import io.github.alexispurslane.bloc.data.network.models.RevoltMessage
import io.github.alexispurslane.bloc.data.network.models.RevoltServer
import io.github.alexispurslane.bloc.data.network.models.RevoltServerMember
import io.github.alexispurslane.bloc.data.network.models.RevoltUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerChannelUiState(
    val channelId: String? = null,
    val channelInfo: RevoltChannel? = null,
    val serverInfo: RevoltServer? = null,
    val users: Map<String, Pair<RevoltUser, RevoltServerMember>> = emptyMap(),
    val messages: SnapshotStateList<RevoltMessage> = mutableStateListOf(),
    val currentUserId: String? = null,
    val error: String? = null
)
@HiltViewModel
class ServerChannelViewModel @Inject constructor(
    private val revoltAccountsRepository: RevoltAccountsRepository,
    private val revoltServersRepository: RevoltServersRepository,
    private val revoltChannelsRepository: RevoltChannelsRepository,
    private val revoltMessagesRepository: RevoltMessagesRepository,
    private val savedStateHandle: SavedStateHandle
): ViewModel() {

    private val _uiState = MutableStateFlow(ServerChannelUiState())
    val uiState: StateFlow<ServerChannelUiState> = _uiState.asStateFlow()

    private val regex by lazy { Regex("<@([a-zA-Z0-9]+)>") }

    init {
        viewModelScope.launch {
            revoltAccountsRepository.userSessionFlow.collect {
                if (it.userId != null) {
                    _uiState.update { prevState ->
                        prevState.copy(
                            currentUserId = it.userId
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            savedStateHandle.getStateFlow("channelId", null)
                .collectLatest { channelId: String? ->
                    if (channelId != null) {
                        _uiState.update {
                            initializeChannelData(channelId, it)
                        }
                    }
                }
        }
    }

    private suspend fun initializeChannelData(
        channelId: String,
        prevState: ServerChannelUiState
    ): ServerChannelUiState {
        val channelInfo = revoltChannelsRepository.channels.value[channelId]
        if (channelInfo !is RevoltChannel.TextChannel) return prevState.copy(
            error = "Uh oh! Unable to locate channel"
        )

        val serverInfo =
            revoltServersRepository.servers.value[channelInfo.serverId]
        if (serverInfo == null) return prevState.copy(error = "Uh oh! Unable to locate server")

        val membersInfo =
            revoltServersRepository.fetchServerMembers(serverInfo.serverId)
        if (membersInfo is Either.Error) return prevState.copy(error = membersInfo.value)

        val members = membersInfo as Either.Success
        val users = members.value.users.zip(members.value.members)
            .associate { (user, member) ->
                Log.d(
                    "CHANNEL VIEW",
                    "Found user ${user.userId}, @${user.userName}#${user.discriminator}"
                )
                user.userId to (user to member)
            }

        val messages = revoltMessagesRepository.fetchChannelMessages(
            channelId,
            limit = 50
        )
        if (messages is Either.Error) return prevState.copy(error = messages.value)

        return prevState.copy(
            channelId = channelId,
            channelInfo = channelInfo,
            serverInfo = serverInfo,
            users = users,
            messages = (messages as Either.Success).value

        )
    }

    suspend fun fetchEarlierMessages() {
        Log.d("CHANNEL VIEW", "Fetching earlier messages")
        if (uiState.value.channelId != null)
            revoltMessagesRepository.fetchChannelMessages(
                uiState.value.channelId!!,
                limit = 50,
                before = uiState.value.messages.last().messageId
            )
    }
}
