package io.zingg.duckdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.zingg.duckdb.api.CompatibilityClock;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphOutputTest {
  private static GraphOutput.Edge edge(long left, long right, double score) {
    return new GraphOutput.Edge(left, right, score);
  }

  @Test
  void chainAddsImpliedZeroScorePair() {
    var result = GraphOutput.transitiveEdges(List.of(edge(1, 2, .8), edge(2, 3, .7)));
    assertEquals(List.of(edge(1, 2, .8), edge(1, 3, 0), edge(2, 3, .7)), result);
  }

  @Test
  void disconnectedComponentsRemainSeparate() {
    var result = GraphOutput.transitiveEdges(List.of(edge(1, 2, .8), edge(7, 8, .4)));
    assertEquals(2, result.size());
    assertEquals(List.of(edge(1, 2, .8), edge(7, 8, .4)), result);
  }

  @Test
  void duplicateEdgesKeepMaximumScore() {
    var result = GraphOutput.transitiveEdges(List.of(edge(1, 2, .2), edge(2, 1, .9)));
    assertEquals(List.of(edge(1, 2, .9)), result);
  }

  @Test
  void entityScoresUseInjectedClusterTimestamp() {
    var clock = (CompatibilityClock) () -> 1700000000000L;
    var result = GraphOutput.entityScores(List.of(edge(1, 2, .8), edge(2, 3, .7)), clock);
    assertEquals(3, result.size());
    assertEquals("1700000000000:1", result.get(0).cluster());
    assertEquals(0d, result.get(0).min());
    assertEquals(.8d, result.get(0).max());
  }
}
