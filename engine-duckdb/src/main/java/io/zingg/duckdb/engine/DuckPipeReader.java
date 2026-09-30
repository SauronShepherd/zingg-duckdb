package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** Phase-aware input reader. Source tagging deliberately happens before union. */
public final class DuckPipeReader {
  public enum UnionMode { MATCH_BY_NAME, TRAINING_POSITIONAL }
  private final DuckJob job; private final io.zingg.duckdb.api.PathPolicy policy; private final ResourceBudget budget;
  public DuckPipeReader(JobHandle job) { this(job,null,ResourceBudget.unlimited()); }
  public DuckPipeReader(JobHandle job,io.zingg.duckdb.api.PathPolicy policy) { this(job,policy,ResourceBudget.unlimited()); }
  public DuckPipeReader(JobHandle job,io.zingg.duckdb.api.PathPolicy policy,ResourceBudget budget) { if (!(job instanceof DuckJob j)) throw new IllegalArgumentException("DuckJob required"); this.job=j; this.policy=policy; this.budget=budget==null?ResourceBudget.unlimited():budget; }
  public Frame read(List<Path> files, UnionMode mode) {
    return read(files, mode, true);
  }
  /**
   * Reads and unions source files, optionally assigning the backend's row ID.
   * Phases whose input contract already carries entity IDs (for example LINK
   * scored-pair endpoints) must preserve those values rather than regenerate them.
   */
  public Frame read(List<Path> files, UnionMode mode, boolean assignRowIds) {
    if (files==null || files.isEmpty()) throw new DuckException("at least one input pipe is required");
    Frame result=null;
    for (int i=0;i<files.size();i++) {
      Path p=policy==null?files.get(i).toAbsolutePath().normalize():policy.input(files.get(i));
      if (java.nio.file.Files.isSymbolicLink(p)) throw new DuckException("symbolic-link inputs are not allowed: "+p);
      if (!java.nio.file.Files.isRegularFile(p)) throw new DuckException("input file does not exist: "+p);
      try { budget.enforceInputBytes(java.nio.file.Files.size(p)); } catch (java.io.IOException e) { throw new DuckException("cannot inspect input size: "+p,e); }
      if (isArrow(p)) {
        Frame current=ArrowFileSupport.readWithAppender(job,p).withColumn("z_source", i);
        result=result==null?current:result.union(current, mode==UnionMode.MATCH_BY_NAME, mode==UnionMode.MATCH_BY_NAME);
        continue;
      }
      Frame current=job.sql("SELECT * FROM "+readerSql(p)).withColumn("z_source", i);
      result=result==null?current:result.union(current, mode==UnionMode.MATCH_BY_NAME, mode==UnionMode.MATCH_BY_NAME);
    }
    Frame merged=result.cache();
    if(assignRowIds) merged=RowIdAssigner.assign(merged,"z_zid").cache();
    budget.enforceRows(merged.count());
    return merged;
  }
  private static boolean isArrow(Path p){String n=p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);return n.endsWith(".arrow")||n.endsWith(".feather");}
  private static String readerSql(Path p){
    String n=p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    String path=DuckExpr.literal(p.toString()).sql();
    if(n.endsWith(".parquet")) return "read_parquet("+path+")";
    if(n.endsWith(".json")||n.endsWith(".jsonl")) return "read_json_auto("+path+")";
    if(n.endsWith(".csv")) return "read_csv_auto("+path+")";
    if(n.endsWith(".tsv")) return "read_csv_auto("+path+", delim='\\t')";
    throw new DuckException("unsupported input format: "+p);
  }
}
