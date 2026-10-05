package org.schabi.newpipe.localserver;

import android.app.Application;
import android.util.Log;

import com.google.android.material.color.DynamicColors;

import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;

public class LocalServerApplication extends Application {

    private static final String TAG = "LocalServerApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        // Enable wallpaper-based dynamic color theming across all activities (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this);

        registerPoTokenProvider();
    }

    /**
     * Registers the WebView-based BotGuard poToken provider with NewPipeExtractor.
     *
     * <p>
     * poTokens (proof of origin tokens) prove to YouTube that a real browser environment is
     * behind a request; without them, googlevideo streaming URLs are heavily throttled. The
     * provider is registered here, once per app process, before any component (the server
     * service, the web UI, downloads, ...) touches the extractor.
     * </p>
     */
    private void registerPoTokenProvider() {
        try {
            YoutubeStreamExtractor.setPoTokenProvider(
                    YouTubePoTokenProvider.getInstance(this));
            Log.i(TAG, "YouTube poToken provider registered");
        } catch (final Throwable t) {
            // never prevent the app from starting because of poToken setup problems
            Log.w(TAG, "Could not register YouTube poToken provider", t);
        }
    }
}
