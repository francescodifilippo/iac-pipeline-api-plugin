package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class IdentifiersTest {
 @Test void basicIdentifiers() {
  assertEquals("ws-123", Identifiers.required("ws-123", "workspace"));
  assertNull(Identifiers.optional(" ", "operationKey"));
  assertThrows(IllegalArgumentException.class, () -> Identifiers.required("../etc", "workspace"));
  assertThrows(IllegalArgumentException.class, () -> Identifiers.required("a?b", "workspace"));
 }
 @Test void opaqueProviderIdentifiers() {
  String stack="ocid1.ormstack.oc1.eu-frankfurt-1.example";
  assertEquals(stack,Identifiers.opaque(stack,"targetId"));
  assertEquals(Identifiers.defaultOperationKey("oci-rm",stack),Identifiers.defaultOperationKey("oci-rm",stack));
  assertTrue(Identifiers.defaultOperationKey("oci-rm",stack).startsWith("oci-rm-"));
  assertThrows(IllegalArgumentException.class,()->Identifiers.opaque("bad\nvalue","remoteId"));
 }
 @Test void defaultKeyDependsOnParameters() {
  String ws="ws-123";
  String plan=Identifiers.defaultOperationKey("otf",ws,Map.of("mode","plan"));
  String apply=Identifiers.defaultOperationKey("otf",ws,Map.of("mode","apply"));
  assertNotEquals(plan,apply);
  assertEquals(plan,Identifiers.defaultOperationKey("otf",ws,Map.of("mode","plan")));
  assertTrue(plan.startsWith("otf-"));
  assertEquals(plan,Identifiers.required(plan,"operationKey"));
  assertNotEquals(plan,Identifiers.defaultOperationKey("otf","ws-456",Map.of("mode","plan")));
  assertEquals(Identifiers.defaultOperationKey("otf",ws),Identifiers.defaultOperationKey("otf",ws,Map.of()));
  assertEquals(Identifiers.defaultOperationKey("otf",ws),Identifiers.defaultOperationKey("otf",ws,null));
  Map<String,String> ab=new LinkedHashMap<>();ab.put("a","1");ab.put("b","2");
  Map<String,String> ba=new LinkedHashMap<>();ba.put("b","2");ba.put("a","1");
  assertEquals(Identifiers.defaultOperationKey("otf",ws,ab),Identifiers.defaultOperationKey("otf",ws,ba),"order-independent");
  assertNotEquals(Identifiers.defaultOperationKey("otf",ws,Map.of("a","b1:c")),
    Identifiers.defaultOperationKey("otf",ws,Map.of("a","b","1:c","")),"unambiguous encoding");
 }
 @Test void defaultKeyForNonNullTargetIsUnchanged() {
  assertEquals("otf-"+UUID.nameUUIDFromBytes("ws-123".getBytes(StandardCharsets.UTF_8)),Identifiers.defaultOperationKey("otf","ws-123"));
  assertEquals("otf-"+UUID.nameUUIDFromBytes(("ws-123"+'\0'+"4:mode4:plan").getBytes(StandardCharsets.UTF_8)),
    Identifiers.defaultOperationKey("otf","ws-123",Map.of("mode","plan")));
 }
 @Test void defaultKeyForNamedTarget() {
  Map<String,String> named=Map.of("workspaceName","prod","mode","plan");
  String key=Identifiers.defaultOperationKey("otf",null,named);
  assertEquals(key,Identifiers.defaultOperationKey("otf",null,new LinkedHashMap<>(named)),"deterministic");
  assertEquals(key,Identifiers.defaultOperationKey("otf"," ",named),"blank is absent");
  assertEquals(key,Identifiers.required(key,"operationKey"));
  assertTrue(key.startsWith("otf-"));
  assertNotEquals(key,Identifiers.defaultOperationKey("otf",null,Map.of("workspaceName","dev","mode","plan")));
  assertNotEquals(key,Identifiers.defaultOperationKey("otf",null,Map.of("workspaceName","prod","mode","apply")));
  assertEquals(Identifiers.defaultOperationKey("otf",null),Identifiers.defaultOperationKey("otf",null,Map.of()));
  assertEquals(Identifiers.defaultOperationKey("otf",null),Identifiers.defaultOperationKey("otf",null,null));
  // No ID never collides with an ID, whatever the parameters.
  assertNotEquals(Identifiers.defaultOperationKey("otf",null,Map.of("a","1")),Identifiers.defaultOperationKey("otf","a",Map.of("a","1")));
  assertThrows(IllegalArgumentException.class,()->Identifiers.defaultOperationKey("otf","bad\nid",named));
 }
 @Test void safeUrls() {
  assertEquals("https://iac.example.org", HttpJsonClient.validateUrl("https://iac.example.org/", false).toString());
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("http://iac.example.org", false));
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("https://token@iac.example.org", false));
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("https://iac.example.org/subpath", false));
 }
}
