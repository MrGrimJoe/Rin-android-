package com.example.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.example.core.logging.MeshAuditLogger
import java.nio.charset.StandardCharsets

/**
 * Android Network Service Discovery (mDNS / DNS-SD ZeroConf engine).
 *
 * Automatically advertises local mesh endpoints on the LAN subnet and resolves
 * discovered peer instances to initiate cryptographic handshakes without manual IP entry.
 */
class NsdMeshDiscovery(
    private val context: Context,
    private val onPeerDiscovered: (serviceName: String, hostAddress: String, port: Int, attributes: Map<String, String>) -> Unit
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

    fun registerService(deviceName: String, port: Int, meshName: String, publicKey: String) {
        val serviceInfo = NsdServiceInfo().apply {
            // Sanitize service name for DNS-SD RFC compliance
            val cleanName = deviceName.replace(Regex("[^a-zA-Z0-9-]"), "-").take(20)
            serviceName = "Rin-$cleanName"
            serviceType = this@NsdMeshDiscovery.serviceType
            setPort(port)

            // DNS-SD TXT Record attributes for automatic Zero-Config identification
            setAttribute("mesh", meshName)
            setAttribute("devname", deviceName)
            // Store fingerprint / key identifier in TXT
            val keyFingerprint = if (publicKey.length > 24) publicKey.take(24) else publicKey
            setAttribute("pubkey", keyFingerprint)
            setAttribute("proto", "rin-v1")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                registeredServiceName = registeredInfo.serviceName
                Log.d(tag, "NSD Service registered successfully: $registeredServiceName on port $port")
                MeshAuditLogger.logNsdDiscovered(registeredInfo.serviceName, "0.0.0.0", port, meshName)
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
                Log.w(tag, "NSD Resolve failed: $errorCode for ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress ?: return
                // Ignore loopback or self-addressed host if matching port
                val port = resolvedInfo.port

                val attrMap = mutableMapOf<String, String>()
                try {
                    resolvedInfo.attributes?.forEach { (k, v) ->
                        if (v != null) {
                            attrMap[k] = String(v, StandardCharsets.UTF_8)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to read NSD attributes: ${e.message}")
                }

                val meshName = attrMap["mesh"]
                Log.i(tag, "NSD Peer auto-resolved: ${resolvedInfo.serviceName} at $host:$port (Mesh: $meshName)")
                MeshAuditLogger.logNsdDiscovered(resolvedInfo.serviceName, host, port, meshName)

                onPeerDiscovered(resolvedInfo.serviceName, host, port, attrMap)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.w(tag, "Error initiating service resolution for ${serviceInfo.serviceName}", e)
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
