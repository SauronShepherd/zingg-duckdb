package io.zingg.duckdb.compat;
import java.util.*;
/** Spark PolynomialExpansion-compatible monomial ordering for degrees 1..N. */
public final class PolynomialFeatures {
  private PolynomialFeatures() {}
  public static double[] expand(double[] values,int degree){if(values==null||values.length==0||degree<1||degree>8)throw new IllegalArgumentException("invalid polynomial input");List<Double> out=new ArrayList<>();for(int d=1;d<=degree;d++)addValues(values,d,0,1d,out);return out.stream().mapToDouble(Double::doubleValue).toArray();}
  public static List<String> sqlTerms(List<String> columns,int degree){if(columns==null||columns.isEmpty()||degree<1||degree>8)throw new IllegalArgumentException("invalid polynomial columns");List<String> out=new ArrayList<>();for(int d=1;d<=degree;d++)addSql(columns,d,0,new ArrayList<>(),out);return out;}
  private static void addValues(double[] v,int left,int start,double product,List<Double> out){if(left==0){out.add(product);return;}for(int i=start;i<v.length;i++)addValues(v,left-1,i,product*v[i],out);}
  private static void addSql(List<String> c,int left,int start,List<Integer> indexes,List<String> out){if(left==0){StringJoiner j=new StringJoiner(" * ","(",")");for(int i:indexes)j.add("COALESCE(\""+c.get(i).replace("\"","\"\"")+"\", 0)");out.add(j.toString());return;}for(int i=start;i<c.size();i++){indexes.add(i);addSql(c,left-1,i,indexes,out);indexes.remove(indexes.size()-1);}}
}
