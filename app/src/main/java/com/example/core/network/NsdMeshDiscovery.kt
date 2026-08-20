package com.example.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NsdMeshDiscovery(
    private val context: Context,
    private val onPeerDiscovered: (serviceName: String, hostAddress: String, port: Int) -> Unit
) {
    private val tag = "NsdMeshDiscovery"
    private val serviceType = "_rin-mesh._tcp."
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null
    private var isDiscovering = false

    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    fun registerService(deviceName: String, port: Int, meshName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Rin-$deviceName"
            serviceType = this@NsdMeshDiscovery.serviceType
            setPort(port)
            // Optional TXT attributes
            setAttribute("mesh", meshName)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                registeredServiceName = NsdServiceInfo.serviceName
                Log.d(tag, "NSD Service registered successfully: $registeredServiceName on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(tag, "NSD Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Service unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Error initiating NSD registration", e)
        }
    }

    fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(tag, "NSD Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service found: ${service.serviceName}")
                if (service.serviceType == serviceType || service.serviceType.contains("rin-mesh")) {
                    if (service.serviceName == registeredServiceName) {
                        Log.d(tag, "Ignoring self discovered service")
                        return
                    }
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(tag, "NSD Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(tag, "NSD Service discovery stopped: $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Discovery failed: $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "NSD Stop discovery failed: $errorCode")
                isDiscovering = false
            }
        }

        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Error starting NSD discovery", e)
            isDiscovering = false
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "NSD Resolve failed: $errorCode for ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                Log.d(tag, "NSD Service resolved: ${serviceInfo.serviceName} at $host:$port")
                onPeerDiscovered(serviceInfo.serviceName, host, port)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(tag, "Error resolving service", e)
        }
    }

    fun stop() {
        try {
            if (registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
                registrationListener = null
            }
            if (discoveryListener != null && isDiscovering) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                discoveryListener = null
                isDiscovering = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping NSD", e)
        }
    }
}
