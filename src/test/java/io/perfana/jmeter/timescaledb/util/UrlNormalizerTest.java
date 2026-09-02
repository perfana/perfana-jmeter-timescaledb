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
}
