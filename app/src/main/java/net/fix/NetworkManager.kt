package net.fix

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class NetworkManager(val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val packageManager = context.packageManager

    /**
     * Checks if the device is connected to Wi-Fi.
     * @return True if connected to Wi-Fi, false otherwise.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun getActiveNetworkType(): Boolean {
        // Check Wi-Fi connection directly
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val wifiInfo = wifiManager.connectionInfo
                Log.d("NetworkManager", "getActiveNetworkType: wifiInfo=$wifiInfo")
                // Check if Wi-Fi is connected using SupplicantState and RSSI
                if (wifiInfo != null && wifiInfo.supplicantState == SupplicantState.COMPLETED && wifiInfo.rssi > -100) {
                    return true
                }
            } catch (e: SecurityException) {
                Log.e("NetworkManager", "getActiveNetworkType: SecurityException - ${e.message}")
            }
        }

        // Check network capabilities as a fallback
        val network = connectivityManager.activeNetwork
        val capabilities = if (network != null) connectivityManager.getNetworkCapabilities(network) else null
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        Log.d("NetworkManager", "getActiveNetworkType: isWifi from capabilities=$isWifi")
        return isWifi
    }

    /**
     * Tests internet connectivity by پinging Google's DNS.
     * @return True if ping is successful.
     */
    fun testConnection(): Boolean {
        return try {
            val address = InetAddress.getByName("8.8.8.8")
            address.isReachable(5000) // 5 seconds timeout
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the Wi-Fi adapter name (root only).
     * @return The adapter name (e.g., wlan0) or default "wlan0" if not found.
     */
    fun getWifiAdapter(): String {
        if (!isRootAvailable()) return "wlan0"
        val result = runCommand("iwconfig")
        return result.lines().firstOrNull { "wlan" in it }?.split(" ")?.firstOrNull { it.isNotBlank() } ?: "wlan0"
    }

    /**
     * Checks if root access is available.
     * @return True if root is available.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { outputStream ->
                outputStream.writeBytes("whoami\nexit\n")
                outputStream.flush()
            }
            process.waitFor(5, TimeUnit.SECONDS) && process.inputStream.bufferedReader().use { it.readText() }.contains("root", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Toggles mobile data (non-root method, may not work on all devices).
     * @param enabled True to enable, false to disable.
     * @return True if successful, false otherwise.
     */
    fun toggleMobileData(enabled: Boolean): Boolean {
        return try {
            val method = telephonyManager.javaClass.getMethod("setDataEnabled", Boolean::class.java)
            method.invoke(telephonyManager, enabled)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Toggles Wi-Fi state (non-root).
     * @param enabled True to enable, false to disable.
     * @return True if successful, false otherwise.
     */
    fun toggleWifi(enabled: Boolean): Boolean {
        return try {
            wifiManager.isWifiEnabled = enabled
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets network information (Wi-Fi, mobile, VPN status).
     * @return Formatted string with network details.
     */
    fun getNetworkInfo(): String {
        val sb = StringBuilder()
        var isWifiConnected = false

        // Check Wi-Fi status
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val wifiInfo = wifiManager.connectionInfo
                Log.d("NetworkManager", "getNetworkInfo: wifiInfo=$wifiInfo")
                if (wifiInfo != null && wifiInfo.supplicantState == SupplicantState.COMPLETED && wifiInfo.rssi > -100) {
                    isWifiConnected = true
                    val signalLevel = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                    sb.append("Wi-Fi: Connected (Signal: $signalLevel/5)\n")
                } else if (wifiManager.isWifiEnabled) {
                    sb.append("Wi-Fi: Enabled but not connected\n")
                } else {
                    sb.append("Wi-Fi: Disabled\n")
                }
            } catch (e: SecurityException) {
                Log.e("NetworkManager", "getNetworkInfo: SecurityException - ${e.message}")
                sb.append("Wi-Fi: Unable to access info (Permission denied)\n")
            }
        } else {
            sb.append("Wi-Fi: Permission required for info\n")
        }

        // Check Mobile network
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val networkType = when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "Unknown"
            }
            sb.append("Mobile: $networkType\n")
        } else {
            sb.append("Mobile: Permission required for info\n")
        }

        // Check VPN status
        val network = connectivityManager.activeNetwork
        val capabilities = if (network != null) connectivityManager.getNetworkCapabilities(network) else null
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            sb.append("VPN: Active\n")
        } else {
            sb.append("VPN: Inactive\n")
        }

        val result = sb.toString()
        Log.d("NetworkManager", "getNetworkInfo: $result")
        return result
    }

    /**
     * Resets network settings (Wi-Fi, mobile, Bluetooth) by guiding user to settings.
     */
    fun resetNetworkSettings() {
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    /**
     * Diagnoses network issues for Wi-Fi or Mobile, supporting both rooted and non-rooted devices.
     * @param isWifi True for Wi-Fi, false for Mobile.
     * @return List of detected issues.
     */
    fun diagnoseNetwork(isWifi: Boolean): List<String> {
        val issues = mutableListOf<String>()
        val messages = mutableListOf<SpannableMessage>()

        messages.add(SpannableMessage("[*] Starting diagnostics...\n", MessageType.WARNING))

        // Check root access
        val isRooted = isRootAvailable()
        if (!isRooted) {
            messages.add(SpannableMessage("[!] Limited diagnostics without root access\n", MessageType.WARNING))
        }

        // Check internet connection
        if (!testConnection()) {
            issues.add("No internet connection detected.")
            messages.add(SpannableMessage("[-] No internet connection detected\n", MessageType.ERROR))
        } else {
            messages.add(SpannableMessage("[+] Internet connection is active\n", MessageType.SUCCESS))
        }

        // Non-root diagnostics
        if (isWifi) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val wifiInfo = wifiManager.connectionInfo
                    Log.d("NetworkManager", "diagnoseNetwork: wifiInfo=$wifiInfo")
                    if (!wifiManager.isWifiEnabled) {
                        issues.add("Wi-Fi is disabled.")
                        messages.add(SpannableMessage("[-] Wi-Fi is disabled\n", MessageType.ERROR))
                    } else if (wifiInfo == null || wifiInfo.supplicantState != SupplicantState.COMPLETED || wifiInfo.rssi <= -100) {
                        issues.add("Wi-Fi is enabled but not connected.")
                        messages.add(SpannableMessage("[-] Wi-Fi is enabled but not connected\n", MessageType.ERROR))
                    } else {
                        messages.add(SpannableMessage("[+] Wi-Fi is connected\n", MessageType.SUCCESS))
                    }
                } catch (e: SecurityException) {
                    issues.add("Wi-Fi: Unable to access info (Permission denied)")
                    messages.add(SpannableMessage("[-] Wi-Fi: Unable to access info (Permission denied)\n", MessageType.ERROR))
                }
            } else {
                issues.add("Wi-Fi: Permission required for diagnostics")
                messages.add(SpannableMessage("[-] Wi-Fi: Permission required for diagnostics\n", MessageType.ERROR))
            }
        } else {
            if (telephonyManager.dataState == TelephonyManager.DATA_DISCONNECTED) {
                issues.add("Mobile data is disabled.")
                messages.add(SpannableMessage("[-] Mobile data is disabled\n", MessageType.ERROR))
            } else {
                messages.add(SpannableMessage("[+] Mobile data is enabled\n", MessageType.SUCCESS))
            }
        }

        // Check VPN (non-root)
        val vpnActive = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (vpnActive) {
            messages.add(SpannableMessage("[+] VPN is active\n", MessageType.SUCCESS))
            val vpnApps = mutableSetOf<String>()
            packageManager.getInstalledApplications(0).forEach { app ->
                val pkgName = app.packageName
                if (pkgName.contains("vpn", ignoreCase = true) || pkgName.contains("openvpn", ignoreCase = true) ||
                    pkgName.contains("invi", ignoreCase = true)) {
                    vpnApps.add(packageManager.getApplicationLabel(app).toString())
                }
            }
            if (vpnApps.isNotEmpty()) {
                messages.add(SpannableMessage("[+] VPN app(s): ${vpnApps.joinToString(", ")}\n", MessageType.SUCCESS))
            }
        }

        if (isRooted) {
            // Root-specific diagnostics
            val iptables = runCommand("iptables -L -n")
            if ("DROP" in iptables || "REJECT" in iptables) {
                val blockingApps = mutableSetOf<String>()
                val uidPattern = Regex("owner UID match (\\d+)")
                val blockedUids = uidPattern.findAll(iptables).map { it.groupValues[1].toInt() }.toSet()

                blockedUids.forEach { uid ->
                    if (uid < 1000) return@forEach // Skip system UIDs
                    try {
                        val pkgNames = packageManager.getPackagesForUid(uid)
                        if (!pkgNames.isNullOrEmpty()) {
                            val appInfo = packageManager.getApplicationInfo(pkgNames[0], 0)
                            val appName = packageManager.getApplicationLabel(appInfo).toString()
                            if (appName.isNotEmpty()) blockingApps.add(appName)
                        } else {
                            blockingApps.add("Unknown app (UID $uid)")
                        }
                    } catch (e: Exception) {
                        blockingApps.add("Unknown app (UID $uid)")
                    }
                }

                if ("afwall" in iptables.lowercase()) {
                    blockingApps.add("AFWall+")
                }

                if (blockingApps.isNotEmpty()) {
                    val issueText = "Firewall rules blocking traffic by:\n${blockingApps.joinToString(", ")}\n"
                    issues.add(issueText)
                    messages.add(SpannableMessage("[-] $issueText\n", MessageType.ERROR))
                }
            }

            if (isWifi) {
                val adapter = getWifiAdapter()
                val linkStatus = runCommand("ip link show $adapter")
                if ("DOWN" in linkStatus) {
                    issues.add("Wi-Fi adapter ($adapter) is down.")
                    messages.add(SpannableMessage("[-] Wi-Fi adapter ($adapter) is down\n", MessageType.ERROR))
                }
            }

            if (issues.contains("No internet connection detected.")) {
                val dnsTest = runCommand("ping -c 4 8.8.8.8", requireRoot = false)
                if ("4 received" !in dnsTest) {
                    issues.add("DNS resolution failure.")
                    messages.add(SpannableMessage("[-] DNS resolution failure\n", MessageType.ERROR))
                }
            }
        }

        if (issues.isEmpty()) {
            messages.add(SpannableMessage("[+] No issues detected\n", MessageType.SUCCESS))
        }

        return issues
    }

    /**
     * Executes a shell command with optional root privileges.
     * @param command The shell command to execute.
     * @param requireRoot Whether root access is required.
     * @param timeoutSec Timeout duration in seconds.
     * @return The command output or error message.
     */
    fun runCommand(command: String, requireRoot: Boolean = true, timeoutSec: Long = 5): String {
        if (!isValidCommand(command)) {
            return "Invalid command: $command"
        }
        if (requireRoot && !isRootAvailable()) {
            return "Root access required for command: $command"
        }
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(if (requireRoot) "su" else command)
            if (requireRoot) {
                DataOutputStream(process.outputStream).use { outputStream ->
                    outputStream.writeBytes("$command\nexit\n")
                    outputStream.flush()
                }
            }
            val exited = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                return "Command '$command' timed out after ${timeoutSec}s"
            }
            return process.inputStream.bufferedReader().use { it.readText() }
                .ifEmpty { process.errorStream.bufferedReader().use { it.readText() } }
                .ifEmpty { "No output from command" }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        } finally {
            process?.destroy()
        }
    }

    private fun isValidCommand(command: String): Boolean {
        return command.matches(Regex("^[a-zA-Z0-9\\s\\-_/|&;]*$"))
    }
}