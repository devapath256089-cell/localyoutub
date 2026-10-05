package org.schabi.newpipe.localserver;

/**
 * Thrown when a poToken (proof of origin token) cannot be generated.
 *
 * <p>
 * Ported from NewPipe's {@code PoTokenException} (GPL-3.0-or-later).
 * </p>
 */
public class PoTokenException extends RuntimeException {

    public PoTokenException(final String message) {
        super(message);
    }

    public PoTokenException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Builds the most specific exception possible for a JavaScript error message.
     *
     * <p>
     * {@code SyntaxError}s almost always mean that the system WebView is only able to run a
     * really old version of JavaScript, which is not something this app can work around, so a
     * {@link BadWebViewException} is returned in that case.
     * </p>
     *
     * @param error the JavaScript error message
     * @return a {@link BadWebViewException} if the error denotes a broken WebView, otherwise a
     * generic {@link PoTokenException}
     */
    public static PoTokenException forJsError(final String error) {
        if (error != null && error.contains("SyntaxError")) {
            return new BadWebViewException(error);
        }
        return new PoTokenException(error);
    }
}
