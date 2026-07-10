package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private final Redactor redactor = Redactor.withDefaults(List.of());

    @Test
    void masksSecretKeyValuePairsKeepingTheKey() {
        assertThat(redactor.redact("login failed password=hunter2 for bob"))
                .isEqualTo("login failed password=███ for bob");
        assertThat(redactor.redact("apiKey: sk-live-1234567890"))
                .isEqualTo("apiKey: ███");
    }

    @Test
    void masksBearerAndJwt() {
        assertThat(redactor.redact("header Authorization: Bearer abcdef1234567890TOKENVALUE"))
                .doesNotContain("TOKENVALUE");
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9P";
        assertThat(redactor.redact("token was " + jwt)).doesNotContain(jwt).contains("███");
    }

    @Test
    void masksLongHexSecrets() {
        assertThat(redactor.redact("sha af52c1d94ee1e33a8ff2fba4bdbf28a1af52c1d94ee1e33a"))
                .doesNotContain("af52c1d94ee1e33a8ff2fba4bdbf28a1");
    }

    @Test
    void masksEmails() {
        assertThat(redactor.redact("user gabriel@example.com not found"))
                .isEqualTo("user ███ not found");
    }

    @Test
    void masksCardNumbersOnlyWhenLuhnValid() {
        assertThat(redactor.redact("card 4532 0151 1283 0366 declined"))   // Luhn-valid
                .doesNotContain("4532").contains("███");
        assertThat(redactor.redact("trace id 1234 5678 9012 3456 kept"))   // Luhn-invalid
                .contains("1234 5678 9012 3456");
    }

    @Test
    void leavesNormalTextAlone() {
        String s = "order 889 failed with status 502 after 800ms (git 7e3c1f)";
        assertThat(redactor.redact(s)).isEqualTo(s);
    }

    @Test
    void customPatternsApply() {
        Redactor custom = Redactor.withDefaults(List.of(Pattern.compile("BR\\d{2}-\\d{4}")));
        assertThat(custom.redact("internal id BR12-9944 leaked")).isEqualTo("internal id ███ leaked");
    }

    @Test
    void disabledPassesThrough() {
        assertThat(Redactor.disabled().redact("password=hunter2")).isEqualTo("password=hunter2");
    }
}
