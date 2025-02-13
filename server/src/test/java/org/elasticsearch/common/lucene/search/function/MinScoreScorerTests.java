/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.lucene.search.function;


import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.AssertingScorer;
import org.apache.lucene.search.ConjunctionDISI;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;

public class MinScoreScorerTests extends ESTestCase {

    private static DocIdSetIterator iterator(final int... docs) {
        return new DocIdSetIterator() {

            int i = -1;

            @Override
            public int nextDoc() throws IOException {
                ++i;
                return docID();
            }

            @Override
            public int docID() {
                return i < 0 ? -1 : i == docs.length ? NO_MORE_DOCS : docs[i];
            }

            @Override
            public long cost() {
                return docs.length;
            }

            @Override
            public int advance(int target) throws IOException {
                return slowAdvance(target);
            }
        };
    }

    private static Weight fakeWeight() {
        return new Weight(new MatchAllDocsQuery()) {
            @Override
            public void extractTerms(Set<Term> terms) {

            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                return null;
            }

            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                return null;
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false;
            }
        };
    }

    private static Scorer hideTwoPhaseIterator(Scorer in) {
        return new Scorer(in.getWeight()) {
            @Override
            public DocIdSetIterator iterator() {
                return TwoPhaseIterator.asDocIdSetIterator(in.twoPhaseIterator());
            }

            @Override
            public TwoPhaseIterator twoPhaseIterator() {
                return null;
            }

            @Override
            public float score() throws IOException {
                return in.score();
            }

            @Override
            public int docID() {
                return in.docID();
            }
        };
    }

    private static Scorer scorer(int maxDoc, final int[] docs, final float[] scores, final boolean twoPhase) {
        final DocIdSetIterator iterator = twoPhase ? DocIdSetIterator.all(maxDoc) : iterator(docs);
        final Scorer scorer = new Scorer(fakeWeight()) {

            final float matchCost = (random().nextBoolean() ? 1000 : 0) + random().nextInt(2000);

            public DocIdSetIterator iterator() {
                if (twoPhase) {
                    return TwoPhaseIterator.asDocIdSetIterator(twoPhaseIterator());
                } else {
                    return iterator;
                }
            }

            public TwoPhaseIterator twoPhaseIterator() {
                if (twoPhase) {
                    return new TwoPhaseIterator(iterator) {

                        @Override
                        public boolean matches() throws IOException {
                            return Arrays.binarySearch(docs, iterator.docID()) >= 0;
                        }

                        @Override
                        public float matchCost() {
                            return matchCost;
                        }
                    };
                } else {
                    return null;
                }
            }

            @Override
            public int docID() {
                return iterator.docID();
            }

            @Override
            public float score() throws IOException {
                final int idx = Arrays.binarySearch(docs, docID());
                return scores[idx];
            }
        };
        final Scorer assertingScorer = AssertingScorer.wrap(random(), scorer, true);
        if (twoPhase && randomBoolean()) {
            return hideTwoPhaseIterator(assertingScorer);
        } else {
            return assertingScorer;
        }
    }

    private static int[] randomDocs(int maxDoc, int numDocs) {
        final List<Integer> docs = randomSubsetOf(numDocs, IntStream.range(0, maxDoc).boxed().collect(Collectors.toList()));
        return docs.stream().mapToInt(n -> n).sorted().toArray();
    }

    public void doTestRandom(boolean twoPhase) throws IOException {
        final int maxDoc = TestUtil.nextInt(random(), 10, 10000);
        final int numDocs = TestUtil.nextInt(random(), 1, maxDoc);
        final Set<Integer> uniqueDocs = new HashSet<>();
        while (uniqueDocs.size() < numDocs) {
            uniqueDocs.add(random().nextInt(maxDoc));
        }
        final int[] docs = randomDocs(maxDoc, numDocs);
        final float[] scores = new float[numDocs];
        for (int i = 0; i < numDocs; ++i) {
            scores[i] = random().nextFloat();
        }
        Scorer scorer = scorer(maxDoc, docs, scores, twoPhase);
        final float minScore = random().nextFloat();
        Scorer minScoreScorer = new MinScoreScorer(null, scorer, minScore);
        int doc = -1;
        while (doc != DocIdSetIterator.NO_MORE_DOCS) {
            final int target;
            if (random().nextBoolean()) {
                target = doc + 1;
                doc = minScoreScorer.iterator().nextDoc();
            } else {
                target = doc + TestUtil.nextInt(random(), 1, 10);
                doc = minScoreScorer.iterator().advance(target);
            }
            int idx = Arrays.binarySearch(docs, target);
            if (idx < 0) {
                idx = -1 - idx;
            }
            while (idx < docs.length && scores[idx] < minScore) {
                idx += 1;
            }
            if (idx == docs.length) {
                assertEquals(DocIdSetIterator.NO_MORE_DOCS, doc);
            } else {
                assertEquals(docs[idx], doc);
                assertEquals(scores[idx], scorer.score(), 0f);
            }
        }
    }

    public void testRegularIterator() throws IOException {
        final int iters = atLeast(5);
        for (int iter = 0; iter < iters; ++iter) {
            doTestRandom(false);
        }
    }

    public void testTwoPhaseIterator() throws IOException {
        final int iters = atLeast(5);
        for (int iter = 0; iter < iters; ++iter) {
            doTestRandom(true);
        }
    }

    public void testConjunction() throws Exception {
        final int maxDoc = randomIntBetween(10, 10000);
        final Map<Integer, Integer> matchedDocs = new HashMap<>();
        final List<Scorer> scorers = new ArrayList<>();
        final int numScorers = randomIntBetween(2, 10);
        for (int s = 0; s < numScorers; s++) {
            final int numDocs = randomIntBetween(2, maxDoc);
            final int[] docs = randomDocs(maxDoc, numDocs);
            final float[] scores = new float[numDocs];
            for (int i = 0; i < numDocs; ++i) {
                scores[i] = randomFloat();
            }
            final boolean useTwoPhase = randomBoolean();
            final Scorer scorer = scorer(maxDoc, docs, scores, useTwoPhase);
            final float minScore;
            if (randomBoolean()) {
                minScore = randomFloat();
                MinScoreScorer minScoreScorer = new MinScoreScorer(scorer.getWeight(), scorer, minScore);
                scorers.add(minScoreScorer);
            } else {
                scorers.add(scorer);
                minScore = 0.0f;
            }
            for (int i = 0; i < numDocs; i++) {
                if (scores[i] >= minScore) {
                    matchedDocs.compute(docs[i], (k, v) -> v == null ? 1 : v + 1);
                }
            }
        }

        final DocIdSetIterator disi = ConjunctionDISI.intersectScorers(scorers);
        final List<Integer> actualDocs = new ArrayList<>();
        while (disi.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            actualDocs.add(disi.docID());
        }
        final List<Integer> expectedDocs = matchedDocs.entrySet()
            .stream()
            .filter(v -> v.getValue() == numScorers)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
        assertThat(actualDocs, equalTo(expectedDocs));
    }
}
