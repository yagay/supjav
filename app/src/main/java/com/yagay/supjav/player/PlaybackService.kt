package com.yagay.supjav.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yagay.supjav.MainActivity
import com.yagay.supjav.SupjavApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate() {
        super.onCreate()
        val app = application as SupjavApp
        val pending = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, app.playerEngine.player)
            .setSessionActivity(pending).build()
        scope.launch {
            while (isActive) {
                delay(10_000)
                app.playerEngine.savePosition()
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        (application as SupjavApp).playerEngine.savePosition()
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
