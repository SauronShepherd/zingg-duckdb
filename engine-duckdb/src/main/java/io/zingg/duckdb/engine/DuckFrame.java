package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.*;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.nio.channels.Channels;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.pojo.*;

/** Lazy relational frame whose execution and temporary state belong to one DuckJob. */
final class DuckFrame implements Frame {
  private final DuckJob job;
  private final String plan;
  private final List<String> columns;
  private final RelationScope scope;
  private final boolean zeroColumns;

  DuckFrame(DuckJob job, String plan, List<String> columns) {
    this(job, plan, columns, RelationScope.QUERY_ONLY, false);
  }
  DuckFrame(DuckJob job, String plan, List<String> columns, RelationScope scope) {
    this(job, plan, columns, scope, false);
  }
  DuckFrame(DuckJob job, String plan, List<String> columns, RelationScope scope, boolean zeroColumns) {
    this.job = Objects.requireNonNull(job); this.plan = Objects.requireNonNull(plan);
    this.columns = List.copyOf(columns == null ? List.of() : columns);
    this.scope = Objects.requireNonNull(scope);
    this.zeroColumns = zeroColumns;
  }
  public JobId owner() { return job.id(); }
  RelationScope scope() { return scope; }
  DuckRelationHandle handle() { return new DuckRelationHandle("relation_" + Integer.toHexString(System.identityHashCode(this)), scope, job.id(), job.connectionId(), plan, scope != RelationScope.QUERY_ONLY); }
  private List<String> availableColumns() {
    if (zeroColumns) return List.of();
    if (!columns.isEmpty()) {
      int actualCount=metadataColumnCount();
      if(actualCount==columns.size())return columns;
    }
    return metadataColumns();
  }
  private int metadataColumnCount(){
    try (var s=job.createStatement(); var r=s.executeQuery("SELECT * FROM ("+plan+") zd LIMIT 0")) {
      return r.getMetaData().getColumnCount();
    } catch(SQLException e){throw new DuckException("column inspection failed",e);}
  }
  private List<String> metadataColumns(){
    try (var s=job.createStatement(); var r=s.executeQuery("SELECT * FROM ("+plan+") zd LIMIT 0")) {
      var m=r.getMetaData(); var out=new ArrayList<String>();
      for(int i=1;i<=m.getColumnCount();i++) out.add(m.getColumnName(i));
      return List.copyOf(out);
    } catch(SQLException e) { throw new DuckException("column inspection failed",e); }
  }
  public List<String> columns() { return availableColumns(); }
  public List<Column> schema() {
    if (zeroColumns) return List.of();
    try (var s=job.createStatement();
         var r=s.executeQuery("SELECT * FROM ("+plan+") zd LIMIT 0")) {
      var m=r.getMetaData(); var out=new ArrayList<Column>();
      var names=availableColumns();
      for(int i=1;i<=m.getColumnCount();i++)
        out.add(new Column(names.size()==m.getColumnCount()?names.get(i-1):m.getColumnName(i),m.getColumnType(i),m.getColumnTypeName(i),
            m.isNullable(i)!=ResultSetMetaData.columnNoNulls));
      return List.copyOf(out);
    } catch(SQLException e) { throw new DuckException("schema inspection failed",e); }
  }
  public String explain() {
    try (var s=job.createStatement(); var r=s.executeQuery("EXPLAIN "+plan)) {
      var out=new StringBuilder();
      while(r.next()) { if(out.length()>0) out.append(System.lineSeparator()); out.append(r.getString(1)); }
      return out.toString();
    } catch(SQLException e) { throw new DuckException("explain failed",e); }
  }
  public List<Row> collect() { return collect(CancellationToken.none()); }
  public List<Row> collect(CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancelled();
    try (var s=job.createStatement()) {
      if(cancellation instanceof DuckCancellation dc) dc.attach(s);
      try(var r=s.executeQuery("SELECT * FROM ("+plan+") zd")) {
      var m=r.getMetaData(); var names=new ArrayList<String>();
      var declared=availableColumns();
      if (!zeroColumns) for(int i=1;i<=m.getColumnCount();i++) names.add(declared.size()==m.getColumnCount()?declared.get(i-1):m.getColumnName(i));
      var out=new ArrayList<Row>(); long collectedBytes=0;
      while(r.next()) { cancellation.throwIfCancelled(); var values=new ArrayList<Object>();
        if (!zeroColumns) for(int i=1;i<=m.getColumnCount();i++) values.add(r.getObject(i));
        out.add(new Row(names,values)); job.budget().enforceRows(out.size());
        for(Object value:values) collectedBytes += String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
        job.budget().enforceCollectBytes(collectedBytes + (long)out.size() * 8L);
      }
      return List.copyOf(out); }
    } catch(SQLException e) { throw new DuckException("collect failed",e); }
  }
  public Frame split(String column,String pattern,String resultColumn){if(column==null||column.isBlank()||pattern==null||resultColumn==null||resultColumn.isBlank())throw new DuckException("split columns and pattern are required");var available=availableColumns();if(!available.contains(column))throw new DuckException("split column is not present: "+column);var output=new ArrayList<>(available);if(!resultColumn.equals(column)&&output.contains(resultColumn))throw new DuckException("split result column already exists: "+resultColumn);if(!resultColumn.equals(column)){output.remove(column);output.add(resultColumn);}return new DuckFrame(job,"SELECT * EXCLUDE ("+DuckExpr.quote(column)+"), string_split("+DuckExpr.quote(column)+", "+DuckExpr.literal(pattern).sql()+") AS "+DuckExpr.quote(resultColumn)+" FROM ("+plan+") zd",output);}
  private void same(Frame other){if(other==null||!owner().equals(other.owner()))throw new DuckException("cross-job DuckFrame operation");}
  private static String joinType(String value){if(value==null)throw new DuckException("join type is required");return switch(value.trim().toLowerCase(Locale.ROOT)){case "inner"->"INNER";case "left","left_outer"->"LEFT";case "right","right_outer"->"RIGHT";case "full","outer","full_outer"->"FULL OUTER";case "left_semi"->"SEMI";case "left_anti"->"ANTI";default->throw new DuckException("unsupported join type: "+value);};}
  private DuckFrame q(String sql){return new DuckFrame(job,sql,columns,scope,zeroColumns);}
  public Frame select(String... c){if(c==null||c.length==0)throw new DuckException("select requires columns");var available=availableColumns();var selected=Arrays.stream(c).map(x->{if(x==null||x.isBlank()||!available.contains(x))throw new DuckException("selected column is not present: "+x);return x;}).toList();return new DuckFrame(job,"SELECT "+String.join(",",selected.stream().map(DuckExpr::quote).toList())+" FROM ("+plan+") zd",selected);}
  public Frame selectZeroColumns(){return new DuckFrame(job,"SELECT 1 AS \"__zingg_zero_column_sentinel\" FROM ("+plan+") zd",List.of(),scope,true);}
  public Frame selectExpr(String... e){
    if(e==null||e.length==0)throw new DuckException("selectExpr requires expressions");
    var expressions=Arrays.stream(e).map(SqlSafety::predicate).toList();
    return new DuckFrame(job,"SELECT "+String.join(",",expressions)+" FROM ("+plan+") zd",List.of());
  }
  public Frame filter(Expression e){return q("SELECT * FROM ("+plan+") zd WHERE "+SqlSafety.predicate(e.sql()));}
  public Frame filterInCond(Frame values,String left,String right){same(values);return q("SELECT l.* FROM ("+plan+") l JOIN ("+((DuckFrame)values).plan+") r ON l."+DuckExpr.quote(left)+" = r."+DuckExpr.quote(right));}
  public Frame explode(String list,String output){return q("SELECT zd.* EXCLUDE ("+DuckExpr.quote(list)+"), u.value AS "+DuckExpr.quote(output)+" FROM ("+plan+") zd, UNNEST("+DuckExpr.quote(list)+") u");}
  public Frame sort(String... e){if(e==null||e.length==0)throw new DuckException("sort requires expressions");return q("SELECT * FROM ("+plan+") zd ORDER BY "+String.join(",",Arrays.stream(e).map(SqlSafety::predicate).toList()));}
  public Frame limit(long rows){if(rows<0)throw new DuckException("limit cannot be negative");return q("SELECT * FROM ("+plan+") zd LIMIT "+rows);}
  public SplitResult partitionByPredicate(Expression e){if(e==null)throw new DuckException("partition predicate is required");String p=SqlSafety.predicate(e.sql());return new SplitResult(q("SELECT * FROM ("+plan+") zd WHERE "+p),q("SELECT * FROM ("+plan+") zd WHERE NOT ("+p+")"));}
  public SplitResult split(Expression e){return partitionByPredicate(e);}
  public Frame aggregate(String[] groups,String... aggs){if(aggs==null||aggs.length==0)throw new DuckException("aggregates required");String g=groups==null?"":String.join(",",Arrays.stream(groups).map(SqlSafety::predicate).toList());String a=String.join(",",Arrays.stream(aggs).map(SqlSafety::predicate).toList());return new DuckFrame(job,"SELECT "+(g.isBlank()?a:g+", "+a)+" FROM ("+plan+") zd"+(g.isBlank()?"":" GROUP BY "+g),List.of());}
  public Frame repartition(int n){if(n<1)throw new DuckException("partitions must be positive");return q("SELECT * FROM ("+plan+") zd");}
  public Frame coalesce(int n){if(n<1)throw new DuckException("partitions must be positive");return q("SELECT * FROM ("+plan+") zd");}
  public <T> Frame withColumn(String name,T value){
    if(name==null||name.isBlank())throw new DuckException("column name is required");
    DuckExpr e=value instanceof Expression expression
        ?new DuckExpr(SqlSafety.predicate(expression.sql())):DuckExpr.literal(value);
    var available=availableColumns();
    String prefix=zeroColumns?"SELECT ":available.contains(name)?"SELECT * EXCLUDE ("+DuckExpr.quote(name)+"), ":"SELECT *, ";
    var updated=new ArrayList<>(available); if(!updated.contains(name))updated.add(name);
    return new DuckFrame(job,""+prefix+e.sql()+" AS "+DuckExpr.quote(name)+" FROM ("+plan+") zd",updated);
  }
  public Frame drop(String... c){var d=new HashSet<>(Arrays.asList(c));var available=availableColumns();var remaining=available.stream().filter(x->!d.contains(x)).toList();if(remaining.isEmpty())return selectZeroColumns();return new DuckFrame(job,"SELECT "+String.join(",",remaining.stream().map(DuckExpr::quote).toList())+" FROM ("+plan+") zd",remaining);}
  public Frame rename(String from,String to){
    if(from==null||from.isBlank()||to==null||to.isBlank())throw new DuckException("rename columns are required");
    var available=availableColumns();
    if(!available.contains(from))throw new DuckException("renamed column is not present: "+from);
    if(available.contains(to)&&!from.equals(to))throw new DuckException("rename target already exists: "+to);
    var renamed=new ArrayList<>(available); renamed.set(renamed.indexOf(from),to);
    return new DuckFrame(job,"SELECT * EXCLUDE ("+DuckExpr.quote(from)+"), "+DuckExpr.quote(from)+" AS "+DuckExpr.quote(to)+" FROM ("+plan+") zd",renamed);
  }
  public Frame join(Frame right,String condition){return join(right,condition,"inner");}
  public Frame join(Frame right,String condition,String joinType){same(right);DuckFrame other=(DuckFrame)right;var leftColumns=availableColumns();var rightColumns=other.availableColumns();var output=new ArrayList<String>(leftColumns);output.addAll(rightColumns);String type=joinType(joinType);if(!zeroColumns&&!other.zeroColumns)return new DuckFrame(job,"SELECT * FROM ("+plan+") l "+type+" JOIN ("+other.plan+") r ON "+SqlSafety.predicate(condition),output);var projections=new ArrayList<String>();for(String column:leftColumns)projections.add("l."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(column));for(String column:rightColumns)projections.add("r."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(column));boolean zeroOutput=projections.isEmpty();if(zeroOutput)projections.add("1 AS \"__zingg_zero_column_sentinel\"");return new DuckFrame(job,"SELECT "+String.join(",",projections)+" FROM ("+plan+") l "+type+" JOIN ("+other.plan+") r ON "+SqlSafety.predicate(condition),output,RelationScope.QUERY_ONLY,zeroOutput);}
  public Frame joinUsing(Frame right,String joinType,String... joinColumns){same(right);if(joinColumns==null||joinColumns.length==0)throw new DuckException("join columns are required");var leftColumns=availableColumns();var rightColumns=((DuckFrame)right).availableColumns();for(String column:joinColumns){if(column==null||!leftColumns.contains(column)||!rightColumns.contains(column))throw new DuckException("join column must exist on both frames: "+column);}String using=String.join(",",Arrays.stream(joinColumns).map(DuckExpr::quote).toList());var output=new ArrayList<String>(leftColumns);for(String column:rightColumns)if(!Arrays.asList(joinColumns).contains(column))output.add(column);return new DuckFrame(job,"SELECT * FROM ("+plan+") l "+joinType(joinType)+" JOIN ("+((DuckFrame)right).plan+") r USING ("+using+")",output);}
  public Frame joinProjected(Frame right,String condition,String rightPrefix){
    same(right); if(rightPrefix==null||rightPrefix.isBlank())throw new DuckException("right column prefix is required");
    DuckFrame other=(DuckFrame)right; var leftColumns=availableColumns(); var rightColumns=other.availableColumns();
    var output=new ArrayList<String>(leftColumns); var projections=new ArrayList<String>();
    for(String column:leftColumns){projections.add("l."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(column));}
    for(String column:rightColumns){String alias=rightPrefix+column;if(output.contains(alias))throw new DuckException("projected pair column collision: "+alias);projections.add("r."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(alias));output.add(alias);}
    boolean zeroOutput=projections.isEmpty();if(zeroOutput)projections.add("1 AS \"__zingg_zero_column_sentinel\"");
    return new DuckFrame(job,"SELECT "+String.join(",",projections)+" FROM ("+plan+") l JOIN ("+other.plan+") r ON "+SqlSafety.predicate(condition),output,RelationScope.QUERY_ONLY,zeroOutput);
  }
  public Frame union(Frame other,boolean byName,boolean allowMissing){
    same(other);
    DuckFrame right=(DuckFrame)other;
    var leftColumns=availableColumns();var rightColumns=right.availableColumns();
    if (!byName && columns.size()!=right.columns.size())
      throw new DuckException("positional union requires equal column counts");
    if (byName && !allowMissing && (!new java.util.HashSet<>(leftColumns).equals(new java.util.HashSet<>(rightColumns))))
      throw new DuckException("by-name union requires identical column sets when missing columns are disabled");
    if(byName&&allowMissing&&zeroColumns!=right.zeroColumns){
      var outputColumns=new ArrayList<>(zeroColumns?rightColumns:leftColumns);
      String leftPlan=unionProjection(this,outputColumns),rightPlan=unionProjection(right,outputColumns);
      return new DuckFrame(job,"("+leftPlan+") UNION ALL ("+rightPlan+")",outputColumns);
    }
    var outputColumns=new ArrayList<>(leftColumns);
    if(byName) for(String column:rightColumns) if(!outputColumns.contains(column)) outputColumns.add(column);
    return new DuckFrame(job,"("+plan+")"+(byName?" UNION ALL BY NAME ":" UNION ALL ")+"("+right.plan+")",outputColumns,RelationScope.QUERY_ONLY,zeroColumns&&right.zeroColumns);
  }
  private static String unionProjection(DuckFrame frame,List<String> columns){String projection=String.join(",",columns.stream().map(column->frame.zeroColumns?"NULL AS "+DuckExpr.quote(column):DuckExpr.quote(column)).toList());return "SELECT "+projection+" FROM ("+frame.plan+") zu";}
  public Frame except(Frame other){same(other);DuckFrame right=(DuckFrame)other;if(availableColumns().size()!=right.availableColumns().size())throw new DuckException("EXCEPT requires equal column counts");return new DuckFrame(job,"("+plan+") EXCEPT ("+right.plan+")",availableColumns(),RelationScope.QUERY_ONLY,zeroColumns&&right.zeroColumns);}
  public Frame intersect(Frame other){same(other);DuckFrame right=(DuckFrame)other;if(availableColumns().size()!=right.availableColumns().size())throw new DuckException("INTERSECT requires equal column counts");return new DuckFrame(job,"("+plan+") INTERSECT ("+right.plan+")",availableColumns(),RelationScope.QUERY_ONLY,zeroColumns&&right.zeroColumns);}
  public Frame distinct(){return q("SELECT DISTINCT * FROM ("+plan+") zd");}
  public Frame dropDuplicates(String... subset){if(subset==null||subset.length==0)return distinct();var available=availableColumns();for(String column:subset)if(column==null||!available.contains(column))throw new DuckException("duplicate subset column is not present: "+column);String partition=String.join(",",Arrays.stream(subset).map(DuckExpr::quote).toList());return q("SELECT * FROM ("+plan+") zd QUALIFY ROW_NUMBER() OVER (PARTITION BY "+partition+" ORDER BY "+partition+") = 1");}
  public Frame sample(boolean withReplacement,double fraction){
    if(!Double.isFinite(fraction)||fraction<0)throw new DuckException("sample fraction must be finite and non-negative");
    if(!withReplacement){if(fraction==0)return filter(new DuckExpr("FALSE"));if(fraction>=1)return this;return filter(new DuckExpr("random() < "+Double.toString(fraction)));}
    if(fraction>1)throw new DuckException("sampling fraction with replacement must be at most 1");
    if(fraction==0)return filter(new DuckExpr("FALSE"));
    String randomColumn="zd_poisson_u_"+UUID.randomUUID().toString().replace("-","");
    String copiesColumn="zd_poisson_n_"+UUID.randomUUID().toString().replace("-","");
    while(columns.contains(randomColumn))randomColumn+="x";
    while(columns.contains(copiesColumn)||copiesColumn.equals(randomColumn))copiesColumn+="x";
    String random=DuckExpr.quote(randomColumn), copies=DuckExpr.quote(copiesColumn);
    String lambda=Double.toString(fraction);
    String source="zd_sample_source AS MATERIALIZED (SELECT *, random() AS "+random
        +" FROM ("+plan+") zd_sample_input)";
    String cdf="zd_poisson_cdf AS MATERIALIZED (SELECT k, list_sum(list_transform(range(0, k + 1), "
        +"i -> exp(-"+lambda+") * pow("+lambda+", i) / factorial(i::INTEGER))) AS cumulative_probability "
        +"FROM range(0, 33) AS zd_poisson_candidates(k))";
    String multiplicity="(SELECT min(k) FROM zd_poisson_cdf WHERE "+random+" <= cumulative_probability)";
    String withCounts="zd_sample_multiplicity AS MATERIALIZED (SELECT *, "+multiplicity
        +" AS "+copies+" FROM zd_sample_source)";
    String sampled="WITH "+source+", "+cdf+", "+withCounts+" SELECT zd_sample_multiplicity.* EXCLUDE ("
        +random+", "+copies+") FROM zd_sample_multiplicity CROSS JOIN LATERAL UNNEST(generate_series(1, "
        +copies+")) AS zd_poisson_replicas(replica)";
    return new DuckFrame(job,sampled,columns,scope,zeroColumns);
  }
  public Frame cache(){String n="zd_cache_"+UUID.randomUUID().toString().replace("-","");try(var s=job.createStatement()){s.execute("CREATE TABLE "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n)+" AS "+plan);job.registerMaterialized(n);try(var r=s.executeQuery("SELECT COUNT(*) FROM "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n))){r.next();job.budget().enforceRows(r.getLong(1));}return new DuckFrame(job,"SELECT * FROM "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n),availableColumns(),RelationScope.WORKER_SHARED,zeroColumns);}catch(SQLException e){throw new DuckException("cache failed",e);}}
  public long count(){return count(CancellationToken.none());}
  public long count(CancellationToken cancellation){Objects.requireNonNull(cancellation).throwIfCancelled();try(var s=job.createStatement()){if(cancellation instanceof DuckCancellation dc)dc.attach(s);try(var r=s.executeQuery("SELECT COUNT(*) FROM ("+plan+") zd")){r.next();cancellation.throwIfCancelled();long result=r.getLong(1);job.budget().enforceRows(result);return result;}}catch(SQLException e){throw new DuckException("count failed",e);}}
  public void writeCsv(java.nio.file.Path output,boolean header){copy(output,"FORMAT CSV, HEADER "+header);}
  public void writeParquet(java.nio.file.Path output){copy(output,"FORMAT PARQUET");}
  public void writeJson(java.nio.file.Path output){copy(output,"FORMAT JSON, ARRAY true");}
  public void writeArrow(java.nio.file.Path output){writeArrowIpc(output);}
  private void copy(java.nio.file.Path output,String options){
    if(zeroColumns)throw new DuckException("cannot export a zero-column frame");
    java.nio.file.Path temporary=null;
    try{
      PathPolicySupport.createParent(output);
      var parent=output.toAbsolutePath().normalize().getParent();
      temporary=Files.createTempFile(parent, output.getFileName().toString()+".", ".partial");
      try(var s=job.createStatement()){
        s.execute("COPY ("+plan+") TO "+DuckPath.sqlLiteral(temporary)+" ("+options+")");
      }
      if(Files.exists(temporary))job.budget().enforceOutputBytes(Files.size(temporary));
      try{Files.move(temporary,output,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
      catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temporary,output,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
      temporary=null;
    }catch(SQLException|java.io.IOException e){throw new DuckException("write failed",e);}
    finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){}}
  }
  private void writeArrowIpc(java.nio.file.Path output){
    if(zeroColumns)throw new DuckException("cannot export a zero-column frame");
    java.nio.file.Path temporary=null;
    try{
      PathPolicySupport.createParent(output);
      var parent=output.toAbsolutePath().normalize().getParent();
      temporary=Files.createTempFile(parent, output.getFileName().toString()+".", ".partial");
      try(var s=job.createStatement(); var r=s.executeQuery("SELECT * FROM ("+plan+") zd")){
        var metadata=r.getMetaData();
        var fields=new ArrayList<Field>();
        for(int i=1;i<=metadata.getColumnCount();i++) fields.add(new Field(metadata.getColumnName(i), FieldType.nullable(arrowType(metadata, i)), null));
        try(var allocator=new RootAllocator(); var root=VectorSchemaRoot.create(new Schema(fields),allocator);
            var channel=Channels.newChannel(Files.newOutputStream(temporary));
            var writer=new ArrowStreamWriter(root,null,channel)){
          writer.start();
          int rowCount=0;
          final int batchSize=1024;
          while(r.next()){
            if(rowCount==batchSize){
              root.setRowCount(rowCount);
              writer.writeBatch();
              root.clear();
              rowCount=0;
            }
            for(int i=1;i<=metadata.getColumnCount();i++) setArrowValue(root.getVector(i-1),rowCount,r.getObject(i));
            rowCount++;
          }
          root.setRowCount(rowCount);
          writer.writeBatch();
          writer.end();
        }
      }
      job.budget().enforceOutputBytes(Files.size(temporary));
      try{Files.move(temporary,output,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
      catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(temporary,output,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
      temporary=null;
    }catch(SQLException|java.io.IOException e){throw new DuckException("write Arrow failed",e);}
    finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(java.io.IOException ignored){}}
  }
  private static org.apache.arrow.vector.types.pojo.ArrowType arrowType(ResultSetMetaData metadata, int column) throws SQLException{
    int jdbcType=metadata.getColumnType(column);
    return switch(jdbcType){
      case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER -> new org.apache.arrow.vector.types.pojo.ArrowType.Int(32,true);
      case java.sql.Types.BIGINT -> new org.apache.arrow.vector.types.pojo.ArrowType.Int(64,true);
      case java.sql.Types.FLOAT, java.sql.Types.REAL -> new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.SINGLE);
      case java.sql.Types.DOUBLE -> new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE);
      case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> new org.apache.arrow.vector.types.pojo.ArrowType.Decimal(Math.max(1, metadata.getPrecision(column)), metadata.getScale(column), 128);
      case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> org.apache.arrow.vector.types.pojo.ArrowType.Bool.INSTANCE;
      case java.sql.Types.DATE -> new org.apache.arrow.vector.types.pojo.ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY);
      case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> new org.apache.arrow.vector.types.pojo.ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MILLISECOND, null);
      case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB -> org.apache.arrow.vector.types.pojo.ArrowType.Binary.INSTANCE;
      case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
          java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR -> org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE;
      default -> throw new DuckException("unsupported JDBC type for Arrow IPC: " + metadata.getColumnTypeName(column));
    };
  }
  private static void setArrowValue(FieldVector vector,int row,Object value){
    if(value==null){vector.setNull(row);return;}
    if(vector instanceof IntVector v)v.setSafe(row,((Number)value).intValue());
    else if(vector instanceof BigIntVector v)v.setSafe(row,((Number)value).longValue());
    else if(vector instanceof org.apache.arrow.vector.Float4Vector v)v.setSafe(row,((Number)value).floatValue());
    else if(vector instanceof Float8Vector v)v.setSafe(row,((Number)value).doubleValue());
    else if(vector instanceof DecimalVector v)v.setSafe(row,(java.math.BigDecimal)value);
    else if(vector instanceof BitVector v)v.setSafe(row,Boolean.TRUE.equals(value)?1:0);
    else if(vector instanceof DateDayVector v)v.setSafe(row, value instanceof java.time.LocalDate d ? (int)d.toEpochDay() : (int)((java.sql.Date)value).toLocalDate().toEpochDay());
    else if(vector instanceof TimeStampMilliVector v)v.setSafe(row, value instanceof java.time.LocalDateTime t ? t.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : value instanceof java.time.Instant i ? i.toEpochMilli() : ((java.util.Date)value).getTime());
    else if(vector instanceof VarBinaryVector v)v.setSafe(row,value instanceof byte[] b ? b : blobBytes(value));
    else if(vector instanceof VarCharVector v)v.setSafe(row,String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    else throw new DuckException("unsupported Arrow vector: "+vector.getClass().getName());
  }
  private static byte[] blobBytes(Object value){
    try { if(value instanceof java.sql.Blob b)return b.getBytes(1,(int)b.length()); }
    catch(SQLException e){throw new DuckException("cannot read JDBC binary value",e);}
    throw new DuckException("unsupported JDBC binary value: "+value.getClass().getName());
  }
  private static final class PathPolicySupport { static void createParent(java.nio.file.Path p)throws java.io.IOException{var parent=p.toAbsolutePath().normalize().getParent();if(parent!=null)Files.createDirectories(parent);} }
}
