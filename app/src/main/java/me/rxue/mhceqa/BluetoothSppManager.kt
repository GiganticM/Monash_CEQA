package me.rxue.mhceqa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * BluetoothSppManager
 *
 * Manages a Bluetooth Classic SPP (Serial Port Profile) connection to a slave device.
 *
 * Key design points:
 *  - [connect] stores the in-progress socket as [pendingSocket] so it can be
 *    interrupted at any time by closing it from [cancelConnect].
 *  - Calling [connect] while a previous attempt is still blocking immediately
 *    cancels the old attempt before starting the new one.
 *  - [disconnect] only tears down an established connection and does NOT cancel
 *    an in-progress attempt - call [cancelConnect] for that.
 *  - The app never auto-reconnects on startup; the user must select a device
 *    explicitly from the picker each session.
 */
class BluetoothSppManager(private val context: Context) {

    companion object {
        private const val TAG            = "BluetoothSppManager"
        private const val PREFS_NAME     = "bt_spp_prefs"
        private const val KEY_LAST_MAC   = "last_device_mac"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val LINE_TERMINATOR = "\r"
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** MAC address of the last successfully connected device - persisted across process death. */
    val lastKnownMac: String?
        get() = prefs.getString(KEY_LAST_MAC, null)
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    /**
     * Holds the socket while a [connect] call is blocking on [BluetoothSocket.connect].
     * Closing this socket from any thread immediately unblocks the blocking call
     * and causes it to throw [IOException], which we catch and treat as cancellation.
     */
    @Volatile private var pendingSocket: BluetoothSocket? = null

    /** The last device that connected successfully - used for silent auto-reconnect. */
    @Volatile var lastDevice: BluetoothDevice? = null
        private set

    /**
     * Set to false just before we deliberately close [pendingSocket] so the
     * IOException catch block can distinguish "cancelled by us" from "real error".
     */
    @Volatile private var connectIntentional = true

    /** Called on the IO thread when the connection drops unexpectedly. */
    var onDisconnected: (() -> Unit)? = null

    /** Background thread that blocks on InputStream.read() - detects disconnection
     *  within 1-2 seconds on any Android version, far faster than ACL broadcast on API 33+. */
    @Volatile private var readerThread: Thread? = null

    private fun startReaderThread() {
        readerThread?.interrupt()
        val s = socket ?: return
        readerThread = Thread {
            val buf = ByteArray(256)
            try {
                val inputStream = s.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = inputStream.read(buf)
                    if (n < 0) break   // stream ended cleanly
                }
            } catch (_: IOException) {
                // Socket closed - either by us (disconnect/cancelConnect) or remote drop
            }
            // Only fire callback if this was an unexpected drop (socket still set = we didn't close it)
            if (socket != null) {
                Log.i(TAG, "Reader thread detected disconnection")
                disconnect()
                onDisconnected?.invoke()
            }
        }.also { it.isDaemon = true; it.name = "bt-reader"; it.start() }
    }

    private fun stopReaderThread() {
        readerThread?.interrupt()
        readerThread = null
    }

    val isConnected: Boolean
        get() = socket?.isConnected == true

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    // ─── Device Discovery ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

    // ─── Connection ───────────────────────────────────────────────────────────

    /**
     * Cancels any in-progress connection attempt immediately by closing the
     * pending socket. The blocking [BluetoothSocket.connect] call will throw
     * an IOException which is caught and treated as [ConnectionResult.Cancelled].
     *
     * Safe to call even if no connection is in progress.
     */
    fun clearLastDevice() {
        lastDevice = null
        prefs.edit().remove(KEY_LAST_MAC).apply()
    }

    fun cancelConnect() {
        connectIntentional = false
        try { pendingSocket?.close() } catch (_: IOException) {}
        pendingSocket = null
    }

