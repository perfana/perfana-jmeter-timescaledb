package io.perfana.jmeter.timescaledb.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlNormalizerTest {

    private final UrlNormalizer normalizer = new UrlNormalizer();

    @Test
    void masksPercentEncodedQueryParameters() {
        String url = "https://oam-sso.example.nl:443/obrareq.cgi?ecid-context=abc"
                + "&encquery%3d9if%2bvorl%20agentid%3dwg_sonar%20ver%3d1%26cksum%3dbaffabd2";

        assertEquals("https://oam-sso.example.nl:443/obrareq.cgi?ecid-context={val}&encquery={val}",
                normalizer.normalize(url).normalized());
    }

    @Test
    void masksPathIdsAndSortsParameters() {
        String url = "https://host/api/v1/orders/12345/items/550e8400-e29b-41d4-a716-446655440000?b=2&a=1";

        assertEquals("https://host/api/v1/orders/{id}/items/{uuid}?a={val}&b={val}",
                normalizer.normalize(url).normalized());
    }

    @Test
    void masksJsessionidInThePath() {
        // A path parameter, not a query parameter. Per session, so leaving it in gives every
        // single request its own url_pattern row.
        String url = "https://wbs-accept.example.nl/wbs-new/home;jsessionid="
                + "2o84pngkkda0o0hfz9lvpubtsouc52cs3xgviuqiicqijujhpdhj!-230347638!1214812450";

        assertEquals("https://wbs-accept.example.nl/wbs-new/home;jsessionid={val}",
                normalizer.normalize(url).normalized());
    }

    @Test
    void masksIdsThatMixLettersAndDigits() {
        // Siebel-style row ids: not numeric, not a UUID, and far short of the 21 characters the
        // long-token rule wants.
        assertEquals("https://host/api/assignments/{id}/report/job-application",
                normalizer.normalize("https://host/api/assignments/1-q02oj1h/report/job-application")
                        .normalized());

        assertEquals("https://host/api/assignments/{id}",
                normalizer.normalize("https://host/api/assignments/1-ov4i2iw").normalized());
    }

    @Test
    void keepsPathWordsThatCarryADigit() {
        String url = "https://host/api/v2/oauth2/base64/sha256/html5/reports";

        assertEquals(url, normalizer.normalize(url).normalized());
    }

    @Test
    void neverMasksTheHost() {
        // A farm node name mixes letters and digits, so classifying the authority would collapse
        // every node of the farm into one pattern.
        String url = "https://web3app0110.farm.example.com/portal/api/assignments/1-ov4i2iw";

        assertEquals("https://web3app0110.farm.example.com/portal/api/assignments/{id}",
                normalizer.normalize(url).normalized());
    }

    @Test
    void keepsNumericPathIdsWorkingAsBefore() {
        assertEquals("https://host/api/vacature-relation/{id}/save",
                normalizer.normalize("https://host/api/vacature-relation/58741300/save").normalized());

        assertEquals("https://host/api/contactcandidate/sendwithoutvacature/{id}",
                normalizer.normalize("https://host/api/contactcandidate/sendwithoutvacature/7271586")
                        .normalized());
    }
}
