package org.schabi.newpipe.localserver;

/**
 * Thrown when the WebView implementation provided by the system is not able to run the BotGuard
 * code at all (e.g. it is too old or broken).
 *
 * <p>
 * When this is received, poToken generation should be disabled for the rest of the app process,
 * since retrying will not help.
 * </p>
 *
 * <p>
 * Ported from NewPipe's {@code BadWebViewException} (GPL-3.0-or-later).
 * </p>
 */
public class BadWebViewException extends PoTokenException {

    public BadWebViewException(final String message) {
        super(message);
    }
}
