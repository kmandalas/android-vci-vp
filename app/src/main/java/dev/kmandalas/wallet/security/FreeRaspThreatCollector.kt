package dev.kmandalas.wallet.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges async freeRASP threat callbacks to a reactive [StateFlow].
 * Reported threats accumulate until [clear] is called.
 */
object FreeRaspThreatCollector {

    private val _threats = MutableStateFlow<Set<String>>(emptySet())
    val threats: StateFlow<Set<String>> = _threats.asStateFlow()

    fun report(threat: String) {
        _threats.update { it + threat }
    }

    fun clear() {
        _threats.value = emptySet()
    }
}
