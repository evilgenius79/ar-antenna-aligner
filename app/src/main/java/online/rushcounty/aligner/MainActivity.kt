package online.rushcounty.aligner

import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import online.rushcounty.aligner.ui.AppViewModel
import online.rushcounty.aligner.ui.ArScreen
import online.rushcounty.aligner.ui.Screen
import online.rushcounty.aligner.ui.SettingsScreen
import online.rushcounty.aligner.ui.TargetsScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private var cameraGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var resumed = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
            if (resumed && locationGranted) vm.location.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        val lastCrash = crashFile.takeIf { it.exists() }?.readText()
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        refreshPermissions()
        if (!cameraGranted || !locationGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    var crashText by remember { mutableStateOf(lastCrash) }
                    crashText?.let { text ->
                        // Shown once after a crash so the trace can be sent without adb.
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("The app crashed last time") },
                            text = {
                                Text(text.take(4000), fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("crash", text))
                                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                                }) { Text("Copy") }
                            },
                            dismissButton = {
                                TextButton(onClick = { crashFile.delete(); crashText = null }) { Text("Dismiss") }
                            },
                        )
                    }
                    val screen by vm.screen.collectAsStateWithLifecycle()
                    when (screen) {
                        Screen.AR -> ArScreen(vm, cameraGranted, locationGranted)
                        Screen.TARGETS -> TargetsScreen(vm)
                        Screen.SETTINGS -> SettingsScreen(vm)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPermissions()
        vm.orientation.start()
        if (locationGranted) vm.location.start()
    }

    override fun onPause() {
        resumed = false
        vm.orientation.stop()
        vm.location.stop()
        super.onPause()
    }

    private val crashFile get() = File(filesDir, "last_crash.txt")

    /** Save any uncaught exception to a file so the next launch can show it. */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                crashFile.writeText(
                    "${BuildConfigInfo.describe(this)}\nThread: ${thread.name}\n\n${Log.getStackTraceString(e)}"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun refreshPermissions() {
        cameraGranted = granted(Manifest.permission.CAMERA)
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

private object BuildConfigInfo {
    fun describe(a: ComponentActivity): String {
        val v = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull()
        return "App $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
}
