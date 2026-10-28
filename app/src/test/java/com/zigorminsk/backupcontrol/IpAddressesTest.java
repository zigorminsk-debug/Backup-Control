package com.zigorminsk.backupcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class IpAddressesTest {
    @Test public void keepsACompleteAddressUnchanged() {
        assertEquals("192.168.1.10", IpAddresses.formatInput("192.168.1.10"));
        assertEquals("10.0.0.5", IpAddresses.formatInput("10.0.0.5"));
        assertEquals("255.255.255.255", IpAddresses.formatInput("255.255.255.255"));
    }

    @Test public void insertsDotsAfterAFullOctet() {
        assertEquals("192.168.1", IpAddresses.formatInput("1921681"));
        assertEquals("192.", IpAddresses.formatInput("192."));
    }

    @Test public void aSingleZeroIsACompleteOctet() {
        assertEquals("192.168.0.1", IpAddresses.formatInput("19216801"));
        assertEquals("192.168.0.0", IpAddresses.formatInput("19216800"));
        assertEquals("0.0.0.0", IpAddresses.formatInput("0000"));
        assertEquals("0", IpAddresses.formatInput("0"));
        // Octets fill greedily: 100 is a valid octet, so it is not split.
        assertEquals("100.0.5", IpAddresses.formatInput("10005"));
    }

    @Test public void neverProducesAnOctetAbove255() {
        assertEquals("25.6", IpAddresses.formatInput("256"));
        assertEquals("99.9", IpAddresses.formatInput("999"));
        // Three full octets, then the trailing digits form the last octet.
        assertEquals("192.168.110", IpAddresses.formatInput("192168110"));
    }

    @Test public void stopsAtFourOctets() {
        // A fifth number cannot be appended: the last octet is already full and
        // a dot would create a fifth octet.
        assertEquals("1.2.3.45", IpAddresses.formatInput("1.2.3.4567"));
        assertEquals("1.2.3.45", IpAddresses.formatInput("1.2.3.456"));
    }

    @Test public void dropsEverythingThatIsNotDigitsOrDots() {
        assertEquals("192.168.1.1", IpAddresses.formatInput(" 192.168.1.1 "));
        assertEquals("10.1", IpAddresses.formatInput("10a.1"));
        assertEquals("", IpAddresses.formatInput(""));
        assertEquals("", IpAddresses.formatInput(null));
    }

    @Test public void acceptsOnlyCompleteIpv4Addresses() {
        assertTrue(IpAddresses.isValid("192.168.1.10"));
        assertTrue(IpAddresses.isValid("0.0.0.0"));
        assertTrue(IpAddresses.isValid("255.255.255.255"));
        assertFalse(IpAddresses.isValid("192.168.1"));
        assertFalse(IpAddresses.isValid("192.168.1.256"));
        assertFalse(IpAddresses.isValid("192.168.01.1"));
        assertFalse(IpAddresses.isValid("192.168.1.10.5"));
        assertFalse(IpAddresses.isValid("192.168.1."));
        assertFalse(IpAddresses.isValid(""));
        assertFalse(IpAddresses.isValid(null));
    }

    @Test public void addressIsFormedWhileTypingDigitByDigit() {
        // The dialog formats the field after every keystroke, so only the
        // incremental result matters to the user.
        String typed = "";
        for (char digit : "9999999999".toCharArray()) typed = IpAddresses.formatInput(typed + digit);
        assertEquals("99.99.99.99", typed);
        assertEquals("192.168.0.0", IpAddresses.formatInput("19216800"));
    }

    @Test public void formattingNeverDriftsWhenAppliedAgain() {
        for (String value : new String[]{"0", "8", "192.168.1.10", "25.62.56.252",
                "192.168.110", "99.99.99.99", "255.255.255.255"}) {
            assertEquals(value, IpAddresses.formatInput(IpAddresses.formatInput(value)));
        }
    }

    @Test public void joinsSeveralAddresses() {
        assertEquals("10.0.0.1 · 10.0.0.2",
                IpAddresses.join(Arrays.asList("10.0.0.1", "10.0.0.2")));
        assertEquals("10.0.0.1", IpAddresses.join(Collections.singletonList("10.0.0.1")));
        assertEquals("", IpAddresses.join(Collections.<String>emptyList()));
    }
}
