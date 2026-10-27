package com.android.castappx.socket

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** All state and NSD callbacks are serialized on the main thread; socket IO stays off it. */
@Suppress("DEPRECATION")
class SocketChat(context: Context) {
    val remoteFrame = MutableLiveData<Bitmap?>(null)
    @Volatile
    private var video: UdpVideo? = null
    private var lastFrameAt = 0L
    private val frameTimeout = object : Runnable {
        override fun run() {
            if (closed) return
            if (remoteFrame.value != null && SystemClock.elapsedRealtime() - lastFrameAt > 2500) {
                remoteFrame.value = null
            }
            main.postDelayed(this, 500)
        }
    }

    val status = MutableLiveData("未连接")
    val messages = MutableLiveData<List<String>>(emptyList())
    val devices = MutableLiveData<List<NsdServiceInfo>>(emptyList())
    val discoveryStatus = MutableLiveData("正在搜索局域网设备…")
    val serverRunning = MutableLiveData(false)
    val connected = MutableLiveData(false)
    val connecting = MutableLiveData(false)
    val sending = MutableLiveData(false)
    val sentSequence = MutableLiveData(0)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()
    private val serviceName =
        "CastAPPX-${Build.MODEL.take(20)}-${UUID.randomUUID().toString().take(8)}"
    private var registeredName = serviceName
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var server: ServerSocket? = null
    private var peer: Socket? = null
    private var pending: Socket? = null
    private var generation = 0

    @Volatile
    private var closed = false

    init {
        main.post(frameTimeout)
    }

    fun sendFrame(bitmap: Bitmap) {
        video?.send(bitmap)
    }

    fun canSendFrames(): Boolean = video?.isSender == true

    private fun dispatch(onClosed: () -> Unit = {}, action: () -> Unit) {
        main.post { if (!closed) action() else onClosed() }
    }

    private fun append(text: String) {
        messages.value = (messages.value.orEmpty() + text).takeLast(500)
    }

    fun startServer() {
        if (serverRunning.value == true || connecting.value == true) return
        disconnect()
        serverRunning.value = true
        status.value = "正在启动服务器…"
        val attempt = generation
        io.execute {
            try {
                val listener = ServerSocket(0)
                dispatch(onClosed = { listener.close() }) {
                    if (attempt != generation) listener.close() else {
                        server = listener
                        register(listener.localPort)
                        acceptConnections(listener)
                    }
                }
            } catch (e: Exception) {
                dispatch {
                    if (attempt == generation) {
                        serverRunning.value = false; status.value =
                            "服务器启动失败：${e.localizedMessage}"
                    }
                }
            }
        }
    }

