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

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.ScoreCachingWrappingScorer;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;

/** A {@link Scorer} that filters out documents that have a score that is
 *  lower than a configured constant. */
final class MinScoreScorer extends Scorer {

    private final Scorer in;
    private final float minScore;

    MinScoreScorer(Weight weight, Scorer scorer, float minScore) {
        super(weight);
        if (scorer instanceof ScoreCachingWrappingScorer == false) {
            // when minScore is set, scores might be requested twice: once
            // to verify the match, and once by the collector
            scorer = new ScoreCachingWrappingScorer(scorer);
        }
        this.in = scorer;
        this.minScore = minScore;
    }

    public Scorer getScorer() {
        return in;
    }

    @Override
    public int docID() {
        return in.docID();
    }

    @Override
    public float score() throws IOException {
        return in.score();
    }

    @Override
    public DocIdSetIterator iterator() {
        return TwoPhaseIterator.asDocIdSetIterator(twoPhaseIterator());
    }

    @Override
    public TwoPhaseIterator twoPhaseIterator() {
        TwoPhaseIterator inTwoPhase = in.twoPhaseIterator();
        DocIdSetIterator approximation;
        if (inTwoPhase == null) {
            approximation = in.iterator();
            if (TwoPhaseIterator.unwrap(approximation) != null) {
                inTwoPhase = TwoPhaseIterator.unwrap(approximation);
                approximation = inTwoPhase.approximation();
            }
        } else {
            approximation = inTwoPhase.approximation();
        }
        final TwoPhaseIterator finalTwoPhase = inTwoPhase;
        return new TwoPhaseIterator(approximation) {

            @Override
            public boolean matches() throws IOException {
                // we need to check the two-phase iterator first
                // otherwise calling score() is illegal
                if (finalTwoPhase != null && finalTwoPhase.matches() == false) {
                    return false;
                }
                return in.score() >= minScore;
            }

            @Override
            public float matchCost() {
                return 1000f // random constant for the score computation
                    + (finalTwoPhase == null ? 0 : finalTwoPhase.matchCost());
            }
        };
    }
}
