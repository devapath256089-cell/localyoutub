package org.schabi.newpipe.localserver;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The app's implementation of the extractor's {@link PoTokenProvider} interface, generating web
 * client poTokens with a headless WebView running YouTube's BotGuard machine (see
 * {@link PoTokenWebView}).
 *
 * <p>
 * Ported from NewPipe's {@code PoTokenProviderImpl.kt} (GPL-3.0-or-later). Unlike NewPipe, this
 * implementation never propagates exceptions to the extractor: if a poToken cannot be generated
 * for any reason, {@code null} is returned so that the extractor falls back to clients which do
 * not require poTokens, instead of breaking playback/downloads entirely.
 * </p>
 *
 * <p>
 * Note: the current NewPipeExtractor (upstream {@code eb53b79e6}) sources streaming URLs from
 * the VISIONOS client and does not call {@link #getWebClientPoToken(String)} yet, so this
 * provider is currently dormant. It is kept registered so that the app gets poToken support
 * automatically as soon as the extractor starts consuming web poTokens again (e.g. with upcoming
 * SABR support), at no cost until then.
 * </p>
 */
public final class YouTubePoTokenProvider implements PoTokenProvider {

    private static final String TAG = "YouTubePoTokenProvider";
    private static final long GENERATOR_TIMEOUT_SECONDS = 45;
    private static final long TOKEN_TIMEOUT_SECONDS = 20;

    private static YouTubePoTokenProvider instance;

    private final Context appContext;
    private final boolean webViewSupported;
    private volatile boolean webViewBadImpl;

    private final Object webPoTokenGenLock = new Object();
    private PoTokenGenerator webPoTokenGenerator;
    private String webPoTokenVisitorData;
    private String webPoTokenStreamingPot;

    private YouTubePoTokenProvider(final Context context) {
        appContext = context.getApplicationContext();
        webViewSupported = supportsWebView(appContext);
        if (!webViewSupported) {
            Log.w(TAG, "This device does not seem to support WebView, "
                    + "poToken generation will be disabled");
        }
    }

    /**
     * Returns the singleton instance, creating it on first use.
     */
    public static synchronized YouTubePoTokenProvider getInstance(final Context context) {
        if (instance == null) {
            instance = new YouTubePoTokenProvider(context);
        }
        return instance;
    }

    /**
     * @return whether the system is able to provide a working WebView implementation
     */
    private static boolean supportsWebView(final Context context) {
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
        } catch (final Throwable t) {
            return false;
        }
    }

    @Override
    public PoTokenResult getWebClientPoToken(final String videoId) {
        if (!webViewSupported || webViewBadImpl) {
            return null;
        }

        try {
            return getWebClientPoToken(videoId, false);
        } catch (final BadWebViewException e) {
            Log.e(TAG, "Could not obtain poToken because the system WebView is broken; "
                    + "disabling poToken generation for this session", e);
            webViewBadImpl = true;
            return null;
        } catch (final Exception e) {
            // Never break extraction because poToken generation failed: the extractor will
            // simply continue without poTokens, like it does when no provider is set.
            Log.e(TAG, "Could not obtain web poToken, continuing without it", e);
            return null;
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of {@link #webPoTokenGenerator}, to be
     *                      used in case the current generator threw an error last time
     *                      {@link PoTokenGenerator#generatePoToken(String)} was called
     */
    private PoTokenResult getWebClientPoToken(final String videoId, final boolean forceRecreate)
            throws IOException, ExtractionException {
        final PoTokenGenerator poTokenGenerator;
        final String visitorData;
        final String streamingPot;
        final boolean hasBeenRecreated;

        synchronized (webPoTokenGenLock) {
            final boolean shouldRecreate = webPoTokenGenerator == null || forceRecreate
                    || webPoTokenGenerator.isExpired();

            if (shouldRecreate) {
                final InnertubeClientRequestInfo innertubeClientRequestInfo =
                        InnertubeClientRequestInfo.ofWebClient();
                innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion();

                webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false);

                // close the current generator on the main thread
                final PoTokenGenerator oldGenerator = webPoTokenGenerator;
                if (oldGenerator != null) {
                    new Handler(Looper.getMainLooper()).post(oldGenerator::close);
                }

                // create a new generator (WebView + BotGuard integrity token)
                final CompletableFuture<PoTokenGenerator> generatorFuture =
                        PoTokenWebView.newPoTokenGenerator(appContext);
                webPoTokenGenerator = getGeneratorOrThrow(generatorFuture,
                        GENERATOR_TIMEOUT_SECONDS, "could not create poToken generator");

                // The streaming poToken needs to be generated exactly once before generating
                // any other (player) tokens.
                webPoTokenStreamingPot = getOrThrow(
                        webPoTokenGenerator.generatePoToken(webPoTokenVisitorData),
                        TOKEN_TIMEOUT_SECONDS, "could not generate streaming poToken");
            }

            poTokenGenerator = webPoTokenGenerator;
            visitorData = webPoTokenVisitorData;
            streamingPot = webPoTokenStreamingPot;
            hasBeenRecreated = shouldRecreate;
        }

        final String playerPot;
        try {
            // Not synchronizing here, since the generator is able to generate multiple poTokens
            // in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.
            playerPot = getOrThrow(poTokenGenerator.generatePoToken(videoId),
                    TOKEN_TIMEOUT_SECONDS, "could not generate player poToken");
        } catch (final Exception e) {
            if (hasBeenRecreated) {
                // the generator has just been recreated (and possibly this is already the second
                // time we try), so there is likely nothing we can do
                throw e;
            }
            // retry, this time recreating the generator from scratch; this might happen for
            // example if the app goes to the background and the WebView content is lost
            Log.e(TAG, "Failed to obtain poToken, retrying with a new generator", e);
            return getWebClientPoToken(videoId, true);
        }

        return new PoTokenResult(visitorData, playerPot, streamingPot);
    }

    /**
     * Waits for a {@link CompletableFuture} to complete, converting timeouts and failures into
     * {@link PoTokenException}s.
     */
    private static String getOrThrow(final CompletableFuture<String> future,
                                     final long timeoutSeconds,
                                     final String errorMessage) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            throw new PoTokenException(errorMessage + ": timed out after "
                    + timeoutSeconds + "s", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PoTokenException(errorMessage + ": " + cause.getMessage(), cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoTokenException(errorMessage + ": interrupted", e);
        }
    }

    private static PoTokenGenerator getGeneratorOrThrow(
            final CompletableFuture<PoTokenGenerator> future,
            final long timeoutSeconds,
            final String errorMessage) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            throw new PoTokenException(errorMessage + ": timed out after "
                    + timeoutSeconds + "s", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PoTokenException(errorMessage + ": " + cause.getMessage(), cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PoTokenException(errorMessage + ": interrupted", e);
        }
    }
}
