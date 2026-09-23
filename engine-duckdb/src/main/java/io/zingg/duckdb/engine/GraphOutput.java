package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.CompatibilityClock;
import java.util.*;

/** In-memory output shaping for the released graph contract; SQL integration is supplied by the orchestrator. */
public final class GraphOutput {
  public record Edge(long left,long right,double score) { public Edge { if(left==right||!Double.isFinite(score)) throw new IllegalArgumentException("invalid graph edge"); } }
  public record EntityScore(long id,double min,double max,String cluster) {}
  private GraphOutput() {}
  /** Returns the undirected component closure, adding zero-score implied pairs. */
  public static List<Edge> transitiveEdges(Collection<Edge> edges) {
    if (edges == null) throw new IllegalArgumentException("edges are required");
    Map<Long,Set<Long>> graph = new HashMap<>();
    Map<String,Double> direct = new HashMap<>();
    for (Edge e : edges) {
      if (e == null || e.left() == e.right() || !Double.isFinite(e.score())) throw new IllegalArgumentException("invalid graph edge");
      graph.computeIfAbsent(e.left(), k -> new TreeSet<>()).add(e.right());
      graph.computeIfAbsent(e.right(), k -> new TreeSet<>()).add(e.left());
      direct.merge(pairKey(e.left(), e.right()), e.score(), Math::max);
    }
    List<Edge> result = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (long start : new TreeSet<>(graph.keySet())) if (seen.add(start)) {
      TreeSet<Long> component = new TreeSet<>();
      Deque<Long> queue = new ArrayDeque<>(); queue.add(start);
      while (!queue.isEmpty()) { long id = queue.remove(); component.add(id); for (long n : graph.getOrDefault(id, Set.of())) if (seen.add(n)) queue.add(n); }
      List<Long> ids = new ArrayList<>(component);
      for (int i = 0; i < ids.size(); i++) for (int j = i + 1; j < ids.size(); j++) {
        long left = ids.get(i), right = ids.get(j);
        result.add(new Edge(left, right, direct.getOrDefault(pairKey(left, right), 0d)));
      }
    }
    return List.copyOf(result);
  }
  public static List<EntityScore> entityScores(Collection<Edge> edges, CompatibilityClock clock) {
    if (edges == null || clock == null) throw new IllegalArgumentException("edges and clock are required");
    edges = transitiveEdges(edges);
    Map<Long,Set<Long>> graph=new HashMap<>(); Map<String,Double> pairScores=new HashMap<>();
    for (Edge e:edges) {
      if (e == null || e.left() == e.right() || !Double.isFinite(e.score())) throw new IllegalArgumentException("invalid graph edge");
      graph.computeIfAbsent(e.left(),k->new HashSet<>()).add(e.right()); graph.computeIfAbsent(e.right(),k->new HashSet<>()).add(e.left()); pairScores.merge(pairKey(e.left(),e.right()),e.score(),Math::max);
    }
    List<EntityScore> out=new ArrayList<>(); Set<Long> seen=new HashSet<>(); long stamp=clock.epochMillis();
    for(long start:new TreeSet<>(graph.keySet())) if(seen.add(start)){ Set<Long> component=new TreeSet<>(); Deque<Long> q=new ArrayDeque<>(); q.add(start); while(!q.isEmpty()){long x=q.remove();component.add(x);for(long n:new TreeSet<>(graph.getOrDefault(x,Set.of())))if(seen.add(n))q.add(n);} for(long id:component){double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY; for(long other:component)if(other!=id){double score=pairScores.getOrDefault(pairKey(id,other),0d);min=Math.min(min,score);max=Math.max(max,score);} if(min==Double.POSITIVE_INFINITY)min=max=0d; out.add(new EntityScore(id,min,max,stamp+":"+component.iterator().next()));}}
    return out;
  }
  private static String pairKey(long a,long b){return Math.min(a,b)+":"+Math.max(a,b);}
}
