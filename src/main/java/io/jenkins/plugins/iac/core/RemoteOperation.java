package io.jenkins.plugins.iac.core;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
/** Durable provider-neutral correlation record. Metadata must never contain credentials or secrets. */
public record RemoteOperation(
 String key,
 String provider,
 String connectionId,
 String targetId,
 String requestToken,
 String remoteId,
 String status,
 Map<String,String> metadata,
 long startedAt
) implements Serializable {
 private static final long serialVersionUID=1L;
 public RemoteOperation {
  key=Identifiers.required(key,"operationKey");
  provider=Identifiers.required(provider,"provider");
  connectionId=Identifiers.required(connectionId,"connectionId");
  targetId=Identifiers.opaque(targetId,"targetId");
  requestToken=Identifiers.required(requestToken,"requestToken");
  remoteId=Identifiers.optionalOpaque(remoteId,"remoteId");
  status=Identifiers.opaque(status,"status");
  metadata=metadata==null?new LinkedHashMap<>():new LinkedHashMap<>(metadata);
 }
 @Override public Map<String,String> metadata(){return Collections.unmodifiableMap(metadata);}
 public RemoteOperation accepted(String id,String newStatus){
  return new RemoteOperation(key,provider,connectionId,targetId,requestToken,
    Identifiers.opaque(id,"remoteId"),newStatus,metadata,startedAt);
 }
 public RemoteOperation withStatus(String newStatus){
  return new RemoteOperation(key,provider,connectionId,targetId,requestToken,remoteId,newStatus,metadata,startedAt);
 }
}
