package net.fix

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class NetworkState {
    object Idle : NetworkState()
    object Loading : NetworkState()
    data class Result(val messages: List<SpannableMessage>) : NetworkState()
    data class Error(val message: String) : NetworkState()
}

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val networkManager = NetworkManager(application)
    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Idle)
    val networkState = _networkState.asStateFlow()

    /**
     * Diagnoses the active network (Wi-Fi or Mobile) and reports issues.
     */
    fun diagnoseNetwork() {
        viewModelScope.launch {
            _networkState.value = NetworkState.Loading
            try {
                val isWifi = networkManager.getActiveNetworkType()
                val issues = networkManager.diagnoseNetwork(isWifi)
                val messages = issues.map { SpannableMessage(it, MessageType.INFO) }
                _networkState.value = NetworkState.Result(messages)
            } catch (e: Exception) {
                _networkState.value = NetworkState.Error("Diagnosis failed: ${e.message}")
            }
        }
    }

    /**
     * Resets the specified network (Wi-Fi or Mobile).
     * @param isWifi True for Wi-Fi, false for Mobile.
     */
    fun resetNetwork(isWifi: Boolean) {
        viewModelScope.launch {
            _networkState.value = NetworkState.Loading
            val messages = mutableListOf<SpannableMessage>()
            try {
                messages.add(SpannableMessage("[*] Starting ${if (isWifi) "Wi-Fi" else "Mobile"} Network Reset...\n", MessageType.WARNING))
                val issues = networkManager.diagnoseNetwork(isWifi)
                messages.addAll(issues.map { SpannableMessage(it, MessageType.INFO) })

                if (isWifi) {
                    resetWifiNetwork(messages)
                } else {
                    resetMobileNetwork(messages)
                }
                _networkState.value = NetworkState.Result(messages)
            } catch (e: Exception) {
                messages.add(SpannableMessage("[!] Reset failed: ${e.message}\n", MessageType.ERROR))
                _networkState.value = NetworkState.Result(messages)
            }
        }
    }

    private suspend fun resetWifiNetwork(messages: MutableList<SpannableMessage>) {
        val adapter = networkManager.getWifiAdapter()
        val steps = mutableListOf(
            "Checking adapter status" to "ip link show $adapter",
            "Disabling adapter" to "ip link set $adapter down",
            "Enabling adapter" to "ip link set $adapter up",
            "Resetting ARP table" to "arp -d"
        )

        networkManager.diagnoseNetwork(true).forEach { issue ->
            when {
                "Firewall" in issue -> steps.add("Clearing firewall rules" to "iptables -F")
                "DNS" in issue -> steps.add("Flushing DNS cache" to "ndc resolver flushnet $adapter")
                "traffic" in issue.lowercase() -> steps.add("Resetting network interfaces" to "ip link set $adapter down && ip link set $adapter up")
            }
        }

        steps.addAll(
            listOf(
                "Restarting network service" to "service network restart",
                "Setting adapter to Managed Mode" to "iwconfig $adapter mode managed && ifconfig $adapter up"
            )
        )

        for ((stepName, command) in steps) {
            messages.add(SpannableMessage("[*] $stepName...\n", MessageType.WARNING))
            val result = withContext(Dispatchers.IO) { networkManager.runCommand(command) }
            messages.add(SpannableMessage("  Result: $result\n", MessageType.INFO))
            if (stepName != "Checking adapter status") {
                delay(2000)
                if (networkManager.testConnection()) {
                    messages.add(SpannableMessage("[+] Wi-Fi Network Fixed!\n", MessageType.SUCCESS))
                    return
                }
            }
        }
        messages.add(SpannableMessage("[!] Could not fix Wi-Fi network. Try restarting device.\n", MessageType.ERROR))
    }

    private suspend fun resetMobileNetwork(messages: MutableList<SpannableMessage>) {
        if (networkManager.isRootAvailable()) {
            val steps = mutableListOf(
                "Checking mobile data status" to "getprop | grep gsm",
                "Disabling mobile data" to "svc data disable",
                "Enabling mobile data" to "svc data enable"
            )

            networkManager.diagnoseNetwork(false).forEach { issue ->
                when {
                    "Firewall" in issue -> steps.add("Clearing firewall rules" to "iptables -F")
                    "DNS" in issue -> steps.add("Flushing DNS cache" to "ndc resolver flushdefaultif")
                    "traffic" in issue.lowercase() -> steps.add("Resetting mobile interface" to "ndc interface clearaddrs rmnet0")
                }
            }

            steps.add("Resetting mobile network" to "ndc interface clearaddrs rmnet0")

            for ((stepName, command) in steps) {
                messages.add(SpannableMessage("[*] $stepName...\n", MessageType.WARNING))
                val result = withContext(Dispatchers.IO) { networkManager.runCommand(command) }
                messages.add(SpannableMessage("  Result: $result\n", MessageType.INFO))
                delay(2000)
                if (networkManager.testConnection()) {
                    messages.add(SpannableMessage("[+] Mobile Network Fixed!\n", MessageType.SUCCESS))
                    return
                }
            }
            messages.add(SpannableMessage("[!] Could not fix mobile network. Try restarting device.\n", MessageType.ERROR))
        } else {
            messages.add(SpannableMessage("[*] Attempting non-root mobile reset...\n", MessageType.WARNING))
            try {
                networkManager.toggleMobileData(false)
                delay(2000)
                networkManager.toggleMobileData(true)
                messages.add(SpannableMessage("[+] Mobile data toggled successfully!\n", MessageType.SUCCESS))
            } catch (e: Exception) {
                messages.add(SpannableMessage("[!] Error toggling mobile data: ${e.message}\n[!] Please toggle manually.\n", MessageType.ERROR))
            }
        }
    }
}