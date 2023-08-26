package io.github.alexispurslane.bloc.data.networking

import io.github.alexispurslane.bloc.data.networking.models.LoginResponse
import io.github.alexispurslane.bloc.data.networking.models.LoginRequest
import io.github.alexispurslane.bloc.data.networking.models.QueryNodeResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface RevoltApiService {
    @GET
    suspend fun queryNode(@Url baseUrl: String): Response<QueryNodeResponse>


    // Have to duplicate this since the DEDUCTIVE type discriminator isn't in
    // this version of Jackson.
    @POST("auth/session/login")
    @Headers(
        "Accept: application/json",
        "Content-type: application/json",
    )
    suspend fun login(@Body login: LoginRequest.Basic): Response<LoginResponse>

    @POST("auth/session/login")
    @Headers(
        "Accept: application/json",
        "Content-type: application/json",
    )
    suspend fun login(@Body login: LoginRequest.MFA): Response<LoginResponse>
}