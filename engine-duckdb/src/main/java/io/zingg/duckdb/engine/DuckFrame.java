package io.zingg.duckdb.engine;

import io.zingg.duckdb.api.*;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

/** Lazy relational frame whose execution and temporary state belong to one DuckJob. */
final class DuckFrame implements Frame {
  private final DuckJob job;
  private final String plan;
  private final List<String> columns;
  private final RelationScope scope;

  DuckFrame(DuckJob job, String plan, List<String> columns) {
    this(job, plan, columns, RelationScope.QUERY_ONLY);
  }
  DuckFrame(DuckJob job, String plan, List<String> columns, RelationScope scope) {
    this.job = Objects.requireNonNull(job); this.plan = Objects.requireNonNull(plan);
    this.columns = List.copyOf(columns == null ? List.of() : columns);
    this.scope = Objects.requireNonNull(scope);
  }
  public JobId owner() { return job.id(); }
  RelationScope scope() { return scope; }
  DuckRelationHandle handle() { return new DuckRelationHandle("relation_" + Integer.toHexString(System.identityHashCode(this)), scope, job.id(), job.connectionId(), plan, scope != RelationScope.QUERY_ONLY); }
  private List<String> availableColumns() {
    if (!columns.isEmpty()) return columns;
    try (var s=job.connection().createStatement(); var r=s.executeQuery("SELECT * FROM ("+plan+") zd LIMIT 0")) {
      var m=r.getMetaData(); var out=new ArrayList<String>();
      for(int i=1;i<=m.getColumnCount();i++) out.add(m.getColumnName(i));
      return List.copyOf(out);
    } catch(SQLException e) { throw new DuckException("column inspection failed",e); }
  }
  public List<String> columns() { return availableColumns(); }
  public List<Column> schema() {
    try (var s=job.connection().createStatement();
         var r=s.executeQuery("SELECT * FROM ("+plan+") zd LIMIT 0")) {
      var m=r.getMetaData(); var out=new ArrayList<Column>();
      for(int i=1;i<=m.getColumnCount();i++)
        out.add(new Column(m.getColumnName(i),m.getColumnType(i),m.getColumnTypeName(i),
            m.isNullable(i)!=ResultSetMetaData.columnNoNulls));
      return List.copyOf(out);
    } catch(SQLException e) { throw new DuckException("schema inspection failed",e); }
  }
  public String explain() {
    try (var s=job.connection().createStatement(); var r=s.executeQuery("EXPLAIN "+plan)) {
      var out=new StringBuilder();
      while(r.next()) { if(out.length()>0) out.append(System.lineSeparator()); out.append(r.getString(1)); }
      return out.toString();
    } catch(SQLException e) { throw new DuckException("explain failed",e); }
  }
  public List<Row> collect() { return collect(CancellationToken.none()); }
  public List<Row> collect(CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancelled();
    try (var s=job.connection().createStatement()) {
      if(cancellation instanceof DuckCancellation dc) dc.attach(s);
      try(var r=s.executeQuery("SELECT * FROM ("+plan+") zd")) {
      var m=r.getMetaData(); var names=new ArrayList<String>();
      for(int i=1;i<=m.getColumnCount();i++) names.add(m.getColumnName(i));
      var out=new ArrayList<Row>(); long collectedBytes=0;
      while(r.next()) { cancellation.throwIfCancelled(); var values=new ArrayList<Object>();
        for(int i=1;i<=m.getColumnCount();i++) values.add(r.getObject(i));
        out.add(new Row(names,values)); job.budget().enforceRows(out.size());
        for(Object value:values) collectedBytes += String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
        job.budget().enforceCollectBytes(collectedBytes + (long)out.size() * 8L);
      }
      return List.copyOf(out); }
    } catch(SQLException e) { throw new DuckException("collect failed",e); }
  }
  private void same(Frame other){if(other==null||!owner().equals(other.owner()))throw new DuckException("cross-job DuckFrame operation");}
  private DuckFrame q(String sql){return new DuckFrame(job,sql,columns);}
  public Frame select(String... c){if(c==null||c.length==0)throw new DuckException("select requires columns");var available=availableColumns();var selected=Arrays.stream(c).map(x->{if(x==null||x.isBlank()||!available.contains(x))throw new DuckException("selected column is not present: "+x);return x;}).toList();return new DuckFrame(job,"SELECT "+String.join(",",selected.stream().map(DuckExpr::quote).toList())+" FROM ("+plan+") zd",selected);}
  public Frame selectExpr(String... e){
    if(e==null||e.length==0)throw new DuckException("selectExpr requires expressions");
    var expressions=Arrays.stream(e).map(SqlSafety::predicate).toList();
    var available=availableColumns();
    var projected=new ArrayList<String>();
    for(String expression:expressions){
      if(expression.equals("*")){projected.addAll(available);continue;}
      var alias=java.util.regex.Pattern.compile("(?is).*\\bAS\\s+([\\\"]?[A-Za-z_][A-Za-z0-9_]*[\\\"]?)\\s*$").matcher(expression);
      if(alias.matches()) projected.add(alias.group(1).replace("\"",""));
    }
    return new DuckFrame(job,"SELECT "+String.join(",",expressions)+" FROM ("+plan+") zd",projected);
  }
  public Frame filter(Expression e){return q("SELECT * FROM ("+plan+") zd WHERE "+SqlSafety.predicate(e.sql()));}
  public Frame filterInCond(Frame values,String left,String right){same(values);return q("SELECT l.* FROM ("+plan+") l JOIN ("+((DuckFrame)values).plan+") r ON l."+DuckExpr.quote(left)+" = r."+DuckExpr.quote(right));}
  public Frame explode(String list,String output){return q("SELECT zd.* EXCLUDE ("+DuckExpr.quote(list)+"), u.value AS "+DuckExpr.quote(output)+" FROM ("+plan+") zd, UNNEST("+DuckExpr.quote(list)+") u");}
  public Frame sort(String... e){if(e==null||e.length==0)throw new DuckException("sort requires expressions");return q("SELECT * FROM ("+plan+") zd ORDER BY "+String.join(",",Arrays.stream(e).map(SqlSafety::predicate).toList()));}
  public Frame limit(long rows){if(rows<0)throw new DuckException("limit cannot be negative");return q("SELECT * FROM ("+plan+") zd LIMIT "+rows);}
  public SplitResult split(Expression e){String p=SqlSafety.predicate(e.sql());return new SplitResult(q("SELECT * FROM ("+plan+") zd WHERE "+p),q("SELECT * FROM ("+plan+") zd WHERE NOT ("+p+")"));}
  public Frame aggregate(String[] groups,String... aggs){if(aggs==null||aggs.length==0)throw new DuckException("aggregates required");String g=groups==null?"":String.join(",",Arrays.stream(groups).map(SqlSafety::predicate).toList());String a=String.join(",",Arrays.stream(aggs).map(SqlSafety::predicate).toList());return q("SELECT "+(g.isBlank()?a:g+", "+a)+" FROM ("+plan+") zd"+(g.isBlank()?"":" GROUP BY "+g));}
  public Frame repartition(int n){if(n<1)throw new DuckException("partitions must be positive");return q("SELECT * FROM ("+plan+") zd");}
  public Frame coalesce(int n){if(n<1)throw new DuckException("partitions must be positive");return q("SELECT * FROM ("+plan+") zd");}
  public <T> Frame withColumn(String name,T value){
    if(name==null||name.isBlank())throw new DuckException("column name is required");
    DuckExpr e=value instanceof DuckExpr d?d:DuckExpr.literal(value);
    var available=availableColumns();
    String prefix=available.contains(name)?"SELECT * EXCLUDE ("+DuckExpr.quote(name)+"), ":"SELECT *, ";
    var updated=new ArrayList<>(available); if(!updated.contains(name))updated.add(name);
    return new DuckFrame(job,""+prefix+e.sql()+" AS "+DuckExpr.quote(name)+" FROM ("+plan+") zd",updated);
  }
  public Frame drop(String... c){var d=new HashSet<>(Arrays.asList(c));var available=availableColumns();var remaining=available.stream().filter(x->!d.contains(x)).toList();return new DuckFrame(job,"SELECT "+String.join(",",remaining.stream().map(DuckExpr::quote).toList())+" FROM ("+plan+") zd",remaining);}
  public Frame rename(String from,String to){
    if(from==null||from.isBlank()||to==null||to.isBlank())throw new DuckException("rename columns are required");
    var available=availableColumns();
    if(!available.contains(from))throw new DuckException("renamed column is not present: "+from);
    if(available.contains(to)&&!from.equals(to))throw new DuckException("rename target already exists: "+to);
    var renamed=new ArrayList<>(available); renamed.set(renamed.indexOf(from),to);
    return new DuckFrame(job,"SELECT * EXCLUDE ("+DuckExpr.quote(from)+"), "+DuckExpr.quote(from)+" AS "+DuckExpr.quote(to)+" FROM ("+plan+") zd",renamed);
  }
  public Frame join(Frame right,String condition){same(right);return q("SELECT * FROM ("+plan+") l JOIN ("+((DuckFrame)right).plan+") r ON "+SqlSafety.predicate(condition));}
  public Frame joinProjected(Frame right,String condition,String rightPrefix){
    same(right); if(rightPrefix==null||rightPrefix.isBlank())throw new DuckException("right column prefix is required");
    DuckFrame other=(DuckFrame)right; var leftColumns=availableColumns(); var rightColumns=other.availableColumns();
    var output=new ArrayList<String>(leftColumns); var projections=new ArrayList<String>();
    for(String column:leftColumns){projections.add("l."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(column));}
    for(String column:rightColumns){String alias=rightPrefix+column;if(output.contains(alias))throw new DuckException("projected pair column collision: "+alias);projections.add("r."+DuckExpr.quote(column)+" AS "+DuckExpr.quote(alias));output.add(alias);}
    return new DuckFrame(job,"SELECT "+String.join(",",projections)+" FROM ("+plan+") l JOIN ("+other.plan+") r ON "+SqlSafety.predicate(condition),output);
  }
  public Frame union(Frame other,boolean byName,boolean allowMissing){
    same(other);
    DuckFrame right=(DuckFrame)other;
    if (!byName && columns.size()!=right.columns.size())
      throw new DuckException("positional union requires equal column counts");
    if (byName && !allowMissing && (!new java.util.HashSet<>(columns).equals(new java.util.HashSet<>(right.columns))))
      throw new DuckException("by-name union requires identical column sets when missing columns are disabled");
    var outputColumns=new ArrayList<>(columns);
    if(byName) for(String column:right.columns) if(!outputColumns.contains(column)) outputColumns.add(column);
    return new DuckFrame(job,"("+plan+")"+(byName?" UNION ALL BY NAME ":" UNION ALL ")+"("+right.plan+")",outputColumns);
  }
  public Frame except(Frame other){same(other);return q("("+plan+") EXCEPT ("+((DuckFrame)other).plan+")");}
  public Frame distinct(){return q("SELECT DISTINCT * FROM ("+plan+") zd");}
  public Frame cache(){String n="zd_cache_"+UUID.randomUUID().toString().replace("-","");try(var s=job.connection().createStatement()){s.execute("CREATE TABLE "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n)+" AS "+plan);job.registerMaterialized(n);try(var r=s.executeQuery("SELECT COUNT(*) FROM "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n))){r.next();job.budget().enforceRows(r.getLong(1));}return new DuckFrame(job,"SELECT * FROM "+DuckExpr.quote(job.schemaName())+"."+DuckExpr.quote(n),availableColumns(),RelationScope.WORKER_SHARED);}catch(SQLException e){throw new DuckException("cache failed",e);}}
  public long count(){return count(CancellationToken.none());}
  public long count(CancellationToken cancellation){Objects.requireNonNull(cancellation).throwIfCancelled();try(var s=job.connection().createStatement()){if(cancellation instanceof DuckCancellation dc)dc.attach(s);try(var r=s.executeQuery("SELECT COUNT(*) FROM ("+plan+") zd")){r.next();cancellation.throwIfCancelled();long result=r.getLong(1);job.budget().enforceRows(result);return result;}}catch(SQLException e){throw new DuckException("count failed",e);}}
  public void writeCsv(java.nio.file.Path output,boolean header){copy(output,"FORMAT CSV, HEADER "+header);}
  public void writeParquet(java.nio.file.Path output){copy(output,"FORMAT PARQUET");}
  public void writeJson(java.nio.file.Path output){copy(output,"FORMAT JSON, ARRAY true");}
  public void writeArrow(java.nio.file.Path output){copy(output,"FORMAT ARROW");}
  private void copy(java.nio.file.Path output,String options){try{PathPolicySupport.createParent(output);try(var s=job.connection().createStatement()){s.execute("COPY ("+plan+") TO "+DuckPath.sqlLiteral(output.toAbsolutePath().normalize())+" ("+options+")");}if(Files.exists(output))job.budget().enforceOutputBytes(Files.size(output));}catch(SQLException|java.io.IOException e){throw new DuckException("write failed",e);}}
  private static final class PathPolicySupport { static void createParent(java.nio.file.Path p)throws java.io.IOException{var parent=p.toAbsolutePath().normalize().getParent();if(parent!=null)Files.createDirectories(parent);} }
}
