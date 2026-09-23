package io.zingg.duckdb.compat;

import io.zingg.duckdb.api.DuckException;
import io.zingg.duckdb.api.Frame;
import io.zingg.duckdb.model.ModelReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Explicit lifecycle for a persisted native workflow.  The state machine is
 * intentionally independent of a worker process so a caller can close and
 * recreate the job between training and matching.
 */
public final class PersistedMatchWorkflow implements AutoCloseable {
  public enum State { CREATED, TRAINING_DATA_READY, LABELS_APPLIED, MODEL_PERSISTED, RESTARTED, MATCHED, CLOSED }
  private final CompatibilityRuntime runtime;
  private ZinggJob job;
  private State state = State.CREATED;
  private Path model;
  private Frame trainingData;
  private int appliedLabels;

  public PersistedMatchWorkflow(CompatibilityRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.job = new ZinggJob(runtime);
  }

  public Frame findTrainingData(Frame input, TrainingPlan.Config config) {
    requireOpen();
    trainingData = job.trainingData(Objects.requireNonNull(input, "input"), config);
    state = State.TRAINING_DATA_READY;
    return trainingData;
  }

  public LabelDecisionProvider.ApplyLabelsResult applyLabels(LabelDecisionProvider.ApplyLabelsRequest request) {
    requireState(State.TRAINING_DATA_READY);
    var result = job.applyLabels(request);
    appliedLabels += result.applied();
    state = State.LABELS_APPLIED;
    return result;
  }

  public NativeTrainingService.Result trainMatch(Frame input, NativeTrainingConfig config) {
    requireState(State.LABELS_APPLIED, State.TRAINING_DATA_READY);
    var result = job.trainNative(Objects.requireNonNull(input, "input"), config);
    model = result.artifactDirectory();
    state = State.MODEL_PERSISTED;
    return result;
  }

  /** Closes the current job and reopens a fresh job against the same runtime. */
  public void restart() {
    requireOpen();
    if (model == null) throw new DuckException("cannot restart before a model is persisted");
    job.close();
    job = new ZinggJob(runtime);
    try { ModelReader.load(model, 0); }
    catch (IOException e) { throw new DuckException("persisted model cannot be reloaded after restart", e); }
    state = State.RESTARTED;
  }

  public Frame match(Frame input, String predicate) {
    requireState(State.RESTARTED, State.MODEL_PERSISTED, State.MATCHED);
    Frame output = job.match(Objects.requireNonNull(input, "input"), predicate);
    state = State.MATCHED;
    return output;
  }

  public State state() { return state; }
  public Path model() { return model; }
  public int appliedLabels() { return appliedLabels; }

  @Override public void close() {
    if (state != State.CLOSED) { job.close(); state = State.CLOSED; }
  }

  private void requireOpen() { if (state == State.CLOSED) throw new DuckException("workflow is closed"); }
  private void requireState(State... allowed) {
    requireOpen();
    for (State candidate : allowed) if (candidate == state) return;
    throw new DuckException("invalid workflow state: " + state);
  }
}
