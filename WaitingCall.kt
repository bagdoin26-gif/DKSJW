
package com.delivery.callwatch

data class WaitingCall(
    val id: String,
    val address: String,
    val distance: String,
    val amount: String
)

object WaitingCallStore {

    private var previousCalls = emptySet<String>()

    fun update(calls: List<WaitingCall>): List<WaitingCall> {
        val currentIds = calls.map { it.id }.toSet()

        val newCalls = calls.filter {
            it.id !in previousCalls
        }

        previousCalls = currentIds

        return newCalls
    }

    fun reset() {
        previousCalls = emptySet()
    }
}
