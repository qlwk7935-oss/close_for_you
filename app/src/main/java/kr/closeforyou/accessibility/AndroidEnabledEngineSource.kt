package kr.closeforyou.accessibility

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only enabled-component setting. No package enumeration, label lookup, UI dependency or log.
 * Enabled != bound: even a C service still binding (or crashed) suppresses A while its switch is ON.
 * AccessibilityManager's enabled-service list is not used as an absence/binding certificate. */
internal class AndroidEnabledEngineSource(private val resolver: ContentResolver) : EnabledEngineSource {
    override fun read(): OtherEngineState = EnabledEnginePolicy.fromSecureSetting(
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    )

    override fun observe(onChange: () -> Unit): AutoCloseable {
        val closed = AtomicBoolean()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!closed.get()) onChange()
            }
        }
        try {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
            )
        } catch (failure: Exception) {
            closed.set(true)
            try { resolver.unregisterContentObserver(observer) } catch (_: Exception) { }
            throw failure
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) resolver.unregisterContentObserver(observer)
        }
    }
}
