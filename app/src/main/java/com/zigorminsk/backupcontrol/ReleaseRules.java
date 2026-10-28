package com.zigorminsk.backupcontrol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules shared by release publishing and the updater. No untrusted download host is allowed. */
final class ReleaseRules {
    private static final Pattern RELEASE_TAG = Pattern.compile("^v[0-9]+\\.[0-9]+\\.([0-9]+)$");
    private static final Pattern RELEASE_PATH = Pattern.compile("^/zigorminsk-debug/Backup-Control/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/Backup-Control\\.apk$");
    private ReleaseRules() { }

    static int versionCode(String tag) {
        if (tag == null) return -1;
        Matcher matcher = RELEASE_TAG.matcher(tag);
        if (!matcher.matches()) return -1;
        try {
            int code = Integer.parseInt(matcher.group(1));
            return code > 0 ? code : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean trustedAssetUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath() != null && RELEASE_PATH.matcher(uri.getRawPath()).matches();
        } catch (URISyntaxException | NullPointerException e) {
            return false;
        }
    }
}
