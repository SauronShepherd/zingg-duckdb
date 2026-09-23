package io.zingg.duckdb.engine;
import io.zingg.duckdb.api.CancellationToken;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
public final class DuckCancellation implements CancellationToken {
 private final AtomicReference<Statement> statement=new AtomicReference<>(); private volatile boolean cancelled;
 public void attach(Statement s){statement.set(s);if(cancelled)cancel(s);} public void cancel(){cancelled=true;Statement s=statement.get();if(s!=null)cancel(s);} public boolean isCancelled(){return cancelled;}
 private static void cancel(Statement s){try{s.cancel();}catch(Exception ignored){}}
}
