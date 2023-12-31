package io.github.alexispurslane.bloc.data.local

import android.content.Context
import io.github.alexispurslane.bloc.data.network.models.AutumnFile
import kotlinx.coroutines.runBlocking

object RevoltAutumnModule {

    private var autumnUrl: String? = null

    fun setUrl(url: String) {
        autumnUrl = url
    }

    fun getResourceUrl(context: Context, file: AutumnFile): String? {
        return runBlocking {
            autumnUrl?.let {
                "${it}/${file.fileTag}/${file.fileId}"
            }
        }
    }
}