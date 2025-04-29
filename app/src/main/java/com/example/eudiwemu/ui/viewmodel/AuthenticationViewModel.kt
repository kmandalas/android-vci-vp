package com.example.eudiwemu.ui.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

class AuthenticationViewModel : ViewModel() {

    var isAuthenticated = mutableStateOf(false)

    fun authenticateSuccess() {
        isAuthenticated.value = true
    }

}




