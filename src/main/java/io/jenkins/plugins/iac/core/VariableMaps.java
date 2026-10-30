package io.jenkins.plugins.iac.core;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.model.Run;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
/**
 * Variable maps of provider setup steps, such as {@code variables: [region: 'eu-west-1']} and
 * {@code sensitiveVariables: [db_password: 'db-password-credential']}. Pipeline passes a map literal's values as
 * Groovy gives them (numbers, booleans, GStrings): plain values become strings, and sensitive values are IDs of Secret
 * text credentials, read only when the variables are set and never stored.
 */
public final class VariableMaps {
 private VariableMaps(){}
 /** Checked plain variables in their given order; {@code map} names the step parameter in messages. */
 public static LinkedHashMap<String,String> values(Map<?,?> input,String map){
  LinkedHashMap<String,String> checked=new LinkedHashMap<>();
  if(input!=null)for(Map.Entry<?,?> e:input.entrySet()){
   String k=key(e.getKey(),map);
   Object v=e.getValue();
   if(!(v instanceof CharSequence||v instanceof Number||v instanceof Boolean))
    throw new IllegalArgumentException(map+" value of "+k+" must be a string, number or boolean");
   checked.put(k,v.toString());
  }
  return checked;
 }
 /** Checked sensitive variables, as variable name to credential ID, in their given order. */
 public static LinkedHashMap<String,String> credentialIds(Map<?,?> input,String map){
  LinkedHashMap<String,String> checked=new LinkedHashMap<>();
  if(input!=null)for(Map.Entry<?,?> e:input.entrySet()){
   String k=key(e.getKey(),map);
   if(!(e.getValue() instanceof CharSequence))
    throw new IllegalArgumentException(map+" value of "+k+" must be the ID of a Secret text credential");
   checked.put(k,Identifiers.opaque(e.getValue().toString(),map+" credential ID of "+k));
  }
  return checked;
 }
 /** Fails when a variable is in both maps, which set the same kind of variable. */
 public static void disjoint(Map<String,String> plain,Map<String,String> sensitive,String plainName,String sensitiveName){
  for(String key:plain.keySet())if(sensitive.containsKey(key))
   throw new IllegalArgumentException("Variable "+key+" is set in both "+plainName+" and "+sensitiveName);
 }
 /** A read-only view for a step getter, or null when the map is empty, so that the snippet generator omits it. */
 public static Map<String,String> view(Map<String,String> map){return map.isEmpty()?null:Collections.unmodifiableMap(map);}
 /** The value of the Secret text credential of a sensitive variable, as the build may see it. Never log it. */
 public static String secretText(String credentialId,String variable,Run<?,?> run){
  StringCredentials c=CredentialsProvider.findCredentialById(credentialId,StringCredentials.class,run,Collections.emptyList());
  if(c==null)throw new IllegalArgumentException("Missing or inaccessible Secret text credential "+credentialId+" for sensitive variable "+variable);
  return c.getSecret().getPlainText();
 }
 private static String key(Object key,String map){
  String k=key==null?null:key.toString();
  if(k==null||!k.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"))
   throw new IllegalArgumentException(map+" names must start with a letter or underscore and contain up to 128 letters, numbers, underscores or hyphens");
  return k;
 }
}
