package io.zingg.duckdb.compat;
import io.zingg.duckdb.api.DuckException; import java.util.*;
public final class ProfileRegistry {
 private final Map<String,CompatibilityProfile> profiles=new LinkedHashMap<>();
 public ProfileRegistry(){register(new CompatibilityProfile("zingg-0.7.0-duckdb-1.5.5.1","0.7.0","1.5.5.1","3.5.5",Map.of("clusterIds","timestamp-prefixed","graphTransitiveScore","zero","blockingHash","java-signed-32-bit","similarities","exact,jaccard,normalized_levenshtein,jaro,jaro_winkler","jaroWinklerDelegatesToJaro","true","profileSemantics","strict-released-quirks")));}
 public void register(CompatibilityProfile profile){if(profiles.putIfAbsent(profile.id(),profile)!=null)throw new DuckException("compatibility profile is immutable: "+profile.id());}
 public CompatibilityProfile require(String id){var p=profiles.get(id);if(p==null)throw new DuckException("unknown compatibility profile: "+id);return p;}
 public Set<String> ids(){return Collections.unmodifiableSet(profiles.keySet());}
}
