package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.*; import java.util.*;
public final class PhaseRegistry {
 private final EnumMap<ZinggJob.Phase,PhaseExecutor> executors=new EnumMap<>(ZinggJob.Phase.class);
 public PhaseRegistry(){executors.put(ZinggJob.Phase.FIND_TRAINING_DATA,(job,input)->input);executors.put(ZinggJob.Phase.TRAIN,(job,input)->input);executors.put(ZinggJob.Phase.MATCH,(job,input)->input);executors.put(ZinggJob.Phase.LINK,(job,input)->input);}
 public void register(ZinggJob.Phase phase,PhaseExecutor executor){executors.put(Objects.requireNonNull(phase),Objects.requireNonNull(executor));}
 public Frame execute(ZinggJob.Phase phase,ZinggJob job,Frame input){var executor=executors.get(phase);if(executor==null)throw new DuckException("no executor for phase: "+phase);return executor.execute(job,input);}
 public Set<ZinggJob.Phase> phases(){return Collections.unmodifiableSet(executors.keySet());}
}