    private fun register(portNumber: Int) {
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = dispatch {
                if (registration === this) {
                    registeredName = info.serviceName
                    if (peer == null) status.value = "服务器已启动，等待连接（端口 $portNumber）"
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = dispatch {
                if (registration === this) {
                    disconnect(); status.value = "服务发布失败（$code），请重试"
                }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = callback
        try {
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = this@SocketChat.serviceName
                serviceType = SERVICE_TYPE
                port = portNumber
            }, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (e: RuntimeException) {
            disconnect()
            status.value = "服务发布失败：${e.localizedMessage}"
        }
    }

    private fun acceptConnections(listener: ServerSocket) = io.execute {
        try {
            while (!listener.isClosed) {
                val socket = listener.accept()
                dispatch(onClosed = { socket.close() }) {
                    if (server !== listener || peer != null) socket.close()
                    else attach(socket, socket.inetAddress.hostAddress ?: "设备")
                }
            }
        } catch (e: Exception) {
            dispatch {
                if (server === listener) {
                    disconnect(); status.value = "服务器已停止：${e.localizedMessage}"
                }
            }
        }
    }

    fun startDiscovery() {
        if (discovery != null) return
        devices.value = emptyList()
        discoveryStatus.value = "正在搜索局域网设备…请在另一台设备启动服务器"
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = dispatch {
                if (discovery === this && info.serviceName != registeredName && info.serviceName != serviceName) {
                    devices.value = (devices.value.orEmpty()
                        .filterNot { it.serviceName == info.serviceName } + info).sortedBy { it.serviceName }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = dispatch {
                if (discovery === this) devices.value =
                    devices.value.orEmpty().filterNot { it.serviceName == info.serviceName }
            }

            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = dispatch {
                if (discovery === this) {
                    stopDiscovery(); discoveryStatus.value = "搜索失败（$code），请关闭后重试"
                }
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        discovery = callback
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (e: RuntimeException) {
            stopDiscovery(); discoveryStatus.value = "无法搜索：${e.localizedMessage}"
        }
    }

    fun stopDiscovery() {
        val callback = discovery ?: return
        discovery = null
        runCatching { nsd.stopServiceDiscovery(callback) }
    }

    fun connect(info: NsdServiceInfo) {
        disconnect()
        connecting.value = true
        status.value = "正在连接 ${info.serviceName}…"
        val attempt = generation
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) = dispatch {
                    if (attempt == generation) {
                        connecting.value = false; status.value = "设备解析失败（$code），请重新搜索"
                    }
                }

                override fun onServiceResolved(service: NsdServiceInfo) = dispatch {
                    if (attempt != generation) return@dispatch
                    val host = service.host
                    if (host == null || service.port <= 0) {
                        connecting.value = false
                        status.value = "设备地址不可用，请重新搜索"
                        return@dispatch
                    }
                    val socket = Socket()
                    pending = socket
                    io.execute {
                        try {
                            socket.connect(InetSocketAddress(host, service.port), 5000)
                            dispatch(onClosed = { socket.close() }) {
                                if (attempt != generation) socket.close()
                                else {
                                    pending = null; attach(socket, service.serviceName)
                                }
                            }
                        } catch (e: Exception) {
                            runCatching { socket.close() }
                            dispatch {
                                if (attempt == generation) {
                                    pending = null; connecting.value = false; status.value =
                                        "连接失败：${e.localizedMessage}"
                                }
                            }
                        }
                    }
                }
            })
        } catch (e: RuntimeException) {
            connecting.value = false; status.value = "无法解析设备：${e.localizedMessage}"
        }
        main.postDelayed({
            if (!closed && attempt == generation && connecting.value == true) {
                disconnect()
                status.value = "连接超时，请重新搜索设备"
            }
        }, 10000)
    }

    private fun attach(socket: Socket, name: String) {
        peer = socket
        if (server != null) {
            try {
                val token = java.security.SecureRandom().nextLong()
                lateinit var transport: UdpVideo
                transport = UdpVideo(socket.localPort, socket.inetAddress, 0, token) { bitmap ->
                    dispatch {
                        if (video === transport && peer === socket) {
                            lastFrameAt = SystemClock.elapsedRealtime()
                            remoteFrame.value = bitmap
                        }
                    }
                }
                video = transport
                writer.execute {
                    try {
                        ChatProtocol.write(
                            DataOutputStream(socket.getOutputStream()),
                            "VIDEO:$token"
                        )
                    } catch (_: Exception) {
                        dispatch { dropPeer(socket, "画面通道建立失败") }
                    }
                }
            } catch (e: Exception) {
                dropPeer(socket, "UDP通道启动失败：${e.localizedMessage}")
                return
            }
        }
        connected.value = true
        connecting.value = false
        status.value = "已连接：$name"
        append("系统：已连接 $name")
        io.execute {
            try {
                val input = DataInputStream(socket.getInputStream())
                while (!socket.isClosed) {
                    val message = ChatProtocol.read(input)
                    dispatch {
                        if (peer === socket) {
                            when {
                                message.startsWith("CHAT:") -> append("对方：${message.removePrefix("CHAT:")}")
                                server == null && message.startsWith("VIDEO:") -> {
                                    val token = message.removePrefix("VIDEO:").toLongOrNull()
                                    if (token != null) {
                                        try {
                                            video?.close()
                                            video =
                                                UdpVideo(0, socket.inetAddress, socket.port, token)
                                        } catch (_: Exception) {
                                            dropPeer(socket, "UDP通道启动失败")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                dispatch { dropPeer(socket, "连接已断开") }
            }
        }
    }

    fun send(text: String) {
        val socket = peer ?: return
        if (sending.value == true || text.isBlank()) return
        if (text.toByteArray(Charsets.UTF_8).size > ChatProtocol.MAX_BYTES - 5) {
            status.value = "消息过长，请缩短后重试"
            return
        }
        sending.value = true
        writer.execute {
            try {
                ChatProtocol.write(DataOutputStream(socket.getOutputStream()), "CHAT:$text")
                dispatch {
                    if (peer === socket) {
                        append("我：$text")
                        sending.value = false
                        sentSequence.value = sentSequence.value!! + 1
                    }
                }
            } catch (e: Exception) {
                dispatch { dropPeer(socket, "发送失败，连接已断开；输入内容已保留") }
            }
        }
    }

    private fun dropPeer(socket: Socket, reason: String) {
        if (peer !== socket) return
        runCatching { socket.close() }
        peer = null
        video?.close()
        video = null
        remoteFrame.value = null
        connected.value = false
        sending.value = false
        status.value = if (server != null) "$reason，等待新连接" else reason
        append("系统：$reason")
    }

    fun disconnect() {
        generation++
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        runCatching { pending?.close() }
        pending = null
        peer?.let { dropPeer(it, "已断开连接") }
        serverRunning.value = false
        connecting.value = false
        status.value = "未连接"
    }

    fun close() {
        stopDiscovery()
        disconnect()
        closed = true
        main.removeCallbacks(frameTimeout)
        io.shutdownNow()
        writer.shutdownNow()
    }

    companion object {
        const val SERVICE_TYPE = "_castappx._tcp."
    }
}
