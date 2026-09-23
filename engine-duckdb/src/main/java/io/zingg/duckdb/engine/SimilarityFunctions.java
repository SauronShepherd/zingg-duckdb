package io.zingg.duckdb.engine;

public final class SimilarityFunctions {
 private SimilarityFunctions(){}
 public static double exact(String a,String b){return a==null||b==null?0d:(a.equals(b)?1d:0d);}
 /** Zingg v0.7 Jaccard behavior: null/empty values are maximally similar and tokens normalize case/punctuation. */
 public static double jaccard(String a,String b){if(isBlank(a)||isBlank(b))return 1d;var left=tokens(a);var right=tokens(b);var union=new java.util.HashSet<>(left);union.addAll(right);if(union.isEmpty())return 1d;var intersection=new java.util.HashSet<>(left);intersection.retainAll(right);return (double)intersection.size()/union.size();}
 private static boolean isBlank(String value){return value==null||value.trim().isEmpty();}
 private static java.util.Set<String> tokens(String value){String normalized=value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+"," ").trim();return normalized.isEmpty()?java.util.Set.of():new java.util.HashSet<>(java.util.List.of(normalized.split("\\s+")));}
 public static double normalizedLevenshtein(String a,String b){if(a==null||b==null)return 0d;if(a.equals(b))return 1d;int[] prev=new int[b.length()+1],cur=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;for(int i=1;i<=a.length();i++){cur[0]=i;for(int j=1;j<=b.length();j++)cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));int[] t=prev;prev=cur;cur=t;}return 1d-(double)prev[b.length()]/Math.max(a.length(),b.length());}
}
