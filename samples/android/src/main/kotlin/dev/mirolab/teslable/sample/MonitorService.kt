package dev.mirolab.teslable.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 모니터를 포그라운드로 붙드는 서비스(`foregroundServiceType="connectedDevice"`). 화면이 꺼지거나 앱이 백그라운드에 있어도
 * [MonitorEngine](연결 루프, 이벤트 구독, 로그 쓰기)이 계속 돈다. 알림에 링크 상태와 "중지" 동작을 보인다.
 *
 * 시작 조건: `BLUETOOTH_CONNECT` 권한이 허용된 뒤에, 화면이 보이는 동안 [start]로 시작한다(안드로이드 14의 connectedDevice 규칙).
 * 시스템이 서비스를 죽이면 되살리지 않는다(`START_NOT_STICKY`): 되살아나는 서비스는 앱이 보이지 않을 때 시작되어 거부될 수 있다.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private lateinit var engine: MonitorEngine

    override fun onCreate() {
        super.onCreate()
        engine = MonitorRuntime.get(application)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "모니터", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            // 알림의 "중지": 연결을 끊고 자동 재연결을 멈춘 뒤 서비스를 내린다.
            scope.launch {
                engine.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(engine.link.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (watch == null) {
            watch =
                scope.launch {
                    engine.link.collect { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it)) }
                }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) engine.autoConnectIfSaved()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: LinkState<*>): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Teslable 모니터")
            .setContentText(LinkText.of(state))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.mirolab.teslable.sample.STOP"
        private const val EXTRA_AUTO_CONNECT = "autoConnect"

        /** 서비스를 포그라운드로 시작한다. [autoConnect]: 저장된 VIN이 있으면 이 프로세스에서 처음 한 번 자동으로 연결한다. */
        fun start(
            context: Context,
            autoConnect: Boolean,
        ) {
            context.startForegroundService(Intent(context, MonitorService::class.java).putExtra(EXTRA_AUTO_CONNECT, autoConnect))
        }
    }
}

/** 링크 상태를 사람이 읽는 한 줄로(배너와 알림 공용). */
object LinkText {
    fun of(state: LinkState<*>): String =
        when (state) {
            LinkState.Idle -> "대기 중 (연결 전)"
            is LinkState.Connecting -> "연결 중… (시도 ${state.attempt})"
            is LinkState.Connected -> "연결됨"
            is LinkState.Backoff ->
                "재연결 대기 (연속 실패 ${state.attempt}회, ${state.delay.inWholeSeconds}초 뒤)" +
                    (state.reason?.let { ": $it" } ?: "")
            LinkState.Stopped -> "연결 끊음 (자동 재연결 중지)"
        }
}
