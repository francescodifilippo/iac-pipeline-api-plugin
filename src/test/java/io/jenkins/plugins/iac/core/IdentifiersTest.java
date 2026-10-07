package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;
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
 @Test void safeUrls() {
  assertEquals("https://iac.example.org", HttpJsonClient.validateUrl("https://iac.example.org/", false).toString());
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("http://iac.example.org", false));
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("https://token@iac.example.org", false));
  assertThrows(IllegalArgumentException.class, () -> HttpJsonClient.validateUrl("https://iac.example.org/subpath", false));
 }
}
