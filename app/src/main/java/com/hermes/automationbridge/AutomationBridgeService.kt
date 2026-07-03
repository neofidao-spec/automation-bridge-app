package com.hermes.automationbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import kotlin.concurrent.thread

class AutomationBridgeService : AccessibilityService() {

    private val TAG = "AutomationBridge"
    private val PORT = 8080
    private var serverSocket: ServerSocket? = null
    private val serverThread = Thread {
        try {
            serverSocket = ServerSocket(PORT)
            Log.d(TAG, "Server socket opened on port $PORT")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = serverSocket!!.accept()
                    handleClient(client)
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting client", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server socket error", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected.")
        if (!serverThread.isAlive) {
            serverThread.start()
        }
    }

    private fun handleClient(client: Socket) {
        Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Received command: $line")
                    val response = processCommand(line!!)
                    writer.println(response)
                    writer.println("END_OF_RESPONSE") // Delimiter
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                client.close()
                Log.d(TAG, "Client disconnected.")
            }
        }
    }

    private fun processCommand(command: String): String {
        val parts = command.split(" ", limit = 2)
        val action = parts[0]
        val params = if (parts.size > 1) parts[1] else ""

        return when (action.uppercase()) {
            "TAP" -> handleTap(params)
            "SWIPE" -> handleSwipe(params)
            "READ" -> handleRead()
            "PING" -> "PONG"
            else -> "ERROR: Unknown command '$action'"
        }
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        val result = nodes.firstOrNull { it.isVisibleToUser }
        nodes.forEach { it.recycle() }
        rootNode.recycle()
        return result
    }

    private fun handleTap(params: String): String {
        val node = findNodeByText(params)
        if (node != null && node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return if (result) "OK: Tapped on '$params'" else "ERROR: Failed to tap on '$params'"
        }
        return "ERROR: Node with text '$params' not found or not clickable."
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
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            
            val dispatched = dispatchGesture(gesture, null, null)
            return if(dispatched) "OK: Swipe dispatched" else "ERROR: Swipe failed"

        } catch (e: NumberFormatException) {
            return "ERROR: Invalid number format for swipe coordinates."
        }
    }

    private fun handleRead(): String {
        val rootNode = rootInActiveWindow ?: return "ERROR: Cannot get window content."
        val sb = StringBuilder()
        traverseNodes(rootNode, sb)
        rootNode.recycle()
        return sb.toString().trim()
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (node.isVisibleToUser && !node.text.isNullOrBlank()) {
            sb.append(node.text.toString()).append("\n")
        }
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i), sb)
        }
        node.recycle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for command processing
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "Accessibility Service unbound.")
        serverSocket?.close()
        serverThread.interrupt()
        return super.onUnbind(intent)
    }
}