package io.github.alexispurslane.bloc.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.alexispurslane.bloc.Either
import io.github.alexispurslane.bloc.data.network.RevoltApiModule
import io.github.alexispurslane.bloc.data.network.RevoltWebSocketModule
import io.github.alexispurslane.bloc.data.network.models.LoginRequest
import io.github.alexispurslane.bloc.data.network.models.LoginResponse
import io.github.alexispurslane.bloc.data.network.models.QueryNodeResponse
import io.github.alexispurslane.bloc.data.network.models.RevoltUser
import io.github.alexispurslane.bloc.data.network.models.RevoltWebSocketResponse
import io.github.alexispurslane.bloc.data.network.models.UserProfile
import io.github.alexispurslane.bloc.data.network.models.WebPushSubscriptionResponse
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class UserSession(
    val instanceApiUrl: String? = null,
    val websocketsUrl: String? = null,
    val autumnUrl: String? = null,
    val emailAddress: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val sessionToken: String? = null,
    val displayName: String? = null,
    val preferences: Map<String, String> = emptyMap()
)

@OptIn(DelicateCoroutinesApi::class)
@Singleton
class RevoltAccountsRepository @Inject constructor(
    private val settingsLocalDataSource: DataStore<Preferences>,
) {
    private val mapper = ObjectMapper()

    var userSessionFlow: Flow<UserSession> =
        settingsLocalDataSource.data.catch {
            if (it is IOException)
                emit(emptyPreferences())
            else
                throw it
        }.map {
            UserSession(
                instanceApiUrl = it[PreferenceKeys.INSTANCE_API_URL],
                websocketsUrl = it[PreferenceKeys.WEBSOCKETS_URL],
                autumnUrl = it[PreferenceKeys.AUTUMN_URL],
                emailAddress = it[PreferenceKeys.EMAIL],
                sessionId = it[PreferenceKeys.SESSION_ID],
                userId = it[PreferenceKeys.USER_ID],
                sessionToken = it[PreferenceKeys.SESSION_TOKEN],
                displayName = it[PreferenceKeys.DISPLAY_NAME],
                preferences = try {
                    mapper.readValue(
                        it[PreferenceKeys.SESSION_PREFERENCES],
                        object : TypeReference<Map<String, String>>() {}
                    )
                } catch (e: Exception) {
                    emptyMap()
                }
            )
        }

    private var userProfileCache: MutableMap<String, RevoltUser> =
        mutableMapOf()

    var webPushSubscription: MutableStateFlow<WebPushSubscriptionResponse?> =
        MutableStateFlow(null)

    init {
        GlobalScope.launch(Dispatchers.IO) {
            RevoltWebSocketModule.eventFlow.collect {
                onWebSocketEvent(it)
            }
        }
    }

    private fun onWebSocketEvent(event: RevoltWebSocketResponse): Boolean {
        return true
    }

    suspend fun queryNode(baseUrl: String): Response<QueryNodeResponse> {
        return RevoltApiModule.service().queryNode(baseUrl)
    }

    suspend fun login(
        baseUrl: String,
        emailAddress: String,
        ws: String,
        autumn: String,
        loginRequest: LoginRequest
    ): Either<LoginResponse, String> {
        return try {
            val res = when (loginRequest) {
                is LoginRequest.Basic -> RevoltApiModule.service()
                    .login(loginRequest)

                is LoginRequest.MFA -> RevoltApiModule.service()
                    .login(loginRequest)
            }

            if (res.isSuccessful) {
                val loginResponse = res.body()!!
                saveLoginInfo(
                    baseUrl,
                    emailAddress,
                    ws,
                    autumn,
                    loginResponse
                )
                Either.Success(loginResponse)
            } else {
                val errorBody = res.errorBody()?.string()
                if (errorBody != null) {
                    val jsonObject = JSONObject(errorBody.trim())
                    Either.Error(
                        "Uh oh! ${res.message()}:The server error was '${
                            jsonObject.getString(
                                "type"
                            )
                        }'"
                    )
                } else {
                    Either.Error("Uh oh! The server returned an error:${res.message()}")
                }
            }
        } catch (e: Exception) {
            Either.Error("Uh oh! Could not send login request:${e.message!!}")
        }
    }

    private suspend fun saveLoginInfo(
        api: String,
        email: String,
        ws: String,
        autumn: String,
        loginResponse: LoginResponse
    ) {
        settingsLocalDataSource.edit { preferences ->
            preferences[PreferenceKeys.INSTANCE_API_URL] = api
            preferences[PreferenceKeys.EMAIL] = email
            preferences[PreferenceKeys.WEBSOCKETS_URL] = ws
            preferences[PreferenceKeys.AUTUMN_URL] = autumn
            Log.d("DATA STORE", "Saved: $api, $email")

            if (loginResponse is LoginResponse.Success) {
                webPushSubscription.update {
                    loginResponse.webPushSubscription
                }
                preferences[PreferenceKeys.SESSION_ID] = loginResponse.id
                preferences[PreferenceKeys.USER_ID] = loginResponse.userId
                preferences[PreferenceKeys.SESSION_TOKEN] =
                    loginResponse.sessionToken
                preferences[PreferenceKeys.DISPLAY_NAME] =
                    loginResponse.displayName
            }
        }
    }

    suspend fun fetchUserInformation(userId: String = "@me"): Either<RevoltUser, String> {
        val userSession = userSessionFlow.first()
        if (userSession.sessionToken != null) {
            if (userProfileCache.containsKey(userId)) {
                return Either.Success(userProfileCache[userId]!!)
            }
            try {
                val res = RevoltApiModule.service()
                    .fetchUser(userSession.sessionToken, userId)
                val res2: Response<UserProfile>
                if (res.isSuccessful) {
                    val user = res.body()!!
                    if (user.profile == null) {
                        res2 = RevoltApiModule.service()
                            .fetchUserProfile(
                                userSession.sessionToken,
                                user.userId
                            )
                        if (res2.isSuccessful) {
                            user.profile = res2.body()!!
                            userProfileCache[userId] = user
                            return Either.Success(user)
                        }
                    } else {
                        userProfileCache[userId] = user
                        return Either.Success(user)
                    }
                }

                val errorBody = (res.errorBody() ?: res.errorBody())?.string()
                if (errorBody != null) {
                    val jsonObject = JSONObject(errorBody.trim())
                    return Either.Error(
                        "Uh oh! ${res.message()}:The server error was '${
                            jsonObject.getString(
                                "type"
                            )
                        }'"
                    )
                } else {
                    return Either.Error("Uh oh! The server returned an error:${res.message()}")
                }
            } catch (e: Exception) {
                return Either.Error("Uh oh! Could not send profile info request:${e.message!!}")
            }
        } else {
            return Either.Error("Uh oh! Your user session token is null:You'll have to sign out and sign back in again.")
        }
    }

    suspend fun clearSession() {
        settingsLocalDataSource.edit {
            it.clear()
        }
    }

    suspend fun savePreferences(prefs: Map<String, String>) {
        settingsLocalDataSource.edit {
            val prevPrefs = try {
                mapper.readValue(
                    it[PreferenceKeys.SESSION_PREFERENCES],
                    prefs::class.java
                )
            } catch (e: Exception) {
                Log.e(
                    "ACCOUNT REPO",
                    "Can't deserialize preferences: ${e}"
                )
                emptyMap()
            }
            val newPrefs = prevPrefs + prefs
            try {
                it[PreferenceKeys.SESSION_PREFERENCES] =
                    mapper.writeValueAsString(newPrefs)
            } catch (e: Exception) {
                Log.e("ACCOUNT REPO", "Cannot save preferences: $newPrefs, $e")
            }
        }
    }
}

object PreferenceKeys {
    val AUTUMN_URL = stringPreferencesKey("autumn_url")
    val EMAIL = stringPreferencesKey("email")
    val INSTANCE_API_URL = stringPreferencesKey("instance_api_url")
    val WEBSOCKETS_URL = stringPreferencesKey("websockets_url")
    val SESSION_ID = stringPreferencesKey("session_id")
    val USER_ID = stringPreferencesKey("user_id")
    val SESSION_TOKEN = stringPreferencesKey("session_token")
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val SESSION_PREFERENCES = stringPreferencesKey("session_preferences")
}