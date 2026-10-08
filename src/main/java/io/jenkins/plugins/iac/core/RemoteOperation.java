package io.jenkins.plugins.iac.core;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable provider-neutral correlation object. Metadata must never contain credentials or secrets. */
public final class RemoteOperation implements Serializable {
 private static final long serialVersionUID=1L;

 private String key;
 private String provider;
 private String connectionId;
 private String targetId;
 private String requestToken;
 private String remoteId;
 private String status;
 private LinkedHashMap<String,String> metadata=new LinkedHashMap<>();
 private long startedAt;

 /** For Jenkins/XStream deserialization. */
 @SuppressWarnings("unused")
 private RemoteOperation(){}

 public RemoteOperation(String key,String provider,String connectionId,String targetId,String requestToken,
   String remoteId,String status,Map<String,String> metadata,long startedAt){
  this.key=Identifiers.required(key,"operationKey");
  this.provider=Identifiers.required(provider,"provider");
  this.connectionId=Identifiers.required(connectionId,"connectionId");
  this.targetId=Identifiers.opaque(targetId,"targetId");
  this.requestToken=Identifiers.required(requestToken,"requestToken");
  this.remoteId=Identifiers.optionalOpaque(remoteId,"remoteId");
  this.status=Identifiers.opaque(status,"status");
  if(metadata!=null)this.metadata.putAll(metadata);
  this.startedAt=startedAt;
 }

 public String key(){return key;}
 public String provider(){return provider;}
 public String connectionId(){return connectionId;}
 public String targetId(){return targetId;}
 public String requestToken(){return requestToken;}
 public String remoteId(){return remoteId;}
 public String status(){return status;}
 public Map<String,String> metadata(){return Collections.unmodifiableMap(metadata);}
 public long startedAt(){return startedAt;}

 public RemoteOperation accepted(String id,String newStatus){
  return new RemoteOperation(key,provider,connectionId,targetId,requestToken,
    Identifiers.opaque(id,"remoteId"),newStatus,metadata,startedAt);
 }

 public RemoteOperation withStatus(String newStatus){
  return new RemoteOperation(key,provider,connectionId,targetId,requestToken,remoteId,newStatus,metadata,startedAt);
 }
}
