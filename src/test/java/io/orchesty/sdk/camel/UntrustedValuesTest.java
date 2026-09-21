package io.orchesty.sdk.camel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Install and payload values must not make Camel resolve or evaluate anything. */
class UntrustedValuesTest {

    @Test
    void placeholdersExpressionsReferencesAndRawValuesAreRejected() {
        for (String value : new String[] {
            "{{env:CRYPT_SECRET}}",
            "user-{{sys:user.home}}",
            "${env:HOME}",
            "$simple{env:HOME}",
            "RAW(x)&delete=true",
            "RAW{x}",
            "#bean:dataSource",
            "#class:java.io.FileInputStream('/etc/hostname')",
            "#type:java.lang.Object",
            "#property:secret",
            "#valueAs(int):1",
            "#autowired",
            "  #class:java.lang.Object"
        }) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class, () -> UntrustedValues.check("username", value, "test"), value);
            assertTrue(e.getMessage().contains("[username]"), e.getMessage());
            if (!value.equals("#autowired")) { // the only value that is the rejected token itself
                assertFalse(e.getMessage().contains(value.trim()), "the value is not echoed: " + e.getMessage());
            }
        }
    }

    @Test
    void ordinaryValuesAreAccepted() {
        for (Object value : new Object[] {"#secret1", "#general", "p&w)d% +x", "a{b}c", "$5", 42, true, null}) {
            assertDoesNotThrow(() -> UntrustedValues.check("key", value, "test"), String.valueOf(value));
        }
    }

    @Test
    void mapsAndListsAreCheckedElementByElement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> UntrustedValues.check("headers", Map.of("a", List.of("ok", "{{env:X}}")), "test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> UntrustedValues.checkAll(Map.of("host", "h", "password", "#bean:x"), "test"));
    }
}
