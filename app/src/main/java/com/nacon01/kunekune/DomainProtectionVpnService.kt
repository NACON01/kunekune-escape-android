package com.nacon01.kunekune

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** DNS-only split-tunnel VPN for selected domain targets. */
class DomainProtectionVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunInput: Closeable? = null
    @Volatile private var tunOutput: Closeable? = null
    @Volatile private var upstreamSocket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var resolvers: List<InetAddress> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY
        stopRequested.set(false)
        createNotificationChannel()
        try {
            startForegroundCompat()
            resolvers = resolveUnderlyingResolvers()
            val descriptor = Builder()
                .setSession("Kunekune DNS protection")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(VIRTUAL_DNS_ADDRESS)
                .addRoute(VIRTUAL_DNS_ADDRESS, 32)
                .allowFamily(OsConstants.AF_INET6)
                .setBlocking(true)
                .establish()
                ?: throw IOException("VPN interface could not be established")
            tun = descriptor
            running.set(true)
            isRunning = true
            DomainProtectionController.publishStatus(applicationContext, DomainProtectionStatus.ACTIVE)
            val thread = Thread(::runPacketLoop, "kunekune-domain-vpn")
            worker = thread
            thread.start()
        } catch (exception: Exception) {
            Log.e(TAG, "domain protection VPN startup failed", exception)
            DomainProtectionController.publishStatus(applicationContext, DomainProtectionStatus.ERROR)
            shutdown()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        DomainProtectionController.publishStatus(applicationContext, DomainProtectionStatus.ERROR)
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun runPacketLoop() {
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            val descriptor = tun ?: throw IOException("VPN interface is unavailable")
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            )
            input = inputStream
            tunInput = inputStream
            val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            )
            output = outputStream
            tunOutput = outputStream
            if (!running.get()) return
            val buffer = ByteArray(TUN_BUFFER_SIZE)
            while (running.get()) {
                val length = try {
                    inputStream.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (length <= 0) break
                val packet = buffer.copyOf(length)
                handlePacket(packet, outputStream)
            }
        } catch (exception: Exception) {
            if (running.get() && !stopRequested.get()) {
                Log.e(TAG, "domain protection VPN packet loop failed", exception)
            }
        } finally {
            closeTunInput(input)
            closeTunOutput(output)
            closeTunDescriptor()
            val unexpectedExit = running.getAndSet(false) && !stopRequested.get()
            isRunning = false
            worker = null
            if (unexpectedExit) {
                DomainProtectionController.publishStatus(applicationContext, DomainProtectionStatus.ERROR)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun handlePacket(packet: ByteArray, output: OutputStream) {
        val parsed = Ipv4UdpPacket.parse(packet) ?: return
        if (parsed.destinationAddress != VIRTUAL_DNS || parsed.destinationPort != DNS_PORT) return
        val query = DnsMessage.parseQuery(parsed.payload) ?: return
        val appContext = applicationContext
        val targetStore = BlockTargetStore(appContext)
        val decision = DomainProtectionPolicy.decide(
            snapshot = HomeZoneRuntimeCoordinator(appContext).reload(),
            configuredTargets = targetStore.all(),
            selectedTargetIds = targetStore.selectedTargetIds(),
            queriedHost = query.hostname
        )
        val response = when (decision) {
            is DomainProtectionDecision.Block -> DnsMessage.buildNxdomainResponse(query)
            DomainProtectionDecision.OutsideOrOff,
            DomainProtectionDecision.NoSelectedDomainTarget,
            DomainProtectionDecision.Allowed -> forward(query) ?: DnsMessage.buildServfailResponse(query)
        }
        if (response.size > MAX_DNS_PAYLOAD) return
        output.write(Ipv4UdpPacket.build(
            sourceAddress = VIRTUAL_DNS,
            destinationAddress = parsed.sourceAddress,
            sourcePort = DNS_PORT,
            destinationPort = parsed.sourcePort,
            payload = response
        ))
        output.flush()
    }

    private fun forward(query: DnsQuery): ByteArray? {
        val linkResolvers = (resolvers + resolveUnderlyingResolvers())
            .distinctBy { it.hostAddress }
            .filter(::isUsableResolver)
            .take(MAX_LINK_RESOLVERS)
        val candidates = if (linkResolvers.isNotEmpty()) {
            linkResolvers
        } else {
            FALLBACK_RESOLVERS.take(MAX_FALLBACK_RESOLVERS)
        }
        for (resolver in candidates) {
            if (!running.get()) return null
            val socket = try { DatagramSocket() } catch (_: Exception) { continue }
            upstreamSocket = socket
            try {
                // protect() must happen before connect/send, otherwise the query can recurse into this VPN.
                if (!protect(socket)) continue
                socket.soTimeout = UPSTREAM_TIMEOUT_MILLIS
                socket.connect(InetSocketAddress(resolver, DNS_PORT))
                socket.send(DatagramPacket(query.originalPayload, query.originalPayload.size))
                val received = ByteArray(MAX_DNS_PAYLOAD)
                val response = DatagramPacket(received, received.size)
                socket.receive(response)
                if (response.length >= 2 &&
                    received[0] == query.originalPayload[0] &&
                    received[1] == query.originalPayload[1]
                ) {
                    return received.copyOf(response.length)
                }
            } catch (_: SocketTimeoutException) {
                // Try the next resolver or return SERVFAIL without affecting the VPN loop.
            } catch (_: Exception) {
                // Resolver/network failures are isolated to this query.
            } finally {
                if (upstreamSocket === socket) upstreamSocket = null
                socket.close()
            }
        }
        return null
    }

    private fun shutdown() {
        stopRequested.set(true)
        running.set(false)
        isRunning = false
        upstreamSocket?.close()
        upstreamSocket = null
        closeTunInput()
        closeTunOutput()
        closeTunDescriptor()
        val currentWorker = worker
        currentWorker?.interrupt()
        if (Thread.currentThread() !== currentWorker) {
            try { currentWorker?.join(WORKER_JOIN_TIMEOUT_MILLIS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (worker === currentWorker) worker = null
        stopForegroundCompat()
    }

    private fun closeTunInput(expected: Closeable? = null) {
        val stream = tunInput
        if (stream == null || (expected != null && stream !== expected)) return
        tunInput = null
        closeQuietly(stream)
    }

    private fun closeTunOutput(expected: Closeable? = null) {
        val stream = tunOutput
        if (stream == null || (expected != null && stream !== expected)) return
        tunOutput = null
        closeQuietly(stream)
    }

    private fun closeTunDescriptor() {
        val descriptor = tun ?: return
        tun = null
        try {
            descriptor.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(resource: Closeable) {
        try {
            resource.close()
        } catch (_: IOException) {
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("ドメイン保護")
            .setContentText("選択したドメインを自宅内で保護中")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "ドメイン保護",
            NotificationManager.IMPORTANCE_LOW
        ))
    }

    private fun resolveUnderlyingResolvers(): List<InetAddress> {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val addresses = buildList {
            connectivity.allNetworks.forEach { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
                connectivity.getLinkProperties(network)?.dnsServers.orEmpty().forEach(::add)
            }
        }.filter(::isUsableResolver)
        return addresses.distinctBy { it.hostAddress }
    }

    private fun isUsableResolver(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress &&
            address.hostAddress != VIRTUAL_DNS_ADDRESS

    companion object {
        const val ACTION_STOP = "com.nacon01.kunekune.action.STOP_DOMAIN_PROTECTION_VPN"
        private const val TAG = "KunekuneDomainVpn"
        private const val NOTIFICATION_CHANNEL_ID = "domain_protection"
        private const val NOTIFICATION_ID = 2401
        private const val DNS_PORT = 53
        private const val TUN_MTU = 1500
        private const val TUN_BUFFER_SIZE = 32767
        private const val MAX_DNS_PAYLOAD = 4096
        private const val UPSTREAM_TIMEOUT_MILLIS = 600
        private const val MAX_LINK_RESOLVERS = 2
        private const val MAX_FALLBACK_RESOLVERS = 2
        private const val WORKER_JOIN_TIMEOUT_MILLIS = 500L
        private const val TUN_ADDRESS = "10.123.0.2"
        const val VIRTUAL_DNS_ADDRESS = "10.123.0.1"
        private val VIRTUAL_DNS = Ipv4UdpPacket.address(10, 123, 0, 1)
        private val FALLBACK_RESOLVERS = listOf(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("8.8.8.8")
        )

        @Volatile var isRunning: Boolean = false
            private set
    }
}
