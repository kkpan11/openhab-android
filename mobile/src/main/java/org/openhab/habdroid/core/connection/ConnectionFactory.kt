/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

@file:Suppress("DEPRECATION")

package org.openhab.habdroid.core.connection

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import android.security.KeyChain
import android.security.KeyChainException
import android.util.Log
import androidx.annotation.VisibleForTesting
import de.duenndns.ssl.MemorizingTrustManager
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.logging.HttpLoggingInterceptor
import org.openhab.habdroid.core.CloudMessagingHelper
import org.openhab.habdroid.model.ServerConfiguration
import org.openhab.habdroid.util.CacheManager
import org.openhab.habdroid.util.PrefKeys
import org.openhab.habdroid.util.getActiveServerId
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.getPrimaryServerId
import org.openhab.habdroid.util.getSecretPrefs
import org.openhab.habdroid.util.getStringOrNull
import org.openhab.habdroid.util.isDebugModeEnabled
import org.openhab.habdroid.util.isDemoModeEnabled

/**
 * A factory class, which is the main entry point to get a Connection to a specific openHAB
 * server. Use this factory class whenever you need to obtain a connection to load additional
 * data from the openHAB server or another supported source
 * (see the constants in [Connection]).
 */
class ConnectionFactory internal constructor(
    private val context: Application,
    private val prefs: SharedPreferences,
    private val secretPrefs: SharedPreferences,
    private val connectionHelper: ConnectionManagerHelper
) : CoroutineScope by CoroutineScope(Dispatchers.Main),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val trustManager: MemorizingTrustManager
    private val httpLogger: HttpLoggingInterceptor
    private var httpClient: OkHttpClient
    private var lastClientCertAlias: String? = null

    private var primaryConn: ServerConnections? = null
    private var activeConn: ServerConnections? = null

    private val listeners = HashSet<UpdateListener>()
    private var needsUpdate: Boolean = false

    private var activeCheck: Job? = null
    private var primaryCheck: Job? = null
    private var activeCloudCheck: Job? = null
    private var primaryCloudCheck: Job? = null

    private data class ServerConnections(val local: Connection?, val remote: AbstractConnection?)

    data class ConnectionResult(val connection: Connection?, val failureReason: ConnectionException?)

    data class CloudConnectionResult(val connection: CloudConnection?, val failureReason: Exception?)

    private data class StateHolder(
        val primary: ConnectionResult?,
        val active: ConnectionResult?,
        val primaryCloud: CloudConnectionResult?,
        val activeCloud: CloudConnectionResult?
    )

    private val stateChannel = ConflatedBroadcastChannel(StateHolder(null, null, null, null))

    interface UpdateListener {
        fun onActiveConnectionChanged()

        fun onPrimaryConnectionChanged()

        fun onActiveCloudConnectionChanged(connection: CloudConnection?)

        fun onPrimaryCloudConnectionChanged(connection: CloudConnection?)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
        secretPrefs.registerOnSharedPreferenceChangeListener(this)

        httpLogger = HttpLoggingInterceptor()
        updateHttpLoggerSettings()

        trustManager = MemorizingTrustManager(context)
        httpClient = OkHttpClient.Builder()
            .cache(CacheManager.getInstance(context).httpCache)
            .addInterceptor(httpLogger)
            .hostnameVerifier(trustManager.wrapHostnameVerifier(OkHostnameVerifier))
            .build()
        updateHttpClientForClientCert(true)

        // For video widgets
        SSLContext.getInstance("TLS").apply {
            init(null, MemorizingTrustManager.getInstanceList(context), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
            val mtmHostnameVerifier = MemorizingTrustManager(context)
                .wrapHostnameVerifier(OkHostnameVerifier)
            HttpsURLConnection.setDefaultHostnameVerifier(mtmHostnameVerifier)
        }

        // Relax per-host connection limit, as the default limit (max 5 connections per host) is
        // too low considering SSE connections count against that limit.
        httpClient.dispatcher.maxRequestsPerHost = httpClient.dispatcher.maxRequests

        connectionHelper.changeCallback = {
            if (listeners.isEmpty()) {
                // We're running in background. Clear current state and postpone update for next
                // listener registration.
                updateState(false, active = null, primary = null)
                needsUpdate = true
            } else {
                triggerConnectionUpdateIfNeeded()
            }
        }
    }

    private fun addListenerInternal(l: UpdateListener) {
        if (listeners.add(l)) {
            if (l is Activity) {
                trustManager.bindDisplayActivity(l)
            }
            if (!triggerConnectionUpdateIfNeededAndPending() && activeConn?.local != null && listeners.size == 1) {
                // When coming back from background, re-do connectivity check for
                // local connections, as the reachability of the local server might have
                // changed since we went to background
                val (_, active, _, _) = stateChannel.value
                val local = active?.connection === activeConn?.local ||
                    (active?.failureReason as? NoUrlInformationException)?.wouldHaveUsedLocalConnection() == true
                if (local) {
                    triggerConnectionUpdateIfNeeded()
                }
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == PrefKeys.DEBUG_MESSAGES) {
            updateHttpLoggerSettings()
        }
        val serverId = prefs.getActiveServerId()
        if (key in UPDATE_TRIGGERING_KEYS ||
            CLIENT_CERT_UPDATE_TRIGGERING_PREFIXES.any { prefix -> key == PrefKeys.buildServerKey(serverId, prefix) }
        ) {
            updateHttpClientForClientCert(false)
        }
        if (key in UPDATE_TRIGGERING_KEYS ||
            UPDATE_TRIGGERING_PREFIXES.any { prefix -> key == PrefKeys.buildServerKey(serverId, prefix) }
        ) {
            launch {
                // if the active server changed, we need to invalidate the old connection immediately,
                // as we don't want the user to see old server data while we're validating the new one
                updateConnections(key == PrefKeys.ACTIVE_SERVER_ID)
            }
        }
    }

    @VisibleForTesting
    fun updateConnections(callListenersImmediately: Boolean = false) {
        if (prefs.isDemoModeEnabled()) {
            if (activeConn?.local is DemoConnection) {
                // demo mode already was enabled
                return
            }
            val conn = DemoConnection(httpClient)
            activeConn = ServerConnections(conn, conn)
            primaryConn = activeConn
            val connResult = ConnectionResult(conn, null)
            updateState(true, connResult, connResult, CloudConnectionResult(null, null))
        } else {
            val activeServer = prefs.getActiveServerId()
            activeConn = loadServerConnections(activeServer)

            val primaryServer = prefs.getPrimaryServerId()
            primaryConn = if (primaryServer == activeServer) {
                activeConn
            } else {
                loadServerConnections(primaryServer)
            }

            updateState(callListenersImmediately, null, null, null)
            triggerConnectionUpdateIfNeeded()
        }
    }

    private fun loadServerConnections(serverId: Int): ServerConnections? {
        val config = ServerConfiguration.load(prefs, secretPrefs, serverId) ?: return null
        val local = config.localPath?.let { path -> DefaultConnection(httpClient, Connection.TYPE_LOCAL, path) }
        val remote = config.remotePath?.let { path -> DefaultConnection(httpClient, Connection.TYPE_REMOTE, path) }
        return ServerConnections(local, remote)
    }

    private fun updateHttpLoggerSettings() {
        with(httpLogger) {
            if (prefs.isDebugModeEnabled()) {
                redactHeader("Authorization")
                redactHeader("set-cookie")
                level = HttpLoggingInterceptor.Level.HEADERS
            } else {
                level = HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    private fun updateHttpClientForClientCert(forceUpdate: Boolean) {
        val clientCertAlias = if (prefs.isDemoModeEnabled()) {
            // No client cert in demo mode
            null
        } else {
            prefs.getStringOrNull(PrefKeys.buildServerKey(prefs.getActiveServerId(), PrefKeys.SSL_CLIENT_CERT_PREFIX))
        }
        val keyManagers = if (clientCertAlias != null) {
            arrayOf<KeyManager>(ClientKeyManager(context, clientCertAlias))
        } else {
            null
        }

        // Updating the SSL socket factory is an expensive call;
        // make sure to only do this if really needed.
        if (!forceUpdate) {
            if (clientCertAlias == null && lastClientCertAlias == null) {
                // No change: no client cert at all
                return
            } else if (clientCertAlias != null && clientCertAlias == lastClientCertAlias) {
                // No change: client cert stayed the same
                return
            }
        }

        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), null)
            httpClient = httpClient.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .build()
            lastClientCertAlias = clientCertAlias
        } catch (e: Exception) {
            Log.d(TAG, "Applying certificate trust settings failed", e)
        }
    }

    private fun updateState(
        callListenersOnChange: Boolean,
        primary: ConnectionResult? = stateChannel.value.primary,
        active: ConnectionResult? = stateChannel.value.active,
        primaryCloud: CloudConnectionResult? = stateChannel.value.primaryCloud,
        activeCloud: CloudConnectionResult? = stateChannel.value.activeCloud
    ) {
        val prevState = stateChannel.value
        val newState = StateHolder(primary, active, primaryCloud, activeCloud)
        stateChannel.trySend(newState)
            .onClosed { throw it ?: ClosedSendChannelException("Channel was closed normally") }
        if (!callListenersOnChange) {
            return
        }
        launch {
            if (newState.active?.failureReason != null ||
                prevState.active?.connection !== newState.active?.connection
            ) {
                listeners.forEach { l -> l.onActiveConnectionChanged() }
            }
            if (newState.primary?.failureReason != null ||
                prevState.primary?.connection !== newState.primary?.connection
            ) {
                listeners.forEach { l -> l.onPrimaryConnectionChanged() }
            }
            if (prevState.activeCloud !== newState.activeCloud) {
                listeners.forEach { l -> l.onActiveCloudConnectionChanged(newState.activeCloud?.connection) }
            }
            if (prevState.primaryCloud !== newState.primaryCloud) {
                CloudMessagingHelper.onConnectionUpdated(context, newState.primaryCloud?.connection)
                listeners.forEach { l -> l.onPrimaryCloudConnectionChanged(newState.primaryCloud?.connection) }
            }
        }
    }

    private fun triggerConnectionUpdateIfNeededAndPending(): Boolean {
        if (!needsUpdate) {
            return false
        }
        needsUpdate = false
        triggerConnectionUpdateIfNeeded()
        return true
    }

    private fun triggerConnectionUpdateIfNeeded() {
        activeCheck?.cancel()
        primaryCheck?.cancel()
        activeCloudCheck?.cancel()
        primaryCloudCheck?.cancel()

        if (activeConn?.local is DemoConnection) {
            return
        }

        val active = activeConn
        val primary = primaryConn

        val updateActive = { result: ConnectionResult ->
            if (active === primary) {
                updateState(true, active = result, primary = result)
            } else {
                updateState(true, active = result)
            }
        }
        val updateActiveCloud = { result: CloudConnectionResult ->
            if (active === primary) {
                updateState(true, activeCloud = result, primaryCloud = result)
            } else {
                updateState(true, activeCloud = result)
            }
        }

        activeCheck = launch {
            try {
                val usable = withContext(Dispatchers.IO) {
                    checkAvailableConnection(active?.local, active?.remote)
                }
                updateActive(ConnectionResult(usable, null))
            } catch (e: ConnectionException) {
                updateActive(ConnectionResult(null, e))
            }
        }

        if (active !== primary) {
            primaryCheck = launch {
                try {
                    val usable = withContext(Dispatchers.IO) {
                        checkAvailableConnection(primary?.local, primary?.remote)
                    }
                    updateState(true, primary = ConnectionResult(usable, null))
                } catch (e: ConnectionException) {
                    updateState(true, primary = ConnectionResult(null, e))
                }
            }
        }

        activeCloudCheck = launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    active?.remote?.toCloudConnection()
                }
                updateActiveCloud(CloudConnectionResult(result, null))
            } catch (e: CancellationException) {
                // ignored
            } catch (e: Exception) {
                updateActiveCloud(CloudConnectionResult(null, e))
            }
        }

        if (active !== primary) {
            primaryCloudCheck = launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        primary?.remote?.toCloudConnection()
                    }
                    updateState(true, primaryCloud = CloudConnectionResult(result, null))
                } catch (e: CancellationException) {
                    // ignored
                } catch (e: Exception) {
                    updateState(true, primaryCloud = CloudConnectionResult(null, e))
                }
            }
        }
    }

    private suspend fun checkAvailableConnection(local: Connection?, remote: Connection?): Connection {
        val available = connectionHelper.currentConnections.sortedBy { type ->
            when (type) {
                is ConnectionManagerHelper.ConnectionType.Vpn -> 1
                is ConnectionManagerHelper.ConnectionType.Ethernet -> 2
                is ConnectionManagerHelper.ConnectionType.Wifi -> 3
                is ConnectionManagerHelper.ConnectionType.Bluetooth -> 4
                is ConnectionManagerHelper.ConnectionType.Mobile -> 5
                is ConnectionManagerHelper.ConnectionType.Unknown -> 6
            }
        }

        Log.d(TAG, "checkAvailableConnection: found types $available")
        if (available.isEmpty()) {
            Log.e(TAG, "Network is not available")
            throw NetworkNotAvailableException()
        }

        var hasWrongWifi = false
        val restrictedSsids = ServerConfiguration.load(prefs, secretPrefs, prefs.getActiveServerId())?.let { config ->
            if (config.restrictToWifiSsids) config.wifiSsids else null
        }

        if (local != null && local is DefaultConnection) {
            val localCandidates = available.filter { type ->
                when (type) {
                    is ConnectionManagerHelper.ConnectionType.Wifi -> {
                        val ssid = type.fetchSsid(context)
                        when {
                            ssid.isNullOrEmpty() -> true // assume missing permissions
                            restrictedSsids?.contains(ssid) == true -> {
                                Log.d(TAG, "Skip Wi-Fi ${type.network} (server restricted to $restrictedSsids)")
                                hasWrongWifi = true
                                false
                            }
                            else -> true
                        }
                    }
                    is ConnectionManagerHelper.ConnectionType.Bluetooth -> true
                    is ConnectionManagerHelper.ConnectionType.Ethernet -> true
                    is ConnectionManagerHelper.ConnectionType.Vpn -> true
                    else -> false
                }
            }
            val usableLocalNetwork = localCandidates.firstOrNull { local.isReachableViaNetwork(it.network) }
            if (usableLocalNetwork != null) {
                Log.d(TAG, "Connecting to local URL via $usableLocalNetwork")
                local.network = usableLocalNetwork.network
                local.isMetered = !usableLocalNetwork.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                return local
            }
        }

        if (available[0] is ConnectionManagerHelper.ConnectionType.Unknown) {
            Log.d(TAG, "Network type ${available[0]} is unknown")
        }

        if (remote != null) {
            // If local URL is not reachable or not configured, use remote URL
            Log.d(TAG, "Connecting to remote URL")
            if (remote is DefaultConnection) {
                remote.isMetered = available.any { type ->
                    !type.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
            return remote
        }

        throw if (hasWrongWifi) WrongWifiException() else NoUrlInformationException(true)
    }

    private class ClientKeyManager(context: Context, private val alias: String?) : X509KeyManager {
        private val context: Context = context.applicationContext

        override fun chooseClientAlias(
            keyTypes: Array<String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? {
            Log.d(TAG, "chooseClientAlias - alias: $alias")
            return alias
        }

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
            Log.d(TAG, "chooseServerAlias")
            return null
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            Log.d(TAG, "getCertificateChain", Throwable())
            return try {
                alias?.let { KeyChain.getCertificateChain(context, alias) }
            } catch (e: KeyChainException) {
                Log.e(TAG, "Failed loading certificate chain", e)
                null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed loading certificate chain", e)
                null
            }
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
            Log.d(TAG, "getClientAliases")
            return alias?.let { arrayOf(it) }
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
            Log.d(TAG, "getServerAliases")
            return null
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            Log.d(TAG, "getPrivateKey")
            return try {
                alias?.let { KeyChain.getPrivateKey(context, alias) }
            } catch (e: KeyChainException) {
                Log.e(TAG, "Failed loading private key", e)
                null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Failed loading private key", e)
                null
            }
        }

        companion object {
            private val TAG = ClientKeyManager::class.java.simpleName
        }
    }

    companion object {
        private val TAG = ConnectionFactory::class.java.simpleName
        private val UPDATE_TRIGGERING_KEYS = listOf(
            PrefKeys.DEMO_MODE,
            PrefKeys.ACTIVE_SERVER_ID,
            PrefKeys.PRIMARY_SERVER_ID
        )
        private val UPDATE_TRIGGERING_PREFIXES = listOf(
            PrefKeys.LOCAL_URL_PREFIX,
            PrefKeys.REMOTE_URL_PREFIX,
            PrefKeys.LOCAL_USERNAME_PREFIX,
            PrefKeys.LOCAL_PASSWORD_PREFIX,
            PrefKeys.REMOTE_USERNAME_PREFIX,
            PrefKeys.REMOTE_PASSWORD_PREFIX,
            PrefKeys.SSL_CLIENT_CERT_PREFIX,
            PrefKeys.WIFI_SSID_PREFIX,
            PrefKeys.RESTRICT_TO_SSID_PREFIX
        )
        private val CLIENT_CERT_UPDATE_TRIGGERING_PREFIXES = listOf(PrefKeys.SSL_CLIENT_CERT_PREFIX)

        @VisibleForTesting
        lateinit var instance: ConnectionFactory

        fun initialize(ctx: Application) {
            instance = ConnectionFactory(ctx, ctx.getPrefs(), ctx.getSecretPrefs(), ConnectionManagerHelper.create(ctx))
            instance.launch {
                instance.connectionHelper.start()
                instance.updateConnections()
            }
        }

        @VisibleForTesting
        fun initialize(ctx: Application, prefs: SharedPreferences, connectionHelper: ConnectionManagerHelper) {
            instance = ConnectionFactory(ctx, prefs, prefs, connectionHelper)
        }

        fun shutdown() {
            instance.connectionHelper.shutdown()
        }

        /**
         * Wait for initialization of the factory.
         *
         * This method blocks until all asynchronous work (that is, determination of
         * available and cloud connection) is ready, so that {@link connection}
         * and {@link usableConnection} can safely be used.
         */
        suspend fun waitForInitialization() {
            instance.triggerConnectionUpdateIfNeededAndPending()
            val sub = instance.stateChannel.openSubscription()
            do {
                val (primary, active, primaryCloud, activeCloud) = sub.receive()
            } while (primary == null || active == null || primaryCloud == null || activeCloud == null)
        }

        fun addListener(l: UpdateListener) {
            instance.addListenerInternal(l)
        }

        fun removeListener(l: UpdateListener) {
            if (instance.listeners.remove(l) && l is Activity) {
                instance.trustManager.unbindDisplayActivity(l as Activity)
            }
        }

        fun restartNetworkCheck() {
            instance.triggerConnectionUpdateIfNeeded()
        }

        /**
         * Returns any openHAB connection that is most likely to work for the active server on the current network.
         * The returned object will contain either a working connection, or the initialization failure cause.
         * If initialization did not finish yet, null is returned.
         */
        val activeUsableConnection get() = instance.stateChannel.value.active

        /**
         * Returns whether the active server has a configured local connection
         */
        val hasActiveLocalConnection get() = instance.activeConn?.local != null

        /**
         * Returns whether the active server has a configured remote connection
         */
        val hasActiveRemoteConnection get() = instance.activeConn?.remote != null

        /**
         * Like {@link activeUsableConnection}, but for the primary instead of active server.
         */
        val primaryUsableConnection get() = instance.stateChannel.value.primary

        /**
         * Like {@link hasActiveLocalConnection}, but for the primary instead of active server.
         */
        val hasPrimaryLocalConnection get() = instance.primaryConn?.local != null

        /**
         * Like {@link hasActiveRemoteConnection}, but for the primary instead of active server.
         */
        val hasPrimaryRemoteConnection get() = instance.primaryConn?.remote != null

        /**
         * Returns the resolved cloud connection for the active server.
         * The returned object will contain either
         * - a working connection
         * - the initialization failure cause or
         * - null for both values
         *   (in case no remote server is configured or the remote server is not an openHAB cloud instance)
         * If initialization did not finish yet, null is returned.
         */
        val activeCloudConnection get() = instance.stateChannel.value.activeCloud

        /**
         * Like {@link activeCloudConnection}, but for the primary instead of active server.
         */
        val primaryCloudConnection get() = instance.stateChannel.value.primaryCloud
    }
}
