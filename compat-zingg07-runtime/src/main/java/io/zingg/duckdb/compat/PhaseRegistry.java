package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.*; import java.util.*;
public final class PhaseRegistry {
 private final EnumMap<ZinggJob.Phase,PhaseExecutor> executors=new EnumMap<>(ZinggJob.Phase.class);
 /** No phase is silently treated as an identity operation. Implementations must register an explicit executor. */
 public PhaseRegistry(){}
 public void register(ZinggJob.Phase phase,PhaseExecutor executor){executors.put(Objects.requireNonNull(phase),Objects.requireNonNull(executor));}
 public Frame execute(ZinggJob.Phase phase,ZinggJob job,Frame input){var executor=executors.get(phase);if(executor==null)throw new DuckException("no executor for phase: "+phase);return executor.execute(job,input);}
 public Set<ZinggJob.Phase> phases(){return Collections.unmodifiableSet(executors.keySet());}
}
