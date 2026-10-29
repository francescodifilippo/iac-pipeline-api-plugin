package io.jenkins.plugins.iac.core;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
public final class Identifiers {
 private Identifiers(){}
 public static String required(String value,String name){
  if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    throw new IllegalArgumentException(name+" must contain 1..128 letters, numbers, underscores or hyphens");
  return value;
 }
 public static String optional(String value,String name){return value==null || value.isBlank()?null:required(value,name);}
 public static String opaque(String value,String name){
  if(value==null || value.isBlank() || value.length()>4096 || value.chars().anyMatch(c->c<32 || c==127))
    throw new IllegalArgumentException(name+" must contain 1..4096 non-control characters");
  return value;
 }
 public static String optionalOpaque(String value,String name){return value==null || value.isBlank()?null:opaque(value,name);}
 /** Same as {@link #defaultOperationKey(String,String,Map)} with no parameters; targetId may be null. */
 public static String defaultOperationKey(String provider,String targetId){return defaultOperationKey(provider,targetId,null);}
 /**
  * Deterministic default key for one provider, target and parameter set, so e.g. a plan and an apply stage
  * on the same target get different keys. Parameter order is irrelevant; no parameters gives the
  * {@link #defaultOperationKey(String,String)} value. A null (or blank) targetId, for a target named in the
  * parameters, gives a key distinct from every non-null targetId.
  */
 public static String defaultOperationKey(String provider,String targetId,Map<String,String> parameters){
  String p=required(provider,"provider");
  String t=optionalOpaque(targetId,"targetId");
  // targetId cannot contain control characters (so the null marker cannot collide), and entries are
  // length-prefixed, so the encoding is unambiguous.
  StringBuilder b=new StringBuilder(t==null?"\0":t);
  if(parameters!=null)for(Map.Entry<String,String> e:new TreeMap<>(parameters).entrySet())
   b.append('\0').append(lengthPrefixed(e.getKey())).append(lengthPrefixed(e.getValue()));
  return p+"-"+UUID.nameUUIDFromBytes(b.toString().getBytes(StandardCharsets.UTF_8));
 }
 private static String lengthPrefixed(String value){return value==null?"-1:":value.length()+":"+value;}
}
