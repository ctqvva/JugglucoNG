package tk.glucodata

import android.app.Application
import android.content.Context

/**
 * The per-variant composition root, behind one contract instead of two same-named classes.
 *
 * ## Why [tk.glucodata.Specific] still exists twice
 *
 * `Applic` is in `src/main` and it calls this seam. `src/main` cannot name a class that
 * lives in `src/mobile` or `src/wear`, so the two flavours must keep a class of the same
 * name for shared code to have anything to call. That is the design here, not an accident,
 * and it is the same reason `ui.AlarmActivity` is an allow-list entry: the alternative was
 * a per-variant `Application` subclass, which needs `android:name` repointed in two flavour
 * manifests plus `tools:replace` against the main manifest, and moves the registration out
 * of `Applic.onCreate()` -- the ordering that closed the post-reboot window where forecast
 * alerts fired on the two-point fallback slope. A mistake in a manifest only shows at
 * runtime; the registration order is the sensitive part.
 *
 * So `Specific` is a shim with exactly one member, [Specific.registerBridges], pinned by
 * `SpecificCompositionRootTests.theShimDeclaresOnlyRegisterBridges`. Everything below moves
 * here. `BridgeRegistrationTest` covers what the shim registers, not what the class declares,
 * which is why the shim needs its own assertion: a member added back would be a silent
 * per-variant difference.
 *
 * ## Lifecycle
 *
 * Registered from [Specific.registerBridges] as the first statement of `Applic.onCreate()`.
 * That runs twice, from `onCreate()` and again from the variant's `start()` safety net, so
 * [register] keeps the first factory and [get] keeps the first instance: a second instance
 * would hand out a different object than the one a caller already holds.
 */
interface VariantBootstrap {
    fun start(application: Application)
    fun splash(activity: MainActivity)
    fun initScreen(activity: MainActivity)
    fun wearnosensors(activity: MainActivity)
    fun historyDatabaseCompatible(context: Context): Boolean

    /**
     * The watch's implementation used to return whether its startup text view existed to put
     * the message in. Nothing reads it -- not in any compiled source set, not in native code
     * -- so the contract returns [Unit] and the watch drops it.
     */
    fun settext(text: String)
    fun rmlayout()

    /**
     * Whether the close button is offered. This is a persisted native setting, not variant
     * state: the phone has always answered `true`, and the watch is a cache of
     * `!Natives.getdontuseclose()`. The cache and its `setclose` writer are gone, so the watch
     * reads the setting directly.
     */
    fun useCloseButton(): Boolean
}

object VariantBootstrapAccess {
    fun interface Factory {
        fun create(): VariantBootstrap
    }

    @Volatile
    private var factory: Factory? = null

    @Volatile
    private var instance: VariantBootstrap? = null

    /**
     * First registration wins, unlike [GlucoseAlarmsAccess.register] which overwrites. The
     * watch's [VariantBootstrap.settext] and friends operate on static state, so replacing the
     * instance would be harmless -- but keeping the first is the cheaper invariant and the one
     * that cannot surprise a later variant.
     */
    @JvmStatic
    fun register(factory: Factory) {
        if (this.factory == null) this.factory = factory
    }

    /**
     * The bootstrap for this process.
     *
     * Non-null, unlike [GlucoseAlarmsAccess.create]: that one is nullable because a flavour
     * may register nothing, and the caller null-checks per #465. This one cannot be absent --
     * `Specific.registerBridges()` is unconditional, and it runs as the first statement of
     * `Applic.onCreate()`, before any of these call sites exists. So there is no absence to
     * handle and a missing registration is a bug, which fails here rather than as a null
     * dereference in a component.
     */
    @JvmStatic
    fun create(): VariantBootstrap = checkNotNull(instance ?: factory?.create()?.also { instance = it }) {
        "VariantBootstrap is not registered. Specific.registerBridges() must be the first " +
            "statement of Applic.onCreate(); see the comment there."
    }

    /**
     * Static-import target for the 32 read sites, so each one stays a bare call.
     *
     * Every reader is a method body in a component -- seven shared Activities and dialogs,
     * plus four watch View screens -- and no component can be constructed before
     * `Application.onCreate()` returns, which is after registration. So this cannot be
     * reached unregistered, and it deliberately has no default: an unreachable fallback that
     * returned one flavour's answer would be a place for the next reader to be wrong.
     *
     * Worth knowing if a reader is ever added earlier than the rest: when the disk check
     * fails, `initproc()` never runs and the native settings are never loaded, but the only
     * things that do run are the watch's `splash` and the plain dialogs in `outofStorageSpace`
     * and `makefilesfailed`, none of which reads this. Screens reached from `MainActivity`
     * are behind its `stopprogram` guard, and the four watch classes are Views rather than
     * manifest components, so the system cannot launch them on its own. If a *new* reader
     * ever lands before the settings are loaded, it needs the old default, `false`, rather
     * than this call.
     */
    @JvmStatic
    fun useCloseButton(): Boolean = create().useCloseButton()
}
