package org.schabi.newpipe.localserver;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Helpers to parse YouTube BotGuard challenge data and convert between the byte/base64
 * representations used by the poToken algorithm.
 *
 * <p>
 * Ported from NewPipe's {@code JavaScriptUtil.kt} (GPL-3.0-or-later), using the built-in
 * {@code org.json} instead of nanojson and {@link Base64} instead of okio.
 * </p>
 */
final class JavaScriptUtil {

    private JavaScriptUtil() {
    }

    /**
     * Parses the raw challenge data obtained from the {@code Create} endpoint and returns a JSON
     * object that can be embedded directly in JavaScript code.
     */
    static String parseChallengeData(final String rawChallengeData) throws Exception {
        final JSONArray scrambled = new JSONArray(rawChallengeData);

        final JSONArray challengeData;
        if (scrambled.length() > 1 && !scrambled.isNull(1) && scrambled.get(1) instanceof String) {
            challengeData = new JSONArray(descramble(scrambled.getString(1)));
        } else {
            challengeData = scrambled.getJSONArray(0);
        }

        final String messageId = challengeData.getString(0);
        final String interpreterHash = challengeData.getString(3);
        final String program = challengeData.getString(4);
        final String globalName = challengeData.getString(5);
        final String clientExperimentsStateBlob = challengeData.getString(7);

        final String privateDoNotAccessOrElseSafeScriptWrappedValue =
                firstStringInArrayOrNull(challengeData, 1);
        final String privateDoNotAccessOrElseTrustedResourceUrlWrappedValue =
                firstStringInArrayOrNull(challengeData, 2);

        final JSONObject interpreterJavascript = new JSONObject();
        interpreterJavascript.put("privateDoNotAccessOrElseSafeScriptWrappedValue",
                privateDoNotAccessOrElseSafeScriptWrappedValue == null
                        ? JSONObject.NULL
                        : privateDoNotAccessOrElseSafeScriptWrappedValue);
        interpreterJavascript.put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                privateDoNotAccessOrElseTrustedResourceUrlWrappedValue == null
                        ? JSONObject.NULL
                        : privateDoNotAccessOrElseTrustedResourceUrlWrappedValue);

        final JSONObject result = new JSONObject();
        result.put("messageId", messageId);
        result.put("interpreterJavascript", interpreterJavascript);
        result.put("interpreterHash", interpreterHash);
        result.put("program", program);
        result.put("globalName", globalName);
        result.put("clientExperimentsStateBlob", clientExperimentsStateBlob);
        return result.toString();
    }

    /**
     * Parses the raw integrity token data obtained from the {@code GenerateIT} endpoint.
     *
     * @return a two-element array containing a JavaScript {@code Uint8Array} literal of the
     * integrity token at index 0, and the token duration in seconds (as a string) at index 1
     */
    static String[] parseIntegrityTokenData(final String rawIntegrityTokenData) throws Exception {
        final JSONArray integrityTokenData = new JSONArray(rawIntegrityTokenData);
        return new String[]{
                base64ToU8(integrityTokenData.getString(0)),
                String.valueOf(integrityTokenData.getLong(1))
        };
    }

    /**
     * Converts a string (usually the identifier used as input to {@code obtainPoToken}) to a
     * JavaScript {@code Uint8Array} that can be embedded directly in JavaScript code.
     */
    static String stringToU8(final String identifier) {
        return newUint8Array(identifier.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
     * (e.g. {@code "97,98,99"} would be {@code "abc"}), which is the output of
     * {@code Uint8Array::toString()} in JavaScript, and converts it to the specific base64
     * representation used for poTokens (URL-safe alphabet, with padding).
     */
    static String u8ToBase64(final String poTokenU8) {
        final String[] parts = poTokenU8.split(",");
        final byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i].trim());
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    /**
     * Takes the scrambled challenge, decodes it from base64 and adds 97 to each byte.
     */
    private static String descramble(final String scrambledChallenge) throws Exception {
        final byte[] decoded = base64ToByteString(scrambledChallenge);
        final byte[] result = new byte[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            result[i] = (byte) (decoded[i] + 97);
        }
        return new String(result, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
     * returns a JavaScript {@code Uint8Array} that can be embedded directly in JavaScript code.
     */
    private static String base64ToU8(final String base64) throws Exception {
        return newUint8Array(base64ToByteString(base64));
    }

    private static String newUint8Array(final byte[] contents) {
        final StringBuilder builder = new StringBuilder("new Uint8Array([");
        for (int i = 0; i < contents.length; i++) {
            if (i != 0) {
                builder.append(',');
            }
            builder.append(contents[i] & 0xFF);
        }
        return builder.append("])").toString();
    }

    /**
     * Decodes a base64 string encoded in the specific base64 representation used by YouTube
     * (which may use {@code -}, {@code _} and {@code .} instead of {@code +}, {@code /} and
     * {@code =}).
     */
    private static byte[] base64ToByteString(final String base64) {
        String base64Mod = base64
                .replace('-', '+')
                .replace('_', '/')
                .replace('.', '=')
                .trim();
        // make sure the length is a multiple of 4, since android.util.Base64 is stricter than
        // the decoder used by NewPipe
        final int remainder = base64Mod.length() % 4;
        if (remainder != 0) {
            final StringBuilder padded = new StringBuilder(base64Mod);
            for (int i = 0; i < 4 - remainder; i++) {
                padded.append('=');
            }
            base64Mod = padded.toString();
        }

        try {
            return Base64.decode(base64Mod, Base64.DEFAULT);
        } catch (final IllegalArgumentException e) {
            throw new PoTokenException("Cannot base64 decode", e);
        }
    }

    /**
     * @return the first {@link String} contained in the array stored at the given index of the
     * provided {@link JSONArray}, or {@code null} if there is no array at this index or if the
     * array contains no strings
     */
    private static String firstStringInArrayOrNull(final JSONArray array, final int index) {
        try {
            if (array.isNull(index)) {
                return null;
            }
            final Object value = array.get(index);
            if (!(value instanceof JSONArray)) {
                return null;
            }
            final JSONArray innerArray = (JSONArray) value;
            for (int i = 0; i < innerArray.length(); i++) {
                final Object item = innerArray.get(i);
                if (item instanceof String) {
                    return (String) item;
                }
            }
            return null;
        } catch (final Exception e) {
            return null;
        }
    }
}
