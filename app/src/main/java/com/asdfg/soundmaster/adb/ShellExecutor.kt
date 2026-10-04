package com.asdfg.soundmaster.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ShellExecutor"

/**
 * Shell command executor.
 *
 * Runs commands as root through `su` (Magisk, KernelSU, APatch) when root is
 * granted. Without root it falls back to the embedded ADB client, which needs
 * wireless debugging and an imported, already paired ADB key.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ShellExecutor(private val context: Context) {

    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }

    private enum class RootState { UNKNOWN, GRANTED, DENIED }

    @Volatile
    private var rootState = RootState.UNKNOWN
    private val rootLock = Any()

    /** True once su has been granted in this process. */
    val isRootAvailable: Boolean
        get() = rootState == RootState.GRANTED

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
    }

    private val keyStore: AdbKeyStore by lazy {
        PreferenceAdbKeyStore(prefs)
    }

    private val adbKeyName = "soundmaster@${Build.MODEL}"

    // Created on first ADB use, so root mode never has to generate an RSA key.
    private var adbKeyInstance: AdbKey? = null

    @Synchronized
    private fun adbKey(): AdbKey =
        adbKeyInstance ?: AdbKey(keyStore, adbKeyName).also { adbKeyInstance = it }

    private var cachedPort: Int = -1
    private var cachedClient: AtomicReference<AdbClient?> = AtomicReference(null)

    // One dedicated thread plus a fair mutex keeps commands in issue order.
    private val commandDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ShellExecutor").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val commandMutex = Mutex()
    private val scope = CoroutineScope(commandDispatcher + SupervisorJob())

    /**
     * Ask for root through su. The first call shows the root manager's prompt and
     * waits for the answer, so it runs on the IO dispatcher. Calling it again
     * re-checks, which lets the user retry after denying.
     */
    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) { probeRoot() }

    private fun probeRoot(): Boolean = synchronized(rootLock) {
        val granted = runAsRoot("id").getOrNull()?.contains("uid=0") == true
        rootState = if (granted) RootState.GRANTED else RootState.DENIED
        Log.i(TAG, if (granted) "Root access granted" else "Root access not available")
        granted
    }

    /** Blocking; call from a background thread only. */
    private fun useRoot(): Boolean {
        if (rootState == RootState.UNKNOWN) {
            synchronized(rootLock) {
                if (rootState == RootState.UNKNOWN) probeRoot()
            }
        }
        return rootState == RootState.GRANTED
    }

    /** Runs one command through `su -c`. Blocking. */
    private fun runAsRoot(command: String): Result<String> {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Result.success(output)
            } else {
                Result.failure(Exception("su exited with code $exitCode: ${output.trim()}"))
            }
        } catch (e: Exception) {
            // No su binary on this device, or su could not be started
            Result.failure(e)
        }
    }

    /**
     * Import an existing private ADB key
     */
    fun importKey(keyBytes: ByteArray) {
        // Import the key into the store
        adbKey().importPrivateKey(keyBytes)

        // Re-initialize AdbKey instance to load the new private key from store
        synchronized(this) {
            adbKeyInstance = AdbKey(keyStore, adbKeyName)
        }

        // Clear existing connection to force reconnection with the new key
        disconnect()
        
        // Mark as set up since we have a key now
        prefs.edit().putBoolean("adb_imported", true).apply()
    }

    val isSetUp: Boolean
        get() = prefs.getBoolean("adb_paired", false) || prefs.getBoolean("adb_imported", false)

    val isConnected: Boolean
        get() = isRootAvailable || cachedClient.get()?.isConnected == true

    /**
     * Discover wireless debugging port using mDNS
     */
    fun discoverPort(callback: (Int) -> Unit) {
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) {
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                callback(port)
            }
        }
        mdns.start()
        
        // Also try last known port
        val lastPort = prefs.getInt("last_port", -1)
        if (lastPort > 0) {
            cachedPort = lastPort
            callback(lastPort)
        }
    }

    fun getCachedPort(): Int {
        if (cachedPort > 0) return cachedPort
        return prefs.getInt("last_port", -1)
    }

    /**
     * Connect to ADB daemon
     */
    suspend fun connect(port: Int = cachedPort): Boolean {
        if (port <= 0) {
            Log.e(TAG, "Invalid port: $port")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val client = AdbClient("127.0.0.1", port, adbKey())
                client.connect()
                cachedClient.set(client)
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                false
            }
        }
    }

    /**
     * Disconnect from ADB
     */
    fun disconnect() {
        cachedClient.getAndSet(null)?.close()
    }

    /**
     * Execute a shell command. Commands run one at a time, in the order they
     * were issued, so a quick "deny" followed by "allow" cannot swap places.
     */
    fun command(command: String, listener: CommandResultListener) {
        scope.launch {
            val result = commandMutex.withLock { execute(command) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { output -> listener.onCommandResult(output, true) },
                    onFailure = { e -> listener.onCommandError(e.message ?: "Unknown error") }
                )
            }
        }
    }

    /**
     * Execute a shell command synchronously (for use in coroutines)
     */
    suspend fun commandSync(command: String): Result<String> =
        withContext(commandDispatcher) {
            commandMutex.withLock { execute(command) }
        }

    /** Root first; without root, the ADB connection. Blocking parts run on the command thread. */
    private suspend fun execute(command: String): Result<String> {
        if (useRoot()) {
            return runAsRoot(command).onFailure { e ->
                Log.e(TAG, "Root command failed: $command", e)
            }
        }

        return try {
            var client = cachedClient.get()

            // Try to connect if not connected
            if (client == null || !client.isConnected) {
                if (cachedPort <= 0) {
                    cachedPort = prefs.getInt("last_port", -1)
                }
                if (cachedPort > 0 && connect(cachedPort)) {
                    client = cachedClient.get()
                }
            }

            if (client == null || !client.isConnected) {
                Result.failure(Exception("ADB not connected"))
            } else {
                Result.success(client.shellCommand(command, null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            // Connection may be broken, clear it
            disconnect()
            Result.failure(e)
        }
    }

    /**
     * Mark as paired (called after successful pairing)
     */
    fun markAsPaired() {
        prefs.edit().putBoolean("adb_paired", true).apply()
    }

    /**
     * Clear pairing status
     */
    fun clearPairing() {
        prefs.edit()
            .remove("adb_paired")
            .remove("last_port")
            .apply()
        disconnect()
    }

    companion object {
        @Volatile
        private var instance: ShellExecutor? = null

        fun getInstance(context: Context): ShellExecutor {
            return instance ?: synchronized(this) {
                instance ?: ShellExecutor(context.applicationContext).also { instance = it }
            }
        }
    }
}
