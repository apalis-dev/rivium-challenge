package io.github.kdbrian.rivapln.todoradar.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.kdbrian.rivapln.todoradar.engine.Engine
import io.github.kdbrian.rivapln.todoradar.engine.EngineConfig
import io.github.kdbrian.rivapln.todoradar.engine.NativeEngine
import java.io.File

/** Owns the process-wide engine (one Rivium host per process) and tracks app visibility. */
class RadarApplication : Application() {
    lateinit var engine: Engine
        private set

    /** Engine root: radar.db, cache/ and inbox/ live here. */
    val engineRoot: File get() = File(filesDir, "engine")

    @Volatile
    var visibleActivities = 0
        private set

    override fun onCreate() {
        super.onCreate()
        engine = NativeEngine.createOrDemo(
            root = engineRoot,
            config = EngineConfig(githubClientId = getString(R.string.github_client_id)),
            load = { System.loadLibrary("radar_jni") },
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityStopped(activity: Activity) { visibleActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

val Activity.radarApp get() = application as RadarApplication
