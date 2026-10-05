package org.schabi.newpipe.localserver;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A poToken generator backed by a headless {@link WebView} running YouTube's BotGuard machine.
 *
 * <p>
 * The WebView loads a small local HTML page containing the BotGuard glue code, then:
 * </p>
 * <ol>
 *     <li>requests a challenge from {@code https://www.youtube.com/api/jnn/v1/Create};</li>
 *     <li>runs the downloaded BotGuard VM program inside the WebView;</li>
 *     <li>exchanges the VM result for an {@code integrityToken} via
 *     {@code https://www.youtube.com/api/jnn/v1/GenerateIT};</li>
 *     <li>mints poTokens locally with {@code obtainPoToken()} for any requested identifier.</li>
 * </ol>
 *
 * <p>
 * The WebView itself never performs network requests ({@code blockNetworkLoads} is enabled); all
 * BotGuard service requests are performed with OkHttp on background threads.
 * </p>
 *
 * <p>
 * Ported from NewPipe's {@code PoTokenWebView.kt} (GPL-3.0-or-later), replacing RxJava with
 * {@link CompletableFuture}.
 * </p>
 */
public class PoTokenWebView implements PoTokenGenerator {

    private static final String TAG = "PoTokenWebView";

    // Public API key used by BotGuard, which has been obtained by looking at BotGuard requests
    private static final String GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
    private static final String REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3";
    private static final String JS_INTERFACE = "PoTokenWebView";

    private static final long INIT_TIMEOUT_SECONDS = 45;
    private static final long TOKEN_TIMEOUT_SECONDS = 20;

    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    /** poToken requests waiting for the WebView to answer, keyed by identifier in order. */
    private final List<PendingRequest> pendingRequests = new ArrayList<>();

    /** The future completed when the generator is ready to mint poTokens. */
    private CompletableFuture<PoTokenGenerator> initFuture;

    /** When the integrity token expires (10 minutes of margin are already subtracted). */
    private volatile Instant expirationInstant = Instant.EPOCH;

    private static final class PendingRequest {
        final String identifier;
        final CompletableFuture<String> future;

        PendingRequest(final String identifier, final CompletableFuture<String> future) {
            this.identifier = identifier;
            this.future = future;
        }
    }

    //region Initialization

    private PoTokenWebView(final Context context) {
        webView = new WebView(context);
        // we want to use JavaScript!
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setUserAgentString(USER_AGENT);
        // the WebView does not need internet access, everything goes through the OkHttp client
        webView.getSettings().setBlockNetworkLoads(true);

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(final ConsoleMessage message) {
                if (message.message() != null && message.message().contains("Uncaught")) {
                    // There should not be any uncaught errors while executing the code, because
                    // everything that can fail is guarded by try-catch. Therefore, this likely
                    // indicates that there was a syntax error in the code, i.e. the WebView only
                    // supports a really old version of JS.
                    final String fmt = "\"" + message.message() + "\", source: "
                            + message.sourceId() + " (" + message.lineNumber() + ")";
                    Log.e(TAG, "This WebView implementation is broken: " + fmt);
                    final BadWebViewException exception = new BadWebViewException(fmt);
                    failInitialization(exception);
                    failAllPendingRequests(exception);
                }
                return super.onConsoleMessage(message);
            }
        });
    }

    /**
     * Creates and initializes a new poToken generator: loads the BotGuard VM into a headless
     * WebView and obtains an {@code integrityToken}. Can be called from any thread.
     *
     * @param context any context; the application context will be used
     * @return a future completed with the ready-to-use generator, or completed exceptionally with
     * a {@link PoTokenException} (a {@link BadWebViewException} means the system WebView is too
     * broken to be used at all)
     */
    static CompletableFuture<PoTokenGenerator> newPoTokenGenerator(final Context context) {
        final CompletableFuture<PoTokenGenerator> future = new CompletableFuture<>();

        final String html;
        try {
            html = readPoTokenHtml(context.getApplicationContext());
        } catch (final Exception e) {
            future.completeExceptionally(new PoTokenException("Could not read po_token.html", e));
            return future;
        }

        postToMain(future, () -> {
            try {
                final PoTokenWebView poTokenWebView = new PoTokenWebView(context);
                poTokenWebView.initFuture = future;
                poTokenWebView.loadHtmlAndObtainBotguard(html);
            } catch (final Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static String readPoTokenHtml(final Context context) throws Exception {
        try (java.io.InputStream inputStream = context.getAssets().open("po_token.html")) {
            java.util.Scanner scanner = new java.util.Scanner(inputStream,
                    StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    /**
     * Loads the local BotGuard HTML page into the WebView. When the page has finished loading,
     * the JavaScript snippet appended to the HTML content calls
     * {@link #downloadAndRunBotguard()}.
     *
     * <p>Must be called on the main thread.</p>
     */
    private void loadHtmlAndObtainBotguard(final String html) {
        final String htmlWithCall = html.replaceFirst(
                "</script>",
                java.util.regex.Matcher.quoteReplacement(
                        "\n" + JS_INTERFACE + ".downloadAndRunBotguard()</script>"));
        webView.loadDataWithBaseURL("https://www.youtube.com", htmlWithCall,
                "text/html", "utf-8", null);
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * {@link #loadHtmlAndObtainBotguard(String)} after the WebView content has been loaded.
     */
    @JavascriptInterface
    public void downloadAndRunBotguard() {
        try {
            final String responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ " + jsStringLiteral(REQUEST_KEY) + " ]");
            final String parsedChallengeData;
            try {
                parsedChallengeData = JavaScriptUtil.parseChallengeData(responseBody);
            } catch (final JSONException e) {
                throw new PoTokenException("Could not parse challenge data", e);
            }

            runOnMainThreadUnchecked(() -> {
                try {
                    final String js = "try {\n"
                            + "data = " + parsedChallengeData + "\n"
                            + "runBotGuard(data).then(function (result) {\n"
                            + "    this.webPoSignalOutput = result.webPoSignalOutput\n"
                            + "    " + JS_INTERFACE + ".onRunBotguardResult(result.botguardResponse)\n"
                            + "}, function (error) {\n"
                            + "    " + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\""
                            + " + error.stack)\n"
                            + "})\n"
                            + "} catch (error) {\n"
                            + "    " + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\""
                            + " + error.stack)\n"
                            + "}";
                    webView.evaluateJavascript(js, null);
                } catch (final Exception e) {
                    failInitialization(e);
                }
            });
        } catch (final Exception e) {
            failInitialization(e);
        }
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * {@link #downloadAndRunBotguard()} or {@link #onRunBotguardResult(String)}.
     */
    @JavascriptInterface
    public void onJsInitializationError(final String error) {
        Log.e(TAG, "Initialization error from JavaScript: " + error);
        failInitialization(PoTokenException.forJsError(error));
    }

    /**
     * Called during initialization by the JavaScript snippet from {@link
     * #downloadAndRunBotguard()} after obtaining the BotGuard execution output.
     */
    @JavascriptInterface
    public void onRunBotguardResult(final String botguardResponse) {
        try {
            final String responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ " + jsStringLiteral(REQUEST_KEY) + ", "
                            + jsStringLiteral(botguardResponse) + " ]");
            final String[] integrityTokenData;
            try {
                integrityTokenData = JavaScriptUtil.parseIntegrityTokenData(responseBody);
            } catch (final JSONException e) {
                throw new PoTokenException("Could not parse integrity token data", e);
            }
            final long expirationTimeInSeconds = Long.parseLong(integrityTokenData[1]);

            // leave 10 minutes of margin just to be sure
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600);

            runOnMainThreadUnchecked(() -> {
                try {
                    webView.evaluateJavascript("this.integrityToken = " + integrityTokenData[0],
                            result -> {
                                Log.i(TAG, "poToken generator initialized, token expires in "
                                        + expirationTimeInSeconds + "s");
                                if (initFuture != null) {
                                    initFuture.complete(this);
                                }
                            });
                } catch (final Exception e) {
                    failInitialization(e);
                }
            });
        } catch (final Exception e) {
            failInitialization(e);
        }
    }
    //endregion

    //region Obtaining poTokens

    @Override
    public CompletableFuture<String> generatePoToken(final String identifier) {
        final CompletableFuture<String> future = new CompletableFuture<>();
        final PendingRequest pendingRequest = new PendingRequest(identifier, future);
        synchronized (pendingRequests) {
            pendingRequests.add(pendingRequest);
        }

        try {
            postToMain(future, () -> {
                try {
                    if (expirationInstant.equals(Instant.EPOCH)) {
                        throw new PoTokenException("poToken generator is not initialized yet");
                    }
                    final String u8Identifier = JavaScriptUtil.stringToU8(identifier);
                    final String js = "try {\n"
                            + "identifier = " + jsStringLiteral(identifier) + "\n"
                            + "u8Identifier = " + u8Identifier + "\n"
                            + "poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken,"
                            + " u8Identifier)\n"
                            + "poTokenU8String = \"\"\n"
                            + "for (i = 0; i < poTokenU8.length; i++) {\n"
                            + "    if (i != 0) poTokenU8String += \",\"\n"
                            + "    poTokenU8String += poTokenU8[i]\n"
                            + "}\n"
                            + JS_INTERFACE + ".onObtainPoTokenResult(identifier, poTokenU8String)\n"
                            + "} catch (error) {\n"
                            + "    " + JS_INTERFACE + ".onObtainPoTokenError(identifier,"
                            + " error + \"\\n\" + error.stack)\n"
                            + "}";
                    webView.evaluateJavascript(js, null);
                } catch (final Exception e) {
                    removePendingRequest(pendingRequest);
                    future.completeExceptionally(e);
                }
            });
        } catch (final Exception e) {
            removePendingRequest(pendingRequest);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Called by the JavaScript snippet from {@link #generatePoToken(String)} when an error occurs
     * in calling the JavaScript {@code obtainPoToken()} function.
     */
    @JavascriptInterface
    public void onObtainPoTokenError(final String identifier, final String error) {
        Log.e(TAG, "obtainPoToken error from JavaScript: " + error);
        final PendingRequest request = popPoTokenRequest(identifier);
        if (request != null) {
            request.future.completeExceptionally(PoTokenException.forJsError(error));
        }
    }

    /**
     * Called by the JavaScript snippet from {@link #generatePoToken(String)} with the original
     * identifier and the result of the JavaScript {@code obtainPoToken()} function.
     */
    @JavascriptInterface
    public void onObtainPoTokenResult(final String identifier, final String poTokenU8) {
        final String poToken;
        try {
            poToken = JavaScriptUtil.u8ToBase64(poTokenU8);
        } catch (final Exception e) {
            final PendingRequest request = popPoTokenRequest(identifier);
            if (request != null) {
                request.future.completeExceptionally(
                        new PoTokenException("Could not decode poToken", e));
            }
            return;
        }

        final PendingRequest request = popPoTokenRequest(identifier);
        if (request != null) {
            request.future.complete(poToken);
        }
    }

    @Override
    public boolean isExpired() {
        return Instant.now().isAfter(expirationInstant);
    }
    //endregion

    //region Internal helpers

    /**
     * Makes a POST request to a BotGuard service endpoint ({@code Create}/{@code GenerateIT})
     * with the correct headers. Blocking; must not be called on the main thread.
     *
     * @throws PoTokenException on network errors or non-200 response codes
     */
    private String makeBotguardServiceRequest(final String url, final String data) {
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json+protobuf")
                .header("x-goog-api-key", GOOGLE_API_KEY)
                .header("x-user-agent", "grpc-web-javascript/0.1")
                .post(RequestBody.create(data.getBytes(StandardCharsets.UTF_8),
                        MediaType.parse("application/json+protobuf")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            final int httpCode = response.code();
            if (httpCode != 200) {
                throw new PoTokenException("Invalid response code: " + httpCode);
            }
            if (response.body() == null) {
                throw new PoTokenException("Empty response body");
            }
            return response.body().string();
        } catch (final java.io.IOException e) {
            throw new PoTokenException("BotGuard service request failed: " + url, e);
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and completing the
     * initialization future exceptionally. Safe to call from any thread.
     */
    private void failInitialization(final Throwable error) {
        postToMainUnchecked(() -> {
            final CompletableFuture<PoTokenGenerator> future = initFuture;
            initFuture = null;
            close();
            if (future != null) {
                future.completeExceptionally(error);
            }
        });
    }

    /**
     * Removes and returns the first pending request matching the given identifier.
     */
    private PendingRequest popPoTokenRequest(final String identifier) {
        synchronized (pendingRequests) {
            for (int i = 0; i < pendingRequests.size(); i++) {
                final PendingRequest request = pendingRequests.get(i);
                if (request.identifier.equals(identifier)) {
                    return pendingRequests.remove(i);
                }
            }
        }
        return null;
    }

    /**
     * Removes the provided pending request from the pending list, if still present.
     */
    private void removePendingRequest(final PendingRequest pendingRequest) {
        synchronized (pendingRequests) {
            pendingRequests.remove(pendingRequest);
        }
    }

    private void failAllPendingRequests(final Throwable error) {
        final List<PendingRequest> requests;
        synchronized (pendingRequests) {
            requests = new ArrayList<>(pendingRequests);
            pendingRequests.clear();
        }
        for (final PendingRequest request : requests) {
            request.future.completeExceptionally(error);
        }
    }

    private static String jsStringLiteral(final String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }

    /**
     * Runs the provided runnable on the main thread, completing the provided future
     * exceptionally if the post fails (e.g. the looper is shutting down).
     */
    private static void postToMain(final CompletableFuture<?> futureIfPostFails,
                                   final Runnable runnable) {
        final Handler handler = new Handler(Looper.getMainLooper());
        if (!handler.post(runnable)) {
            futureIfPostFails.completeExceptionally(
                    new PoTokenException("Could not run on main thread"));
        }
    }

    private static void postToMainUnchecked(final Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    private void runOnMainThreadUnchecked(final Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            postToMainUnchecked(runnable);
        }
    }
    //endregion

    //region Cleanup

    /**
     * Releases all WebView resources. Must be called on the main thread (is posted to the main
     * thread automatically when called from another one).
     */
    @Override
    public void close() {
        failAllPendingRequests(new PoTokenException("poToken generator has been closed"));

        final Runnable destroy = () -> {
            webView.clearHistory();
            // clears RAM cache and disk cache (globally for all WebViews)
            webView.clearCache(true);
            // ensures that the WebView isn't doing anything when destroying it
            webView.loadUrl("about:blank");
            webView.onPause();
            webView.removeAllViews();
            webView.destroy();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroy.run();
        } else {
            postToMainUnchecked(destroy);
        }
    }
    //endregion
}
