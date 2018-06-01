/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentManager.FragmentLifecycleCallbacks
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.BrowserFragment
import org.mozilla.focus.browser.BrowserFragment.Companion.APP_URL_HOME
import org.mozilla.focus.toolbar.ToolbarStateProvider
import org.mozilla.focus.ext.toSafeIntent
import org.mozilla.focus.home.pocket.Pocket
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.iwebview.WebViewProvider
import org.mozilla.focus.locale.LocaleAwareAppCompatActivity
import org.mozilla.focus.session.Session
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.session.Source
import org.mozilla.focus.telemetry.SentryWrapper
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.toolbar.NavigationEvent
import org.mozilla.focus.toolbar.ToolbarIntegration
import org.mozilla.focus.toolbar.UrlBoxProgressView
import org.mozilla.focus.utils.OnUrlEnteredListener
import org.mozilla.focus.utils.SafeIntent
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.utils.ViewUtils
import org.mozilla.focus.utils.publicsuffix.PublicSuffix
import org.mozilla.focus.widget.InlineAutocompleteEditText

class MainActivity : LocaleAwareAppCompatActivity(), OnUrlEnteredListener {

    private val sessionManager = SessionManager.getInstance()

    private val fragmentLifecycleCallbacks = MainActivityFragmentLifecycleCallbacks()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable crash reporting. Don't add anything above here because if it crashes, we won't know.
        SentryWrapper.init(this)
        Pocket.init()
        PublicSuffix.init(this) // Used by Pocket Video feed & custom home tiles.

        val intent = SafeIntent(intent)

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setContentView(R.layout.activity_main)

        IntentValidator.validateOnCreate(this, intent, savedInstanceState, ::onValidBrowserIntent)
        sessionManager.sessions.observe(this, object : NonNullObserver<List<Session>>() {
            public override fun onValueChanged(value: List<Session>) {
                val sessions = value
                if (sessions.isEmpty()) {
                    // There's no active session. Start a new session with "homepage".
                    ScreenController.showBrowserScreenForUrl(supportFragmentManager, APP_URL_HOME, Source.NONE)
                } else {
                    ScreenController.showBrowserScreenForCurrentSession(supportFragmentManager, sessionManager)
                }
            }
        })

        if (Settings.getInstance(this@MainActivity).shouldShowOnboarding()) {
            val onboardingIntent = Intent(this@MainActivity, OnboardingActivity::class.java)
            startActivity(onboardingIntent)
        }

        WebViewProvider.preload(this)
        ToolbarIntegration.setup(toolbar, DelegateToBrowserFragmentNavigationStateProvider(), ::onToolbarEvent)
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks)
    }

    override fun onNewIntent(unsafeIntent: Intent) {
        IntentValidator.validate(this, unsafeIntent.toSafeIntent(), ::onValidBrowserIntent)
    }

    private fun onValidBrowserIntent(url: String, source: Source) {
        ScreenController.showBrowserScreenForUrl(supportFragmentManager, url, source)
    }

    override fun applyLocale() {
        // We don't care here: all our fragments update themselves as appropriate
    }

    override fun onResume() {
        super.onResume()
        TelemetryWrapper.startSession(this)
    }

    override fun onPause() {
        super.onPause()
        TelemetryWrapper.stopSession(this)
    }

    override fun onStart() {
        super.onStart()
        Pocket.startBackgroundUpdates()
    }

    override fun onStop() {
        super.onStop()
        Pocket.stopBackgroundUpdates() // Don't regularly hit the network in the background.
        TelemetryWrapper.stopMainActivity()
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return if (name == IWebView::class.java.name) {
            // Inject our implementation of IWebView from the WebViewProvider.
            WebViewProvider.create(this, attrs)
        } else super.onCreateView(name, context, attrs)
    }

    override fun onBackPressed() {
        val fragmentManager = supportFragmentManager
        val browserFragment = fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?
        if (browserFragment != null &&
                browserFragment.isVisible &&
                browserFragment.onBackPressed()) {
            // The Browser fragment handles back presses on its own because it might just go back
            // in the browsing history.
            return
        }
        super.onBackPressed()
    }

    override fun onNonTextInputUrlEntered(urlStr: String) {
        ViewUtils.hideKeyboard(container)
        ScreenController.onUrlEnteredInner(this, supportFragmentManager, urlStr, false,
                null, null)
    }

    override fun onTextInputUrlEntered(urlStr: String,
                                       autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?,
                                       inputLocation: UrlTextInputLocation?) {
        ViewUtils.hideKeyboard(container)
        // It'd be much cleaner/safer to do this with a kotlin callback.
        ScreenController.onUrlEnteredInner(this, supportFragmentManager, urlStr, true,
                autocompleteResult, inputLocation)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragmentManager = supportFragmentManager
        val browserFragment = fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?

        return if (browserFragment != null && browserFragment.isVisible) {
            browserFragment.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    private fun onToolbarEvent(event: NavigationEvent, value: String?, autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?) {
        val fragmentManager = supportFragmentManager

        when (event) {
            NavigationEvent.SETTINGS -> {
                ScreenController.showSettingsScreen(fragmentManager)
                return
            }

            NavigationEvent.TURBO -> Settings.getInstance(this).isBlockingEnabled = value == NavigationEvent.VAL_CHECKED
            else -> Unit // Do nothing.
        }

        val browserFragment = fragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?
        if (browserFragment != null && browserFragment.isVisible) {
            browserFragment.onNavigationEvent(event, value, autocompleteResult)
        } // BrowserFragment is our only fragment: this else case should never happen.
    }

    private inner class MainActivityFragmentLifecycleCallbacks : FragmentLifecycleCallbacks() {
        override fun onFragmentAttached(fragmentManager: FragmentManager, fragment: Fragment, context: Context) {
            if (fragment is BrowserFragment) {
                fragment.onUrlUpdate = { url ->
                    toolbar.url = when (url) {
                        APP_URL_HOME -> "" // Uses hint instead
                        null -> toolbar.url
                        else -> url
                    }

                    toolbar.invalidateActions()
                }

                val progressBar = toolbar.urlBoxView as UrlBoxProgressView
                fragment.onSessionProgressUpdate = { value ->
                    progressBar.progress = value
                }
            }
        }
    }

    private inner class DelegateToBrowserFragmentNavigationStateProvider : ToolbarStateProvider {
        private fun getBrowserNavProvider() = (supportFragmentManager.findFragmentByTag(BrowserFragment.FRAGMENT_TAG) as BrowserFragment?)
                ?.navigationStateProvider

        override fun isBackEnabled() = getBrowserNavProvider()?.isBackEnabled() ?: false
        override fun isForwardEnabled() = getBrowserNavProvider()?.isForwardEnabled() ?: false
        override fun getCurrentUrl() = getBrowserNavProvider()?.getCurrentUrl()
        override fun isURLPinned() = getBrowserNavProvider()?.isURLPinned() ?: false
        override fun isPinEnabled() = getBrowserNavProvider()?.isPinEnabled() ?: false
        override fun isRefreshEnabled() = getBrowserNavProvider()?.isRefreshEnabled() ?: false
    }
}
