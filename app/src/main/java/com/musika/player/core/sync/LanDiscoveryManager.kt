package com.musika.player.core.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredLanPeer(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val accountHash: String,
    val host: String,
    val port: Int
)

/**
 * Bidirectional mDNS / DNS-SD discovery via Android [NsdManager] on `_musika._tcp.`.
 * Advertises this phone to Desktop/Web peers and discovers active Musika Desktop or phone peers on the LAN.
 */
class LanDiscoveryManager(
    private val context: Context,
    private val localDeviceId: String
) {
    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    private val wifiManager: WifiManager? =
        runCatching { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredLanPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredLanPeer>> = _discoveredPeers.asStateFlow()

    fun start(port: Int, deviceName: String, accountHash: String) {
        val mgr = nsdManager ?: return
        acquireMulticastLock()
        registerService(mgr, port, deviceName, accountHash)
        startDiscovery(mgr)
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("MusikaMdnsLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun registerService(mgr: NsdManager, port: Int, deviceName: String, accountHash: String) {
        unregisterService(mgr)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Musika-$localDeviceId"
            serviceType = SERVICE_TYPE
            setPort(port)
            runCatching {
                setAttribute("deviceId", localDeviceId)
                setAttribute("deviceName", deviceName.take(48))
                setAttribute("deviceType", "ANDROID")
                setAttribute("accountHash", accountHash)
                setAttribute("port", port.toString())
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i("MusikaNsd", "Registered mDNS service: ${info.serviceName} port=$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w("MusikaNsd", "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun startDiscovery(mgr: NsdManager) {
        stopDiscovery(mgr)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("MusikaNsd", "mDNS start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.contains(localDeviceId, ignoreCase = true)) return
                runCatching {
                    @Suppress("DEPRECATION")
                    mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = resolved.host?.hostAddress ?: return
                            val attrs = resolved.attributes
                            val remoteDeviceId = attrs["deviceId"]?.let { String(it) } ?: resolved.serviceName
                            if (remoteDeviceId == localDeviceId) return
                            val peer = DiscoveredLanPeer(
                                deviceId = remoteDeviceId,
                                deviceName = attrs["deviceName"]?.let { String(it) } ?: resolved.serviceName,
                                deviceType = attrs["deviceType"]?.let { String(it) } ?: "DESKTOP",
                                accountHash = attrs["accountHash"]?.let { String(it) }.orEmpty(),
                                host = host,
                                port = attrs["port"]?.let { String(it).toIntOrNull() } ?: resolved.port
                            )
                            _discoveredPeers.value =
                                (_discoveredPeers.value.filterNot { it.deviceId == peer.deviceId } + peer)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredPeers.value = _discoveredPeers.value.filterNot {
                    serviceInfo.serviceName.contains(it.deviceId, ignoreCase = true)
                }
            }
        }
        discoveryListener = listener
        runCatching {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterService(mgr: NsdManager) {
        registrationListener?.let { listener ->
            runCatching { mgr.unregisterService(listener) }
        }
        registrationListener = null
    }

    private fun stopDiscovery(mgr: NsdManager) {
        discoveryListener?.let { listener ->
            runCatching { mgr.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
    }

    fun stop() {
        nsdManager?.let { mgr ->
            unregisterService(mgr)
            stopDiscovery(mgr)
        }
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_musika._tcp."
    }
}
