package com.hermes.automationbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AutomationBridgeService : AccessibilityService() {

    private val TAG = "AutomationBridge"
    private val PORT = 8080
    private val serverSocketRef = AtomicReference<ServerSocket?>(null)
    private val serverThreadRef = AtomicReference<Thread?>(null)
    private var serviceRunning = false

    companion object {
        const val CHANNEL_ID = "automation_bridge_channel"
        const val NOTIFICATION_ID = 1001
        const val FOREGROUND_SERVICE_TYPE = "specialUse"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected.")
        serviceRunning = true
        startForegroundService()
        startServerSocket()
    }

    private fun startForegroundService() {
        val channelName = "Automation Bridge Service"
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Required for Android to keep the service running"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Automation Bridge")
            .setContentText("Listening on port $PORT for Hermes commands")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started with notification.")
    }

    private fun startServerSocket() {
        val thread = Thread {
            var socket: ServerSocket? = null
            try {
                // Attempt to bind to localhost only for security
                socket = ServerSocket(PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocketRef.set(socket)
                Log.d(TAG, "Server socket opened on 127.0.0.1:$PORT")

                while (!Thread.currentThread().isInterrupted && serviceRunning) {
                    try {
                        val client = socket.accept()
                        handleClient(client)
                    } catch (e: SocketException) {
                        if (!serviceRunning) {
                            Log.d(TAG, "Socket closed, service stopping.")
                        } else {
                            Log.e(TAG, "Socket error", e)
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accepting client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server socket", e)
                // Log to file as fallback
                logToFile("FATAL: Server socket error: ${e.message}")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
                serverSocketRef.set(null)
            }
        }
        serverThreadRef.set(thread)
        thread.isDaemon = true
        thread.start()
        Log.d(TAG, "Server thread started.")
    }

    private fun handleClient(client: Socket) {
        Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val cmd = line!!
                    Log.d(TAG, "Received command: $cmd")
                    val response = processCommand(cmd)
                    writer.println(response)
                    writer.println("END_OF_RESPONSE")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {}
                Log.d(TAG, "Client disconnected.")
            }
        }
    }

    private fun processCommand(command: String): String {
        val parts = command.split(" ", limit = 2)
        val action = parts[0]
        val params = if (parts.size > 1) parts[1] else ""

        return try {
            when (action.uppercase()) {
                "TAP" -> handleTap(params)
                "SWIPE" -> handleSwipe(params)
                "READ" -> handleRead()
                "PING" -> "PONG"
                else -> "ERROR: Unknown command '$action'"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command '$action'", e)
            "ERROR: ${e.message}"
        }
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        val result = nodes.firstOrNull { it.isVisibleToUser }
        // Don't recycle result if returning it — caller's responsibility
        rootNode.recycle()
        return result
    }

    private fun handleTap(params: String): String {
        val node = findNodeByText(params) ?: return "ERROR: Node with text '$params' not found on screen."
        if (!node.isClickable) {
            // Try to find clickable parent
            var parent = node
            while (parent != null && !parent.isClickable) {
                val p = parent.parent
                if (p != parent) parent = p else break
            }
            if (parent != null && parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return if (result) "OK: Tapped on '$params'" else "ERROR: Failed to tap"
            }
            node.recycle()
            return "ERROR: Node with text '$params' is not clickable and no clickable parent found."
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return if (result) "OK: Tapped on '$params'" else "ERROR: Failed to tap on '$params'"
    }

    private fun handleSwipe(params: String): String {
        val parts = params.split(" ")
        if (parts.size < 4) return "ERROR: Swipe needs 4 params: fromX fromY toX toY"
        try {
            val fromX = parts[0].toFloat()
            val fromY = parts[1].toFloat()
            val toX = parts[2].toFloat()
            val toY = parts[3].toFloat()
            val duration = if (parts.size > 4) parts[4].toLong() else 200L

            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            val dispatched = dispatchGesture(gesture, null, null)
            return if (dispatched) "OK: Swipe dispatched" else "ERROR: Swipe failed"
        } catch (e: NumberFormatException) {
            return "ERROR: Invalid number format for swipe coordinates."
        }
    }

    private fun handleRead(): String {
        val rootNode = rootInActiveWindow
            ?: return "ERROR: Cannot get window content. No active window."
        val sb = StringBuilder()
        try {
            traverseNodes(rootNode, sb)
        } finally {
            rootNode.recycle()
        }
        val result = sb.toString().trim()
        return result.ifEmpty { "No text found on screen." }
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.isVisibleToUser) {
            node.recycle()
            return
        }
        val text = node.text
        if (!text.isNullOrBlank()) {
            sb.append(text.toString().trim()).append('\n')
        }
        // Also check content description
        val desc = node.contentDescription
        if (!desc.isNullOrBlank()) {
            sb.append("[desc: ").append(desc.toString().trim()).append("]\n")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodes(child, sb)
            }
        }
        node.recycle()
    }

    private fun logToFile(message: String) {
        try {
            val file = android.os.Environment.getExternalStorageDirectory()
                ?.let {
                    java.io.File(it, "automation_bridge_crash.log")
                } ?: java.io.File(filesDir, "automation_bridge_crash.log")
            file.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} | $message\n")
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for command processing
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
        serviceRunning = false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "Accessibility Service unbound.")
        serviceRunning = false
        serverSocketRef.getAndSet(null)?.close()
        serverThreadRef.getAndSet(null)?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceRunning = false
        serverSocketRef.getAndSet(null)?.close()
        serverThreadRef.getAndSet(null)?.interrupt()
        Log.d(TAG, "Service destroyed.")
    }
}
