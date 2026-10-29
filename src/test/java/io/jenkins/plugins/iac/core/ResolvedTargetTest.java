package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;
import hudson.model.Run;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
class ResolvedTargetTest {
 @Test void validatesTargetAndCopiesParameters() {
  Map<String,String> p=new LinkedHashMap<>();p.put("b","2");p.put("a","1");
  ResolvedTarget r=new ResolvedTarget("ocid1.ormstack.oc1..x",p);
  p.put("c","3");
  assertEquals("ocid1.ormstack.oc1..x",r.targetId());
  assertEquals(Map.of("b","2","a","1"),r.parameters(),"copied");
  assertEquals("[b, a]",r.parameters().keySet().toString(),"order kept");
  assertThrows(UnsupportedOperationException.class,()->r.parameters().put("x","y"));
  assertEquals(Map.of(),new ResolvedTarget("ws-1",null).parameters());
  assertEquals(r,new ResolvedTarget("ocid1.ormstack.oc1..x",Map.of("a","1","b","2")));
  assertThrows(IllegalArgumentException.class,()->new ResolvedTarget(null,Map.of()));
  assertThrows(IllegalArgumentException.class,()->new ResolvedTarget(" ",Map.of()));
  assertThrows(IllegalArgumentException.class,()->new ResolvedTarget("bad\nid",Map.of()));
 }
 @Test void defaultResolveRequiresTargetId() throws Exception {
  IacBackend backend=new IacBackend(){
   @Override public String id(){return "otf";}
   @Override public JobResult submit(SubmissionRequest request,Run<?,?> run){throw new UnsupportedOperationException();}
   @Override public JobResult status(RemoteOperation operation,Run<?,?> run){throw new UnsupportedOperationException();}
  };
  Map<String,String> p=new HashMap<>(Map.of("mode","plan"));
  assertEquals(new ResolvedTarget("ws-1",Map.of("mode","plan")),backend.resolve("conn","ws-1",p,null));
  IllegalArgumentException e=assertThrows(IllegalArgumentException.class,()->backend.resolve("conn",null,p,null));
  assertEquals("otf target id is required",e.getMessage());
 }
}
