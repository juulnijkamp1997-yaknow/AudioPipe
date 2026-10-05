package com.asdfg.soundmaster

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.asdfg.soundmaster.adb.ShellExecutor
import com.asdfg.soundmaster.audio.OutputDevices
import com.asdfg.soundmaster.audio.RootRouting
import com.asdfg.soundmaster.audio.SoundMasterService
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERMISSION_REQUEST_CODE = 1

@RequiresApi(Build.VERSION_CODES.R)
class MainActivity : AppCompatActivity() {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var audioManager: AudioManager

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var importKeyButton: Button
    private lateinit var appSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var balanceSeekBar: SeekBar
    private lateinit var balanceValue: TextView
    private lateinit var startStopButton: Button
    private lateinit var serviceStatus: TextView
    private lateinit var keepOnPhoneSwitch: MaterialSwitch
    private lateinit var keepOnPhoneStatus: TextView
    private lateinit var diagnosticsButton: Button
    private lateinit var diagnosticsText: TextView
    private lateinit var copyDiagnosticsButton: Button

    // Set while the switch is moved from code, so that does not count as the user flipping it
    private var updatingKeepOnPhoneSwitch = false

    // Device list and root check wait for the permission dialogs (see onCreate)
    private var permissionsSettled = false
    private var deviceCallbackRegistered = false

    private var installedApps: List<ApplicationInfo> = emptyList()
    private var audioOutputs: List<AudioDeviceInfo> = emptyList()
    private var outputLabels: List<String> = emptyList()
    private var selectedApp: String? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var currentVolume = 100f
    private var currentBalance = 0f

