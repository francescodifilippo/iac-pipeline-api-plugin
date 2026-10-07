package io.jenkins.plugins.iac.core;
import java.nio.charset.StandardCharsets;
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
 public static String defaultOperationKey(String provider,String targetId){
  String p=required(provider,"provider");
  String t=opaque(targetId,"targetId");
  return p+"-"+UUID.nameUUIDFromBytes(t.getBytes(StandardCharsets.UTF_8));
 }
}
