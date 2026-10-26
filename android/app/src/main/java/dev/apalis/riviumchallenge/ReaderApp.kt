package dev.apalis.riviumchallenge

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReaderApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)

        // Links saved while offline give up after a few attempts; when a network
        // becomes available (also fired once at registration), queue them again.
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        if (!ReaderHost.ensureStarted(this@ReaderApp)) return@launch
                        val requeued = ReaderHost.retryFailed()
                        if (requeued > 0) {
                            Log.i("ReaderApp", "Back online: retrying $requeued saved link(s)")
                            runCatching { ReaderService.sync(this@ReaderApp) }
                        }
                    }
                }
            },
        )
    }
}
