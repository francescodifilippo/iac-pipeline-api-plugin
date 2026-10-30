package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

class VariableMapsTest {
 @Test void plainValuesBecomeStringsInTheirOrder(){
  Map<Object,Object> input=new LinkedHashMap<>();
  input.put("region","eu-west-1");input.put("node_count",3);input.put("ha",true);input.put("label",new StringBuilder("net-2"));
  LinkedHashMap<String,String> checked=VariableMaps.values(input,"variables");
  assertEquals(List.of("region","node_count","ha","label"),List.copyOf(checked.keySet()));
  assertEquals(Map.of("region","eu-west-1","node_count","3","ha","true","label","net-2"),checked);
  assertTrue(VariableMaps.values(null,"variables").isEmpty());
 }
 @Test void plainValuesMustBeScalars(){
  var e=assertThrows(IllegalArgumentException.class,()->VariableMaps.values(Map.of("zones",List.of("a")),"variables"));
  assertEquals("variables value of zones must be a string, number or boolean",e.getMessage());
 }
 @Test void namesAreChecked(){
  for(String bad:new String[]{"1BAD","a b","a.b","x".repeat(129)}){
   var e=assertThrows(IllegalArgumentException.class,()->VariableMaps.values(Map.of(bad,"x"),"environmentVariables"),bad);
   assertTrue(e.getMessage().startsWith("environmentVariables names must start with a letter or underscore"),e.getMessage());
  }
  assertEquals(Map.of("_a-1","x"),VariableMaps.values(Map.of("_a-1","x"),"variables"));
 }
 @Test void sensitiveValuesAreCredentialIds(){
  assertEquals(Map.of("token","api-token"),VariableMaps.credentialIds(Map.of("token","api-token"),"sensitiveVariables"));
  var e=assertThrows(IllegalArgumentException.class,()->VariableMaps.credentialIds(Map.of("token",3),"sensitiveVariables"));
  assertEquals("sensitiveVariables value of token must be the ID of a Secret text credential",e.getMessage());
  assertThrows(IllegalArgumentException.class,()->VariableMaps.credentialIds(Map.of("token","bad\nid"),"sensitiveVariables"));
 }
 @Test void aVariableCannotBePlainAndSensitive(){
  var e=assertThrows(IllegalArgumentException.class,()->VariableMaps.disjoint(Map.of("token","x"),Map.of("token","id"),"variables","sensitiveVariables"));
  assertEquals("Variable token is set in both variables and sensitiveVariables",e.getMessage());
  VariableMaps.disjoint(Map.of("a","x"),Map.of("b","id"),"variables","sensitiveVariables");
 }
 @Test void viewIsNullWhenEmpty(){
  assertNull(VariableMaps.view(new LinkedHashMap<>()));
  Map<String,String> view=VariableMaps.view(new LinkedHashMap<>(Map.of("a","x")));
  assertThrows(UnsupportedOperationException.class,()->view.put("b","y"));
 }
 @WithJenkins
 @Test void secretTextReadsTheCredentialOfTheBuild(JenkinsRule j) throws Exception {
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"db-password","DB password",Secret.fromString("db-password-value")));
  WorkflowJob p=j.createProject(WorkflowJob.class);
  p.setDefinition(new CpsFlowDefinition("echo 'ok'",true));
  WorkflowRun b=j.buildAndAssertSuccess(p);
  assertEquals("db-password-value",VariableMaps.secretText("db-password","db_password",b));
  var e=assertThrows(IllegalArgumentException.class,()->VariableMaps.secretText("missing","db_password",b));
  assertEquals("Missing or inaccessible Secret text credential missing for sensitive variable db_password",e.getMessage());
 }
}