    // Keeps the output list current while the app is open: headphones that
    // connect or disconnect show up or disappear without reopening the app.
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            loadAudioOutputs()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            loadAudioOutputs()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SoundMasterService.projectionData = result.data
            startSoundMaster()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    // Basic validation: Private key usually starts with -----BEGIN PRIVATE KEY-----
                    // or involves some binary structure. We'll trust the user for now.
                    if (bytes.isNotEmpty()) {
                        shellExecutor.importKey(bytes)
                        Toast.makeText(this, "Key imported! Connecting...", Toast.LENGTH_SHORT).show()
                        updateAdbStatus(false) // Reset status
                        
                        // Trigger immediate connection attempt
                        lifecycleScope.launch(Dispatchers.IO) {
                            shellExecutor.discoverPort { port ->
                                if (port > 0) {
                                    lifecycleScope.launch {
                                        val connected = shellExecutor.connect(port)
                                        withContext(Dispatchers.Main) {
                                            updateAdbStatus(connected)
                                            if (connected) {
                                                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "Empty file selected", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shellExecutor = ShellExecutor.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        bindViews()
        setupListeners()
        balanceSeekBar.stateDescription = balanceDescription(0)
        loadApps()
        // The device list is read only once the permission dialogs are answered: Android keeps
        // the list per process, and one read before "Nearby devices" is granted can keep
        // Bluetooth speakers hidden until the app restarts.
        if (!requestPermissions()) {
            onPermissionsSettled()
        }
    }

    private fun onPermissionsSettled() {
        permissionsSettled = true
        loadAudioOutputs()
        if (!deviceCallbackRegistered) {
            // A null handler delivers the callbacks on the main thread
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            deviceCallbackRegistered = true
        }
        checkAdbStatus()
    }

    private fun bindViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        importKeyButton = findViewById(R.id.importKeyButton)
        
        appSpinner = findViewById(R.id.appSpinner)
        outputSpinner = findViewById(R.id.outputSpinner)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        volumeValue = findViewById(R.id.volumeValue)
        balanceSeekBar = findViewById(R.id.balanceSeekBar)
        balanceValue = findViewById(R.id.balanceValue)
        startStopButton = findViewById(R.id.startStopButton)
        serviceStatus = findViewById(R.id.serviceStatus)
        keepOnPhoneSwitch = findViewById(R.id.keepOnPhoneSwitch)
        keepOnPhoneStatus = findViewById(R.id.keepOnPhoneStatus)
        diagnosticsButton = findViewById(R.id.diagnosticsButton)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        copyDiagnosticsButton = findViewById(R.id.copyDiagnosticsButton)

        // Usable once root is confirmed
        setKeepOnPhoneSwitch(RootRouting.isKeepOnPhoneEnabled(this))
        keepOnPhoneSwitch.isEnabled = false
    }

    private fun setupListeners() {
        keepOnPhoneSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingKeepOnPhoneSwitch) {
                applyKeepOnPhone(isChecked)
            }
        }

        diagnosticsButton.setOnClickListener { showDiagnostics() }

        copyDiagnosticsButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AudioPipe diagnostics", diagnosticsText.text))
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }

        connectButton.setOnClickListener {
           // Retry connection logic
           Toast.makeText(this, "Retrying connection...", Toast.LENGTH_SHORT).show()
           checkAdbStatus()
        }

        importKeyButton.setOnClickListener {
            importKeyLauncher.launch(arrayOf("*/*"))
        }

        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                 val newApp = installedApps.getOrNull(position)?.packageName
                 if (selectedApp != newApp) {
                     selectedApp = newApp
                     if (SoundMasterService.running) {
                         Toast.makeText(this@MainActivity, "Stop service to change app", Toast.LENGTH_SHORT).show()
                     }
                 }
             }
             override fun onNothingSelected(parent: AdapterView<*>?) {
                 selectedApp = null
             }
        }

        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newOutput = audioOutputs.getOrNull(position)
                val oldOutput = selectedOutput
                selectedOutput = newOutput
                // A refreshed list holds new objects for the same devices; compare ids,
                // so a refresh never counts as the user picking another device.
                if (oldOutput?.id == newOutput?.id) return

                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.let { thread ->
                            if (thread.switchOutputDevice(oldOutput?.id ?: -1, newOutput)) {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.switched_to, outputLabel(newOutput)),
                                    Toast.LENGTH_SHORT
                                ).show()
                                updateServiceUI(true)
                            }
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedOutput = null
            }
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolume = progress.toFloat()
                volumeValue.text = "$progress%"
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setVolume(selectedOutput?.id ?: -1, currentVolume)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        balanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBalance = (progress - 100).toFloat()
                balanceValue.text = currentBalance.toInt().toString()
                balanceSeekBar.stateDescription = balanceDescription(currentBalance.toInt())
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setBalance(selectedOutput?.id ?: -1, currentBalance)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startStopButton.setOnClickListener {
            if (SoundMasterService.running) {
                stopService(Intent(this, SoundMasterService::class.java))
                updateServiceUI(false)
            } else {
                if (selectedApp == null) {
                    Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!shellExecutor.isConnected) {
                    Toast.makeText(this, "Connecting to ADB...", Toast.LENGTH_SHORT).show()
                    statusText.text = "Connecting..."
                    
                    lifecycleScope.launch {
                        val port = shellExecutor.getCachedPort()
                        if (port > 0 && shellExecutor.connect(port)) {
                            updateAdbStatus(true)
                            requestMediaProjection()
                        } else {
                            shellExecutor.discoverPort { newPort ->
                                if (newPort > 0) {
                                    lifecycleScope.launch {
                                        if (shellExecutor.connect(newPort)) {
                                            withContext(Dispatchers.Main) {
                                                updateAdbStatus(true)
                                                requestMediaProjection()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "Failed to connect to ADB", Toast.LENGTH_SHORT).show()
                                                updateAdbStatus(false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return@setOnClickListener
                }

                requestMediaProjection()
            }
        }
    }

    private fun checkAdbStatus() {
        statusText.text = getString(R.string.checking_root)
        lifecycleScope.launch {
            // Root first: shows the Magisk prompt on first launch.
            if (shellExecutor.checkRoot()) {
                updateRootStatus()
                return@launch
            }

            // No root: fall back to wireless debugging
            if (shellExecutor.isSetUp) {
                shellExecutor.discoverPort { port ->
                    if (port > 0) {
                        lifecycleScope.launch {
                            val connected = shellExecutor.connect(port)
                            withContext(Dispatchers.Main) {
                                updateAdbStatus(connected)
                            }
                        }
                    } else {
                        runOnUiThread { updateAdbStatus(false) }
                    }
                }
            } else {
                updateAdbStatus(false)
            }
        }
    }

    // Without this, TalkBack reads the centre position of the balance slider as "50 percent".
    private fun balanceDescription(value: Int): String = when {
        value < 0 -> getString(R.string.balance_left, -value)
        value > 0 -> getString(R.string.balance_right, value)
        else -> getString(R.string.balance_center)
    }

    // The Retry button only shows when there is something to retry; a button
    // labelled "Connected" that does nothing is confusing with TalkBack.
    private fun updateRootStatus() {
        statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
        statusText.text = getString(R.string.connected_root)
        connectButton.visibility = View.GONE
        importKeyButton.visibility = View.GONE

        // With this app-op Android skips the "start recording or casting" dialog on every Start.
        // AudioPipe only uses that permission to capture one app's sound, never the screen.
        lifecycleScope.launch(Dispatchers.IO) {
            RootRouting.runAsRoot("appops set $packageName PROJECT_MEDIA allow")
        }

        keepOnPhoneSwitch.isEnabled = true
        if (RootRouting.isKeepOnPhoneEnabled(this)) {
            // Apply again: routing settings may not survive a reboot
            applyKeepOnPhone(true)
        } else {
            keepOnPhoneStatus.text = getString(R.string.keep_on_phone_off)
        }
    }

    private fun updateAdbStatus(connected: Boolean) {
        keepOnPhoneSwitch.isEnabled = false
        keepOnPhoneStatus.text = getString(R.string.keep_on_phone_needs_root)
        importKeyButton.visibility = View.VISIBLE
        if (connected) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
            statusText.text = getString(R.string.connected_adb)
            connectButton.visibility = View.GONE
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            statusText.text = getString(R.string.disconnected)
            connectButton.text = getString(R.string.retry)
            connectButton.visibility = View.VISIBLE
        }
    }

    private fun setKeepOnPhoneSwitch(checked: Boolean) {
        updatingKeepOnPhoneSwitch = true
        keepOnPhoneSwitch.isChecked = checked
        updatingKeepOnPhoneSwitch = false
    }

    /** Turns "keep TalkBack and notifications on the phone" on or off through the root helper. */
    private fun applyKeepOnPhone(enabled: Boolean) {
        setKeepOnPhoneSwitch(enabled)
        keepOnPhoneSwitch.isEnabled = false
        keepOnPhoneStatus.text = getString(R.string.keep_on_phone_working)
        lifecycleScope.launch {
            val action = if (enabled) RootRouting.ACTION_KEEP_ON_PHONE else RootRouting.ACTION_RELEASE
            val result = withContext(Dispatchers.IO) {
                RootRouting.runHelper(applicationContext, action).onFailure {
                    // A half-applied change is worse than none: undo what did get through
                    if (enabled) RootRouting.runHelper(applicationContext, RootRouting.ACTION_RELEASE)
                }
            }
            keepOnPhoneSwitch.isEnabled = shellExecutor.isRootAvailable
            result.onSuccess {
                RootRouting.setKeepOnPhoneEnabled(this@MainActivity, enabled)
                keepOnPhoneStatus.text =
                    getString(if (enabled) R.string.keep_on_phone_on else R.string.keep_on_phone_off)
            }.onFailure { error ->
                // Show the real state: a failed "on" leaves it off, a failed "off" leaves it on
                RootRouting.setKeepOnPhoneEnabled(this@MainActivity, !enabled)
                setKeepOnPhoneSwitch(!enabled)
                keepOnPhoneStatus.text =
                    getString(R.string.keep_on_phone_failed, RootRouting.shortReason(error))
            }
        }
    }

    private fun showDiagnostics() {
        diagnosticsButton.isEnabled = false
        diagnosticsText.visibility = View.VISIBLE
        diagnosticsText.text = getString(R.string.diagnostics_running)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    RootRouting.collectDiagnostics(applicationContext, shellExecutor.isRootAvailable)
                }.getOrElse { "Diagnostics failed: $it" }
            }
            diagnosticsText.text = report
            diagnosticsButton.isEnabled = true
            copyDiagnosticsButton.visibility = View.VISIBLE
            // Put TalkBack on the copy button instead of reading the whole report aloud
            copyDiagnosticsButton.post {
                copyDiagnosticsButton.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null
                )
            }
        }
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .sortedBy { packageManager.getApplicationLabel(it).toString() }

            val appNames = installedApps.map { packageManager.getApplicationLabel(it).toString() }

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, appNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                appSpinner.adapter = adapter

                val spotifyIndex = installedApps.indexOfFirst { it.packageName == "com.spotify.music" }
                if (spotifyIndex >= 0) {
                    appSpinner.setSelection(spotifyIndex)
                }
            }
        }
    }

    private fun loadAudioOutputs() {
        val devices = OutputDevices.list(audioManager)
        val labels = OutputDevices.labels(this, devices)

        // Nothing changed: leave the picker alone, so TalkBack focus is not disturbed
        if (devices.map { it.id } == audioOutputs.map { it.id } && labels == outputLabels) return

        val previousId = selectedOutput?.id
        audioOutputs = devices
        outputLabels = labels

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        outputSpinner.adapter = adapter

        if (devices.isEmpty()) return
        // Keep the device the user picked while it stays connected
        val keptIndex = devices.indexOfFirst { it.id == previousId }
        outputSpinner.setSelection(if (keptIndex >= 0) keptIndex else OutputDevices.defaultIndex(devices))
    }

    private fun outputLabel(device: AudioDeviceInfo?): String =
        device?.let { OutputDevices.label(this, it) } ?: getString(R.string.output_default)

    private fun appLabel(packageName: String?): String {
        val info = installedApps.firstOrNull { it.packageName == packageName } ?: return packageName.orEmpty()
        return packageManager.getApplicationLabel(info).toString()
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSoundMaster() {
        val intent = Intent(this, SoundMasterService::class.java).apply {
            putExtra("packages", arrayOf(selectedApp))
            putExtra("devices", intArrayOf(selectedOutput?.id ?: -1))
            putExtra("volumes", floatArrayOf(currentVolume))
        }
        startForegroundService(intent)
        updateServiceUI(true)
    }

    private fun updateServiceUI(running: Boolean) {
        if (running) {
            startStopButton.text = getString(R.string.stop)
            serviceStatus.text = getString(
                R.string.routing_status,
                appLabel(selectedApp),
                outputLabel(selectedOutput)
            )
        } else {
            startStopButton.text = getString(R.string.start)
            serviceStatus.text = ""
        }
    }

    private fun getService(): SoundMasterService? {
        return SoundMasterService.instance
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SoundMasterService.NOTIFICATION_CHANNEL,
            "SoundMaster Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /** Returns true when a permission dialog is shown. */
    private fun requestPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            // "Nearby devices": lets the app see connected Bluetooth speakers
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            return true
        }
        return false
    }

    // The device list and the root check wait until the permission dialogs are answered, so
    // Bluetooth speakers are not hidden and the Magisk prompt is never covered by a dialog.
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            onPermissionsSettled()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI(SoundMasterService.running)
        if (permissionsSettled) {
            loadAudioOutputs()
        }
    }

    override fun onDestroy() {
        if (deviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        super.onDestroy()
    }
}
