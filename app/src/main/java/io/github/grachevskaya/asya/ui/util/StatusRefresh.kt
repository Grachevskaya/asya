package io.github.grachevskaya.asya.ui.util

import androidx.lifecycle.MutableLiveData

/** Raised when a package change has been handed to the service, whose table follows a moment later. */
object StatusRefresh {
    val generation = MutableLiveData(0)

    fun trigger() {
        generation.postValue((generation.value ?: 0) + 1)
    }
}
