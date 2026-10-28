package com.zigorminsk.backupcontrol;

import java.util.List;

/**
 * IPv4 helpers for the checklist form. Deliberately free of Android imports so
 * the behaviour users depend on is covered by plain JVM unit tests.
 */
final class IpAddresses {
    private IpAddresses() { }

    /**
     * Keeps digits and dots and inserts the separators a numeric keyboard cannot
     * type: a dot after a full octet, and a dot before the octet would exceed 255.
     */
    static String formatInput(CharSequence raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(15);
        int octetDigits = 0;
        int dots = 0;
        int octetValue = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '.') {
                if (octetDigits > 0 && dots < 3) {
                    out.append('.');
                    dots++;
                    octetDigits = 0;
                    octetValue = 0;
                }
                continue;
            }
            if (c < '0' || c > '9') continue;
            // A single "0" is already a complete octet: without this rule
            // "19216801" would become the invalid "192.168.01".
            boolean zeroComplete = octetDigits == 1 && octetValue == 0;
            if (octetDigits == 3 || zeroComplete
                    || (octetDigits > 0 && octetValue * 10 + (c - '0') > 255)) {
                // Four octets only: without this guard a fifth number would be
                // glued onto the last octet and silently accepted as valid.
                if (dots == 3) break;
                out.append('.');
                dots++;
                octetDigits = 0;
                octetValue = 0;
            }
            octetValue = octetValue * 10 + (c - '0');
            out.append(c);
            octetDigits++;
        }
        return out.toString();
    }

    /** Strict IPv4: exactly four octets, 0-255, no leading zeros. */
    static boolean isValid(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.isEmpty() || text.length() > 15) return false;
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    static String join(List<String> addresses) {
        StringBuilder text = new StringBuilder();
        for (String address : addresses) {
            if (text.length() > 0) text.append(" · ");
            text.append(address);
        }
        return text.toString();
    }
}
