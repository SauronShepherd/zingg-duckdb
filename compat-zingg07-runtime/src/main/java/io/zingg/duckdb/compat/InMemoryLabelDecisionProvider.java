package io.zingg.duckdb.compat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded reference provider for local/offline orchestration and adapters. */
public final class InMemoryLabelDecisionProvider implements LabelDecisionProvider {
  private final Queue<PendingLabel> pending = new ArrayDeque<>();
  private final Map<String, ApplyLabelsResult> applied = new ConcurrentHashMap<>();
  private final Map<String, Decision> decisions = new ConcurrentHashMap<>();

  public synchronized void enqueue(PendingLabel label) { pending.add(Objects.requireNonNull(label)); }

  @Override public synchronized LabelBatch getPendingLabels(LabelRequest request) {
    List<PendingLabel> result = new ArrayList<>(Math.min(request.limit(), pending.size()));
    while (result.size() < request.limit() && !pending.isEmpty()) result.add(pending.remove());
    return new LabelBatch(request.requestId(), request.schemaVersion(), result, !pending.isEmpty());
  }

  @Override public ApplyLabelsResult applyLabels(ApplyLabelsRequest request) {
    ApplyLabelsResult previous = applied.get(request.idempotencyKey());
    if (previous != null) return new ApplyLabelsResult(request.requestId(), request.idempotencyKey(), previous.applied(), previous.rejections(), true);
    int accepted = 0; List<LabelRejection> rejected = new ArrayList<>();
    for (LabelDecision decision : request.decisions()) {
      String key = decision.leftId() + "\u0000" + decision.rightId();
      Decision old = decisions.putIfAbsent(key, decision.decision());
      if (old == null || old == decision.decision()) accepted++;
      else rejected.add(new LabelRejection(decision.leftId(), decision.rightId(), "CONFLICT", "pair already has a different decision"));
    }
    ApplyLabelsResult result = new ApplyLabelsResult(request.requestId(), request.idempotencyKey(), accepted, rejected, false);
    ApplyLabelsResult raced = applied.putIfAbsent(request.idempotencyKey(), result);
    return raced == null ? result : new ApplyLabelsResult(request.requestId(), request.idempotencyKey(), raced.applied(), raced.rejections(), true);
  }
}
