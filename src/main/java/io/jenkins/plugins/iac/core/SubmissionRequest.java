package io.jenkins.plugins.iac.core;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/** Immutable provider-neutral request. Parameters must not contain credentials or secrets. */
public record SubmissionRequest(
 String connectionId,
 String targetId,
 String requestToken,
 Map<String,String> parameters
) implements Serializable {
 private static final long serialVersionUID=1L;
 public SubmissionRequest {
  connectionId=Identifiers.required(connectionId,"connectionId");
  targetId=Identifiers.opaque(targetId,"targetId");
  requestToken=Identifiers.required(requestToken,"requestToken");
  parameters=parameters==null?new LinkedHashMap<>():new LinkedHashMap<>(parameters);
 }
 @Override public Map<String,String> parameters(){return Collections.unmodifiableMap(parameters);}
}
