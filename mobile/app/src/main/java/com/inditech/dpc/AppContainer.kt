package com.inditech.dpc

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.inditech.dpc.data.DeviceRepository
import com.inditech.dpc.data.SecureStore
import com.inditech.dpc.domain.EnforcementEngine
import com.inditech.dpc.domain.IntegrityChecker
import com.inditech.dpc.domain.SecureClock
import com.inditech.dpc.domain.TimeSource
import com.inditech.dpc.policy.DeviceSecurityManager
import com.inditech.dpc.policy.Enforcer

class AndroidTimeSource(private val context: Context) : TimeSource {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun wallClock(): Long = System.currentTimeMillis()
    override fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
}

/** Manual dependency graph (kept tiny on purpose: no DI framework in a Device Owner app). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val store = SecureStore.create(appContext)
    val clock = SecureClock(store.raw, AndroidTimeSource(appContext))
    val engine = EnforcementEngine(store.raw, clock)
    val integrity = IntegrityChecker(appContext)
    val securityManager = DeviceSecurityManager(appContext)
    val enforcer = Enforcer(store, engine, securityManager)
    val repository = DeviceRepository(appContext, store, clock, engine, integrity, enforcer)
}
