package io.zingg.duckdb.engine;
import io.zingg.duckdb.api.*; import org.duckdb.DuckDBConnection; import java.sql.*; import java.util.concurrent.atomic.AtomicBoolean; import java.util.concurrent.Semaphore;
public final class DuckRuntime implements RuntimeHandle {
 private final DuckDBConnection root; private final AtomicBoolean closed=new AtomicBoolean(); private final Semaphore jobSlots;
 public DuckRuntime(String url){this(new RuntimeConfig(url,Runtime.getRuntime().availableProcessors(),0,null,1));}
 public DuckRuntime(RuntimeConfig config){jobSlots=new Semaphore(config.maxConcurrentJobs());try{root=(DuckDBConnection)DriverManager.getConnection(config.databaseUrl());try(var s=root.createStatement()){s.execute("SET threads="+config.threads());if(config.memoryLimitBytes()>0)s.execute("SET memory_limit='"+config.memoryLimitBytes()+"B'");if(config.tempDirectory()!=null)s.execute("SET temp_directory='"+config.tempDirectory().toAbsolutePath().normalize().toString().replace("'","''")+"'");if(config.maxTempDirectoryBytes()>0)s.execute("SET max_temp_directory_size='"+config.maxTempDirectoryBytes()+"B'");}}catch(SQLException e){throw new DuckException("cannot open DuckDB: "+config.databaseUrl(),e);}}
 public JobHandle openJob(){if(closed.get())throw new DuckException("runtime is closed");try{jobSlots.acquire();try{return new DuckJob(root.duplicate(),JobId.create(),jobSlots::release);}catch(SQLException|RuntimeException e){jobSlots.release();throw e;}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new DuckException("interrupted waiting for job slot",e);}catch(SQLException e){throw new DuckException("cannot duplicate DuckDB connection",e);}}
 public JobHandle openJob(ResourceBudget budget){if(closed.get())throw new DuckException("runtime is closed");try{jobSlots.acquire();try{return new DuckJob(root.duplicate(),JobId.create(),jobSlots::release,budget);}catch(SQLException|RuntimeException e){jobSlots.release();throw e;}}catch(InterruptedException e){Thread.currentThread().interrupt();throw new DuckException("interrupted waiting for job slot",e);}catch(SQLException e){throw new DuckException("cannot duplicate DuckDB connection",e);}}
 public RuntimeDiagnostics diagnostics(){
  if(closed.get())throw new DuckException("runtime is closed");
  try(var s=root.createStatement();var rs=s.executeQuery("SELECT current_setting('memory_limit'), current_setting('max_temp_directory_size'), current_setting('temp_directory'), current_setting('threads')")){
   if(!rs.next())throw new DuckException("DuckDB did not return runtime settings");
   var memory=Runtime.getRuntime();
   String tempDirectory=rs.getString(3);
   return new RuntimeDiagnostics(rs.getString(1),rs.getString(2),tempDirectory,rs.getString(4),memory.totalMemory()-memory.freeMemory(),memory.maxMemory(),ProcessResourceSnapshot.residentBytes(),ProcessResourceSnapshot.directoryBytes(tempDirectory));
  }catch(SQLException e){throw new DuckException("cannot read runtime diagnostics",e);}
 }
 public void close(){if(closed.compareAndSet(false,true))try{root.close();}catch(SQLException e){throw new DuckException("cannot close DuckDB",e);}}
}
