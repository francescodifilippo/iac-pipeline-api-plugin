package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
class HttpJsonClientTest {
 @Test void encodesRfc3986() {
  assertEquals("filter%5Bworkspace%5D",HttpJsonClient.encode("filter[workspace]"));
  assertEquals("name%3D%3Dmy%20ws",HttpJsonClient.encode("name==my ws"));
  assertEquals("caf%C3%A9%2F%C3%BC%E2%82%AC",HttpJsonClient.encode("café/ü€"));
  assertEquals("Az09-._~",HttpJsonClient.encode("Az09-._~"));
  assertEquals("%2B%2A%26%3F%23%25%27",HttpJsonClient.encode("+*&?#%'"));
 }
 @Test void buildsQueryString() {
  try(HttpJsonClient c=new HttpJsonClient("https://iac.example.org","t",false)){
   assertEquals("https://iac.example.org/api/v1/organization?filter%5Borganization%5D=name%3D%3Dacme",
     c.uri("/api/v1/organization",Map.of("filter[organization]","name==acme")).toString());
   Map<String,String> q=new LinkedHashMap<>();q.put("filter[workspace]","name==my ws");q.put("page[size]","2");
   assertEquals("https://iac.example.org/api/v1/organization/o-1/workspace?filter%5Bworkspace%5D=name%3D%3Dmy%20ws&page%5Bsize%5D=2",
     c.uri("/api/v1/organization/o-1/workspace",q).toString());
   assertEquals("https://iac.example.org/api/v2/runs/run-1",c.uri("/api/v2/runs/run-1",Map.of()).toString());
   assertEquals("https://iac.example.org/api/v2/runs/run-1",c.uri("/api/v2/runs/run-1",null).toString());
   Map<String,String> nullValue=new LinkedHashMap<>();nullValue.put("a",null);
   assertThrows(IllegalArgumentException.class,()->c.uri("/a",nullValue));
   assertThrows(IllegalArgumentException.class,()->c.uri("/a",Map.of("","x")));
  }
 }
 @Test void encodesPathSegments() {
  assertEquals("my%20ws",HttpJsonClient.pathSegment("my ws"));
  assertEquals("a%2Fb",HttpJsonClient.pathSegment("a/b"));
  assertEquals("%C3%A9quipe",HttpJsonClient.pathSegment("équipe"));
  assertEquals("my..ws",HttpJsonClient.pathSegment("my..ws"));
  for(String bad:new String[]{null,"",".","..","../admin","a/../b","a\\..","./"})
   assertThrows(IllegalArgumentException.class,()->HttpJsonClient.pathSegment(bad),String.valueOf(bad));
  try(HttpJsonClient c=new HttpJsonClient("https://iac.example.org","t",false)){
   assertEquals("https://iac.example.org/api/v2/organizations/acme%20corp/workspaces/my..ws",
     c.uri("/api/v2/organizations/"+HttpJsonClient.pathSegment("acme corp")+"/workspaces/"+HttpJsonClient.pathSegment("my..ws"),null).toString());
  }
 }
 @Test void rejectsUnsafePaths() {
  try(HttpJsonClient c=new HttpJsonClient("https://iac.example.org","t",false)){
   for(String bad:new String[]{null,"api","/a/../b","/a/..","/a/./b","/a/%2e%2E/b","/a/..%2Fb","/a/%2E%2E%5Cb","/a?x=1","/a#f","/a/%zz","/a/%"})
    assertThrows(IllegalArgumentException.class,()->c.uri(bad,null),String.valueOf(bad));
   assertEquals("https://iac.example.org/a//b",c.uri("/a//b",null).toString());
  }
 }
 @Test void sendsEncodedRequestAndKeepsQueryOutOfErrors() throws Exception {
  List<String> seen=new CopyOnWriteArrayList<>();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{
   seen.add(x.getRequestURI().getRawPath()+" "+x.getRequestURI().getRawQuery()+" "+x.getRequestURI().getQuery());
   byte[] body="{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
   int code=x.getRequestURI().getPath().endsWith("/missing")?404:200;
   x.sendResponseHeaders(code,body.length);x.getResponseBody().write(body);x.close();
  });
  server.start();
  try(HttpJsonClient c=new HttpJsonClient("http://127.0.0.1:"+server.getAddress().getPort(),"t",true)){
   String path="/api/v1/organization/"+HttpJsonClient.pathSegment("org/1")+"/workspace";
   assertTrue(c.request("GET",path,Map.of("filter[workspace]","name==my ws"),null).path("data").isArray());
   assertEquals("/api/v1/organization/org%2F1/workspace filter%5Bworkspace%5D=name%3D%3Dmy%20ws filter[workspace]=name==my ws",seen.get(0));
   c.request("GET","/api/v2/runs/run-1",null);
   assertEquals("/api/v2/runs/run-1 null null",seen.get(1));
   IacHttpException e=assertThrows(IacHttpException.class,()->c.request("GET","/api/v1/missing",Map.of("filter[organization]","name==acme"),null));
   assertEquals(404,e.statusCode());
   assertEquals("IaC API HTTP 404 for GET /api/v1/missing",e.getMessage());
  }finally{server.stop(0);}
 }
}
