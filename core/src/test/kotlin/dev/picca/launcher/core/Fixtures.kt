package dev.picca.launcher.core

import dev.picca.launcher.core.engine.AppCatalog
import dev.picca.launcher.core.engine.LayoutEngine
import dev.picca.launcher.core.engine.LayoutException
import dev.picca.launcher.core.engine.SequentialIds
import dev.picca.launcher.core.model.AppRef
import dev.picca.launcher.core.model.Layout
import org.junit.Assert.assertEquals

/** A realistic fake phone: ~30 apps across the usual categories. Immutable — the engine can never touch it. */
object FakePhone : AppCatalog {
    val apps: List<AppRef> = listOf(
        app("com.android.dialer", "Phone"),
        app("com.google.android.apps.messaging", "Messages"),
        app("com.android.camera2", "Camera"),
        app("com.android.chrome", "Chrome"),
        app("com.google.android.gm", "Gmail"),
        app("com.google.android.apps.maps", "Maps"),
        app("com.google.android.youtube", "YouTube"),
        app("com.spotify.music", "Spotify"),
        app("com.netflix.mediaclient", "Netflix"),
        app("com.instagram.android", "Instagram"),
        app("com.twitter.android", "X"),
        app("com.reddit.frontpage", "Reddit"),
        app("com.whatsapp", "WhatsApp"),
        app("com.slack", "Slack"),
        app("com.microsoft.teams", "Teams"),
        app("com.microsoft.office.outlook", "Outlook"),
        app("com.databricks.mobile", "Databricks"),
        app("com.chase.sig.android", "Chase"),
        app("com.venmo", "Venmo"),
        app("com.robinhood.android", "Robinhood"),
        app("com.ubercab", "Uber"),
        app("com.airbnb.android", "Airbnb"),
        app("com.google.android.apps.photos", "Photos"),
        app("com.android.settings", "Settings"),
        app("com.google.android.calculator", "Calculator"),
        app("com.google.android.deskclock", "Clock"),
        app("com.anthropic.claude", "Claude"),
        app("com.strava", "Strava"),
        app("com.fitbit.FitbitMobile", "Fitbit"),
        app("com.nianticlabs.pokemongo", "Pokémon GO"),
        // One package with two launchable activities, to exercise disambiguation.
        AppRef("com.samsung.android.app.notes", "com.samsung.android.app.notes.MainActivity", "Samsung Notes"),
        AppRef("com.samsung.android.app.notes", "com.samsung.android.app.notes.WidgetActivity", "Notes Widget"),
    )

    override fun installedApps(): List<AppRef> = apps

    fun app(pkg: String, label: String) = AppRef(pkg, "$pkg.MainActivity", label)
    fun key(pkg: String): String = apps.first { it.packageName == pkg }.componentKey
}

fun engine(
    initial: Layout = Layout(),
    catalog: AppCatalog = FakePhone,
    onCommit: (Layout) -> Unit = {},
) = LayoutEngine(initial, catalog, SequentialIds(), onCommit = onCommit)

inline fun expectFailure(code: LayoutException.Code? = null, block: () -> Unit): LayoutException {
    try {
        block()
    } catch (e: LayoutException) {
        if (code != null) assertEquals("wrong error code; message: ${e.message}", code, e.code)
        return e
    }
    throw AssertionError("expected LayoutException${code?.let { "($it)" } ?: ""} but nothing was thrown")
}
