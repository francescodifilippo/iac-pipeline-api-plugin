package io.jenkins.plugins.iac.core;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Result of {@link IacBackend#resolve}: the provider target ID and the parameters to submit with,
 * which replace (not merge with) the step's parameters. Parameters must not contain credentials or secrets.
 */
public record ResolvedTarget(String targetId,Map<String,String> parameters) implements Serializable {
 private static final long serialVersionUID=1L;
 public ResolvedTarget {
  targetId=Identifiers.opaque(targetId,"targetId");
  parameters=parameters==null?new LinkedHashMap<>():new LinkedHashMap<>(parameters);
 }
 @Override public Map<String,String> parameters(){return Collections.unmodifiableMap(parameters);}
}
