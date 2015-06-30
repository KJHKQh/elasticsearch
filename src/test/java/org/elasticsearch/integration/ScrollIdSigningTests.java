/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.shield.crypto.CryptoService;
import org.elasticsearch.shield.crypto.InternalCryptoService;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.junit.Test;

import java.util.Locale;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

@LuceneTestCase.AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch-shield/issues/947")
public class ScrollIdSigningTests extends ShieldIntegrationTest {

    @Test
    public void testSearchAndClearScroll() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(randomIntBetween(1, 10)).get();

        int hits = 0;
        try {
            while (true) {
                assertSigned(response.getScrollId());
                assertHitCount(response, docs.length);
                hits += response.getHits().hits().length;
                response = client().prepareSearchScroll(response.getScrollId())
                        .setScroll(TimeValue.timeValueMinutes(2)).get();
                if (response.getHits().getHits().length == 0) {
                    break;
                }
            }
            assertThat(hits, equalTo(docs.length));
        } finally {
            clearScroll(response.getScrollId());
        }
    }

    @Test
    public void testSearchScroll_WithTamperedScrollId() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(randomIntBetween(1, 10)).get();
        String scrollId = response.getScrollId();
        String tamperedScrollId = randomBoolean() ? scrollId.substring(randomIntBetween(1, 10)) : scrollId + randomAsciiOfLength(randomIntBetween(3, 10));
        try {
            client().prepareSearchScroll(tamperedScrollId).setScroll(TimeValue.timeValueMinutes(2)).get();
            fail("Expected an authorization exception to be thrown when scroll id is tampered");
        } catch (Exception e) {
            assertThat(ExceptionsHelper.unwrap(e, AuthorizationException.class), notNullValue());
        } finally {
            clearScroll(scrollId);
        }
    }

    @Test
    public void testClearScroll_WithTamperedScrollId() throws Exception {
        IndexRequestBuilder[] docs = new IndexRequestBuilder[randomIntBetween(20, 100)];
        for (int i = 0; i < docs.length; i++) {
            docs[i] = client().prepareIndex("idx", "type").setSource("field", "value");
        }
        indexRandom(true, docs);
        SearchResponse response = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setScroll(TimeValue.timeValueMinutes(2))
                .setSize(5).get();
        String scrollId = response.getScrollId();
        String tamperedScrollId = randomBoolean() ? scrollId.substring(randomIntBetween(1, 10)) : scrollId + randomAsciiOfLength(randomIntBetween(3, 10));
        try {
            client().prepareClearScroll().addScrollId(tamperedScrollId).get();
            fail("Expected an authorization exception to be thrown when scroll id is tampered");
        } catch (Exception e) {
            assertThat(ExceptionsHelper.unwrap(e, AuthorizationException.class), notNullValue());
        } finally {
            clearScroll(scrollId);
        }
    }

    private void assertSigned(String scrollId) {
        CryptoService cryptoService = internalTestCluster().getDataNodeInstance(InternalCryptoService.class);
        String message = String.format(Locale.ROOT, "Expected scrollId [%s] to be signed, but was not", scrollId);
        assertThat(message, cryptoService.signed(scrollId), is(true));
    }
}
