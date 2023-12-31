package io.github.alexispurslane.bloc.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.alexispurslane.bloc.Either
import io.github.alexispurslane.bloc.data.network.RevoltApiModule
import io.github.alexispurslane.bloc.data.network.RevoltWebSocketModule
import io.github.alexispurslane.bloc.data.network.models.RevoltMessage
import io.github.alexispurslane.bloc.data.network.models.RevoltMessageSent
import io.github.alexispurslane.bloc.data.network.models.RevoltWebSocketResponse
import io.github.alexispurslane.bloc.findIndex
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(DelicateCoroutinesApi::class)
@Singleton
class RevoltMessagesRepository @Inject constructor(
    private val revoltAccountsRepository: RevoltAccountsRepository
) {
    var _channelMessages: MutableMap<String, SnapshotStateList<RevoltMessage>> =
        mutableMapOf()
        private set
    val channelMessages
        get(): Map<String, SnapshotStateList<RevoltMessage>> = _channelMessages

    val existingMessageIds: HashSet<String> = hashSetOf()

    private val USER_MENTION_REGEX by lazy { Regex("<@([a-zA-Z0-9]+)>") }
    private suspend fun treatMessage(message: RevoltMessage): RevoltMessage =
        coroutineScope {
            val userInformation = (message.mentionedIds?.plus(
                message.systemEventMessage?.let { USER_MENTION_REGEX.find(it.message) }?.groupValues
                    ?: emptyList()
            ))?.map { userId: String ->
                async {
                    when (val u =
                        revoltAccountsRepository.fetchUserInformation(userId)) {
                        is Either.Success -> {
                            u.value.userId to u.value
                        }

                        is Either.Error -> {
                            null
                        }
                    }
                }
            }?.awaitAll()?.filterNotNull()?.toMap() ?: emptyMap()
            val newContent = message.content?.replace(USER_MENTION_REGEX) {
                val user = userInformation[it.groupValues[1]]
                "[@${user?.userName ?: it.value}](bloc://profile/${user?.userId})"
            }
            message.systemEventMessage?.let {
                it.message =
                    it.message.replace(USER_MENTION_REGEX) {
                        val user = userInformation[it.groupValues[1]]
                        val id =
                            if (user != null) "@${user.userName}" else it.value
                        "[${id}](bloc://profile/${it.groupValues[1]})"
                    }
            }
            message.copy(
                content = newContent,
                systemEventMessage = message.systemEventMessage
            )
        }

    init {
        GlobalScope.launch(Dispatchers.IO) {
            RevoltWebSocketModule.eventFlow.collect { event ->
                when (event) {
                    is RevoltWebSocketResponse.Message -> {
                        launch {
                            if (!existingMessageIds.contains(event.message.messageId)) {
                                _channelMessages[event.message.channelId]?.apply {
                                    add(0, treatMessage(event.message))
                                }
                                existingMessageIds.add(event.message.messageId)
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    suspend fun sendMessage(
        channelId: String,
        message: RevoltMessageSent
    ): Either<RevoltMessage, String> {
        val userSession = revoltAccountsRepository.userSessionFlow.first()
        if (userSession.sessionToken == null) {
            return Either.Error(
                "Uh oh! Your user session token is null:You'll have to sign out and sign back in again."
            )
        }
        return try {
            val res = RevoltApiModule.service().sendMessage(
                sessionToken = userSession.sessionToken,
                channelId = channelId,
                message = message
            )
            val body = res.body()!!
            val errorBody = (res.errorBody() ?: res.errorBody())?.string()
            if (res.isSuccessful) {
                Either.Success(body)
            } else if (errorBody != null) {
                val jsonObject = JSONObject(errorBody.trim())
                Either.Error(
                    "Uh oh! ${res.message()}:The server error was '${
                        jsonObject.getString(
                            "type"
                        )
                    }'"
                )
            } else {
                Either.Error(
                    "Uh oh! The server returned an error:${res.message()}"
                )
            }
        } catch (e: Exception) {
            Either.Error(
                "Uh oh! Was unable to send a message to the server: ${e.message}"
            )
        }
    }

    suspend fun fetchChannelMessages(
        channelId: String,
        limit: Int? = null,
        before: String? = null,
        after: String? = null,
        sort: String? = null,
        nearby: String? = null,
        includeUsers: Boolean? = null
    ): Either<SnapshotStateList<RevoltMessage>, String> = coroutineScope {
        with(Dispatchers.IO) {
            val userSession = revoltAccountsRepository.userSessionFlow.first()
            if (userSession.sessionToken == null) {
                Either.Error(
                    "Uh oh! Your user session token is null:You'll have to sign out and sign back in again."
                )
            } else {
                try {
                    val res = RevoltApiModule.service().fetchMessages(
                        userSession.sessionToken,
                        channelId,
                        limit,
                        before,
                        after,
                        sort,
                        nearby,
                        includeUsers
                    )
                    val body = res.body()!!.map {
                        async {
                            treatMessage(it)
                        }
                    }.awaitAll()
                    val errorBody =
                        (res.errorBody() ?: res.errorBody())?.string()
                    if (res.isSuccessful) {
                        if (_channelMessages.containsKey(channelId)) {
                            if (nearby == null && includeUsers == null && sort != "Relevance" && body.isNotEmpty()) {
                                _channelMessages[channelId]!!.apply {
                                    integrateMessages(
                                        this,
                                        body,
                                        before,
                                        after,
                                        sort
                                    )
                                }
                            }
                        } else {
                            _channelMessages.getOrPut(
                                channelId,
                                { mutableStateListOf() }).apply {
                                addAll(body)
                            }
                        }
                        Either.Success(channelMessages[channelId]!!)
                    } else if (errorBody != null) {
                        val jsonObject = JSONObject(errorBody.trim())
                        Either.Error(
                            "Uh oh! ${res.message()}:The server error was '${
                                jsonObject.getString(
                                    "type"
                                )
                            }'"
                        )
                    } else {
                        Either.Error(
                            "Uh oh! The server returned an error:${res.message()}"
                        )
                    }
                } catch (e: Exception) {
                    Either.Error(
                        "Uh oh! Was unable to send request for messages to the server: ${e.message}"
                    )
                }
            }
        }
    }

    private fun integrateMessages(
        revoltMessages: SnapshotStateList<RevoltMessage>,
        newMessages: List<RevoltMessage>,
        before: String?,
        after: String?,
        sort: String?,
    ) {
        val sortedNewMessages = when (sort) {
            "Latest" -> {
                newMessages.reversed()
            }

            "Oldest" -> {
                newMessages
            }

            else -> {
                newMessages
            }
        }
        revoltMessages.apply {
            if (before != null) {
                val index =
                    findIndex { _, element -> element.messageId == before }
                        ?: sortedNewMessages.size
                addAll(index + 1, sortedNewMessages)
            } else if (after != null) {
                val index =
                    findIndex { _, element -> element.messageId == after!! }
                        ?: 0
                addAll(index, sortedNewMessages)
            }
        }
    }

}