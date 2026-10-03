package io.jenkins.plugins.iac.core;
public final class Identifiers {
 private Identifiers(){}
 public static String required(String value,String name){
  if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    throw new IllegalArgumentException(name+" must contain 1..128 letters, numbers, underscores or hyphens");
  return value;
 }
 public static String optional(String value,String name){return value==null || value.isBlank()?null:required(value,name);}
}
