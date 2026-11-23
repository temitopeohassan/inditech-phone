package com.inditech.dpc.messaging

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.R
import com.inditech.dpc.data.api.PlatformJson
import com.inditech.dpc.data.model.DeviceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Foreground service hosting the persistent MQTT fallback socket (secondary
 * command path) and a lightweight in-process watchdog loop.
 */
class EnforcementService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqtt: MqttAsyncClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        connectMqtt()
        scope.launch {
            val repo = (application as DpcApplication).container.repository
            while (isActive) {
                runCatching { repo.watchdog() }
                delay(5 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun connectMqtt() {
        val container = (application as DpcApplication).container
        val endpoint = container.store.mqtt ?: return
        val deviceId = container.store.deviceId ?: return
        try {
            val client = MqttAsyncClient(endpoint.url.replace("mqtts://", "ssl://").replace("mqtt://", "tcp://"), "dpc-$deviceId", MemoryPersistence())
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    runCatching { client.subscribe(endpoint.topic, 1) }
                }
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                }
                override fun messageArrived(topic: String?, message: MqttMessage) {
                    val command = runCatching { PlatformJson.decodeFromString<DeviceCommand>(String(message.payload)) }.getOrNull() ?: return
                    scope.launch { container.repository.handleCommand(command) }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                keepAliveInterval = 240 // battery-friendly keepalive
                connectionTimeout = 15
                container.store.deviceJwt?.let { userName = deviceId; password = it.toCharArray() }
            }
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "MQTT connected")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT connect failed: ${exception?.message}")
                }
            })
            mqtt = client
        } catch (e: Exception) {
            Log.w(TAG, "MQTT unavailable", e)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, DpcApplication.CHANNEL_ENFORCEMENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.enforcement_running))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    override fun onDestroy() {
        runCatching { mqtt?.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EnforcementService"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, EnforcementService::class.java))
            }.onFailure { Log.w(TAG, "could not start enforcement service: ${it.message}") }
        }
    }
}