    /**
     * Opens an RFCOMM SPP socket to [device].
     *
     * If another connection attempt is already in progress it is cancelled first
     * via [cancelConnect], so this method is safe to call multiple times in
     * quick succession (e.g. user taps a different device before the first
     * attempt times out).
     *
     * Must be called on a background thread (use [Dispatchers.IO]).
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): ConnectionResult = withContext(Dispatchers.IO) {
        // Cancel any still-blocking previous attempt
        cancelConnect()
        // Tear down any established connection
        disconnect()

        connectIntentional = true

        return@withContext try {
            val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            pendingSocket = newSocket
            bluetoothAdapter?.cancelDiscovery()

            newSocket.connect()     // Blocks until connected or socket is closed externally

            // If we get here the connect succeeded - check we weren't cancelled
            // in the instant between connect() returning and this line
            if (!connectIntentional) {
                try { newSocket.close() } catch (_: IOException) {}
                pendingSocket = null
                return@withContext ConnectionResult.Cancelled
            }

            pendingSocket = null
            socket = newSocket
            outputStream = newSocket.outputStream
            lastDevice = device
            prefs.edit().putString(KEY_LAST_MAC, device.address).apply()
            startReaderThread()

            Log.i(TAG, "Connected to ${device.name}")
            ConnectionResult.Success(device.name ?: device.address)

        } catch (e: IOException) {
            pendingSocket = null
            if (!connectIntentional) {
                // We closed the socket deliberately - not an error
                Log.i(TAG, "Connection cancelled (new attempt or app closing)")
                return@withContext ConnectionResult.Cancelled
            }
            Log.e(TAG, "Connection failed: ${e.message}")
            tryFallbackConnect(device)
        }
    }

    /**
     * Fallback for older HC-05/HC-06 modules that reject SDP lookup.
     * Only called after a genuine (non-cancelled) IOException from [connect].
     */
    @SuppressLint("MissingPermission")
    private fun tryFallbackConnect(device: BluetoothDevice): ConnectionResult {
        return try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val fallbackSocket = method.invoke(device, 1) as BluetoothSocket
            pendingSocket = fallbackSocket
            fallbackSocket.connect()

            if (!connectIntentional) {
                try { fallbackSocket.close() } catch (_: IOException) {}
                pendingSocket = null
                return ConnectionResult.Cancelled
            }

            pendingSocket = null
            socket = fallbackSocket
            outputStream = fallbackSocket.outputStream
            lastDevice = device
            prefs.edit().putString(KEY_LAST_MAC, device.address).apply()
            startReaderThread()

            Log.i(TAG, "Connected via fallback to ${device.name}")
            ConnectionResult.Success(device.name ?: device.address)

        } catch (e: Exception) {
            pendingSocket = null
            if (!connectIntentional) return ConnectionResult.Cancelled
            Log.e(TAG, "Fallback connection also failed: ${e.message}")
            ConnectionResult.Failure("Could not connect to ${device.name ?: device.address}.\n${e.message}")
        }
    }

    // ─── Transmission ─────────────────────────────────────────────────────────

    fun sendString(text: String): Boolean {
        val stream = outputStream ?: run {
            Log.w(TAG, "sendString called but not connected")
            return false
        }
        return try {
            val payload = (text + LINE_TERMINATOR).toByteArray(Charsets.US_ASCII)
            stream.write(payload)
            stream.flush()
            Log.d(TAG, "Sent ${payload.size} bytes: $text")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send failed: ${e.message}")
            false
        }
    }

    fun sendBytes(bytes: ByteArray): Boolean {
        val stream = outputStream ?: return false
        return try {
            stream.write(bytes)
            stream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "sendBytes failed: ${e.message}")
            false
        }
    }

    // ─── Disconnection ────────────────────────────────────────────────────────

    /**
     * Closes an established connection and cleans up streams.
     * Does NOT cancel an in-progress connection attempt - use [cancelConnect] for that.
     * Safe to call even if not connected.
     */
    fun disconnect() {
        stopReaderThread()
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() }       catch (_: IOException) {}
        outputStream = null
        socket = null
        Log.i(TAG, "Disconnected")
    }

    // ─── Result ───────────────────────────────────────────────────────────────

    sealed class ConnectionResult {
        data class Success(val deviceName: String) : ConnectionResult()
        data class Failure(val error: String)      : ConnectionResult()
        object Cancelled                            : ConnectionResult()

        val isSuccess get() = this is Success
    }
}