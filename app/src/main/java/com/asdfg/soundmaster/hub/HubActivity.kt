package com.asdfg.soundmaster.hub

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.asdfg.soundmaster.R
import com.asdfg.soundmaster.adb.ShellExecutor
import com.asdfg.soundmaster.audio.RootRouting
import com.asdfg.soundmaster.audio.SoundMasterService
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * The Bluetooth hub screen: tick the apps that may play on the Bluetooth speaker, switch the
 * hub on. Everything else keeps playing on the phone.
 */
class HubActivity : AppCompatActivity() {

    private data class AppEntry(val packageName: String, val label: String, val uid: Int)

    private lateinit var shell: ShellExecutor
    private lateinit var hubSwitch: MaterialSwitch
    private lateinit var statusText: TextView
    private lateinit var requirementCard: View
    private lateinit var appFilter: EditText
    private lateinit var appList: LinearLayout
    private lateinit var appListEmpty: TextView
    private lateinit var diagnosticsButton: Button
    private lateinit var copyDiagnosticsButton: Button
    private lateinit var diagnosticsText: TextView

    private var rootReady = false
    private var busy = false
    private var updatingSwitch = false
    private var apps: List<AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()
    private var lastStatusText = ""
    private var notificationShown = false
    private var statusObserver: FileObserver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val periodicRefresh = object : Runnable {
        override fun run() {
            // The status file stops changing when the hub dies: notice that too
            refreshStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        shell = ShellExecutor.getInstance(this)
        HubControl.createChannel(this)

        hubSwitch = findViewById(R.id.hubSwitch)
        statusText = findViewById(R.id.hubStatus)
        requirementCard = findViewById(R.id.requirementCard)
        appFilter = findViewById(R.id.appFilter)
        appList = findViewById(R.id.appList)
        appListEmpty = findViewById(R.id.appListEmpty)
        diagnosticsButton = findViewById(R.id.diagnosticsButton)
        copyDiagnosticsButton = findViewById(R.id.copyDiagnosticsButton)
        diagnosticsText = findViewById(R.id.diagnosticsText)

        selected.addAll(HubControl.selectedPackages(this))

        hubSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingSwitch) {
                if (isChecked) turnOn() else turnOff()
            }
        }
        findViewById<Button>(R.id.openDeveloperOptions).setOnClickListener { openDeveloperOptions() }
        appFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderApps()
            }
        })
        diagnosticsButton.setOnClickListener { showDiagnostics() }
        copyDiagnosticsButton.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("AudioPipe diagnostics", diagnosticsText.text))
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }

        loadApps()
        // The Magisk prompt waits until the notification question is answered
        if (!requestNotificationPermission()) {
            checkRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        statusObserver = object : FileObserver(filesDir, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == HubControl.statusFileName()) {
                    runOnUiThread { refreshStatus() }
                }
            }
        }.also { it.startWatching() }
        handler.post(periodicRefresh)
        // Back from the developer options: check the offload switch again
        if (rootReady && requirementCard.visibility == View.VISIBLE) {
            lifecycleScope.launch { updateRequirement() }
        }
    }

    override fun onStop() {
        statusObserver?.stopWatching()
        statusObserver = null
        handler.removeCallbacks(periodicRefresh)
        super.onStop()
    }

    // ---- root and first start ----

    private fun checkRoot() {
        statusText.text = getString(R.string.hub_checking_root)
        lifecycleScope.launch {
            rootReady = shell.checkRoot()
            if (!rootReady) {
                hubSwitch.isEnabled = false
                showStatus(getString(R.string.hub_no_root))
                return@launch
            }
            // The old capture-based routing and Speaker mode are gone: undo what they left on
            if (SoundMasterService.running) {
                stopService(Intent(this@HubActivity, SoundMasterService::class.java))
            }
            withContext(Dispatchers.IO) {
                if (RootRouting.isKeepOnPhoneEnabled(applicationContext)) {
                    RootRouting.runHelper(applicationContext, RootRouting.ACTION_RELEASE)
                    RootRouting.setKeepOnPhoneEnabled(applicationContext, false)
                }
            }
            hubSwitch.isEnabled = true
            updateRequirement()
            refreshStatus(force = true)
        }
    }

    private suspend fun updateRequirement() {
        val offload = withContext(Dispatchers.IO) { HubControl.offload() }
        requirementCard.visibility = if (offload == HubControl.Offload.ON) View.VISIBLE else View.GONE
    }

    // ---- switching ----

    private fun turnOn() {
        if (selected.isEmpty()) {
            setSwitch(false)
            showStatus(getString(R.string.hub_pick_app_first))
            return
        }
        setBusy(true, getString(R.string.hub_starting))
        lifecycleScope.launch {
            val offload = withContext(Dispatchers.IO) { HubControl.offload() }
            if (offload == HubControl.Offload.ON) {
                requirementCard.visibility = View.VISIBLE
                setSwitch(false)
                setBusy(false, getString(R.string.hub_error_offload))
                return@launch
            }
            val result = withContext(Dispatchers.IO) { HubControl.start(applicationContext) }
            setBusy(false, null)
            result.onSuccess {
                HubControl.setWasOn(applicationContext, true)
                refreshStatus(force = true)
            }.onFailure { error ->
                setSwitch(false)
                showStatus(getString(R.string.hub_start_failed, RootRouting.shortReason(error)))
            }
        }
    }

    private fun turnOff() {
        setBusy(true, getString(R.string.hub_stopping))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { HubControl.stop(applicationContext) }
            HubControl.setWasOn(applicationContext, false)
            setBusy(false, null)
            result.onSuccess {
                refreshStatus(force = true)
            }.onFailure { error ->
                refreshStatus(force = true)
                showStatus(getString(R.string.hub_stop_failed, RootRouting.shortReason(error)))
            }
        }
    }

    private fun setBusy(isBusy: Boolean, text: String?) {
        busy = isBusy
        hubSwitch.isEnabled = !isBusy && rootReady
        if (text != null) {
            showStatus(text)
        }
    }

    private fun setSwitch(checked: Boolean) {
        if (hubSwitch.isChecked == checked) return
        updatingSwitch = true
        hubSwitch.isChecked = checked
        updatingSwitch = false
    }

    private fun showStatus(text: String) {
        // Only on change: TalkBack reads the status line aloud whenever it changes
        if (text != lastStatusText) {
            statusText.text = text
            lastStatusText = text
        }
    }

    // ---- status ----

    private fun refreshStatus(force: Boolean = false) {
        if (busy || !rootReady) return
        val status = HubControl.readStatus(this)
        val running = status?.isRunning == true
        setSwitch(running)
        showStatus(describe(status, running))
        if (running && (!notificationShown || force)) {
            HubControl.showNotification(this, getString(R.string.hub_notification_text))
            notificationShown = true
        } else if (!running && (notificationShown || force)) {
            HubControl.cancelNotification(this)
            notificationShown = false
        }
        if (running && (status?.code == "offload" || status?.code == "shared_output")) {
            requirementCard.visibility = View.VISIBLE
        }
    }

    private fun describe(status: HubControl.Status?, running: Boolean): String {
        if (status == null || !running) return getString(R.string.hub_off)
        return when (status.state) {
            "starting" -> getString(R.string.hub_starting)
            "waiting" -> getString(R.string.hub_waiting)
            "idle" -> getString(R.string.hub_no_apps)
            "active" -> getString(
                R.string.hub_active,
                appNames(status.uids),
                status.device.ifEmpty { getString(R.string.hub_unknown_speaker) }
            )
            "error" -> when (status.code) {
                "offload" -> getString(R.string.hub_error_offload)
                "shared_output" -> getString(R.string.hub_error_shared)
                "register" -> getString(R.string.hub_error_register, status.detail)
                else -> getString(R.string.hub_error_other, status.detail)
            }
            else -> getString(R.string.hub_off)
        }
    }

    private fun appNames(uids: List<Int>): String {
        val names = apps.filter { it.uid in uids }.map { it.label }.distinct()
        return if (names.isEmpty()) getString(R.string.hub_chosen_apps) else names.joinToString(", ")
    }

    // ---- apps ----

    private fun loadApps() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val collator = Collator.getInstance()
                @Suppress("DEPRECATION")
                val launchable = packageManager.queryIntentActivities(launcher, 0)
                launchable
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .map { AppEntry(it.packageName, packageManager.getApplicationLabel(it).toString(), it.uid) }
                    .sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            apps = loaded
            renderApps()
            refreshStatus()
        }
    }

    /** Ticked apps first, then the rest, both alphabetical. */
    private fun renderApps() {
        val filter = appFilter.text.toString().trim()
        val shown = apps
            .filter { filter.isEmpty() || it.label.contains(filter, ignoreCase = true) }
            .sortedByDescending { it.packageName in selected }
        appList.removeAllViews()
        appListEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        appListEmpty.text = getString(if (apps.isEmpty()) R.string.hub_loading_apps else R.string.hub_no_match)
        val rowHeight = (48 * resources.displayMetrics.density).toInt()
        for (entry in shown) {
            val box = CheckBox(this).apply {
                text = entry.label
                isChecked = entry.packageName in selected
                minHeight = rowHeight
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, checked -> onAppChecked(entry, checked) }
            }
            appList.addView(
                box,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun onAppChecked(entry: AppEntry, checked: Boolean) {
        if (checked) selected.add(entry.packageName) else selected.remove(entry.packageName)
        HubControl.setSelectedPackages(this, selected)
        // A running hub picks the new choice up by itself
        if (HubControl.isRunning(this)) {
            lifecycleScope.launch(Dispatchers.IO) { HubControl.writeConfig(applicationContext) }
        }
    }

    // ---- other ----

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showDiagnostics() {
        diagnosticsButton.isEnabled = false
        diagnosticsText.visibility = View.VISIBLE
        diagnosticsText.text = getString(R.string.diagnostics_running)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { HubControl.collectDiagnostics(applicationContext, rootReady) }
                    .getOrElse { "Diagnostics failed: $it" }
            }
            diagnosticsText.text = report
            diagnosticsButton.isEnabled = true
            copyDiagnosticsButton.visibility = View.VISIBLE
            // Put TalkBack on the copy button instead of reading the whole report aloud
            copyDiagnosticsButton.post {
                copyDiagnosticsButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }

    /** Returns true when the permission dialog is shown. */
    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            checkRoot()
        }
    }

    private companion object {
        const val REFRESH_MS = 10_000L
        const val PERMISSION_REQUEST = 2
    }
}
