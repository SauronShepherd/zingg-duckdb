package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.Frame;
public final class TrainingPlan {
 public record Config(String blockingExpression,String blockingColumn,long maxRows){public Config{if(blockingExpression==null||blockingExpression.isBlank())throw new IllegalArgumentException("blocking expression required");blockingExpression=io.zingg.duckdb.engine.SqlSafety.predicate(blockingExpression);if(blockingColumn==null||blockingColumn.isBlank())throw new IllegalArgumentException("blocking column required");if(maxRows<1)throw new IllegalArgumentException("maxRows must be positive");}}
 public Frame prepare(Frame input,Config config){return input.withColumn(config.blockingColumn(),new io.zingg.duckdb.engine.DuckExpr(config.blockingExpression())).selectExpr("*", "row_number() OVER (ORDER BY z_zid) AS z_training_id").filter(new io.zingg.duckdb.engine.DuckExpr("z_training_id <= "+config.maxRows())).cache();}
}
