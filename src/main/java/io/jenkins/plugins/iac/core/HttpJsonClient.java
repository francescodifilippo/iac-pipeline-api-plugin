package io.jenkins.plugins.iac.core;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
/** Small, auditable HTTP JSON:API transport, never logs tokens or server bodies. */
public final class HttpJsonClient implements AutoCloseable {
 public static final ObjectMapper JSON=new ObjectMapper();
 private final URI origin;
 private final String token;
 private final HttpClient http;
 public HttpJsonClient(String url,String token,boolean allowHttp){
   origin=validateUrl(url,allowHttp);
   if(token==null || token.isBlank() || token.indexOf('\r')>=0 || token.indexOf('\n')>=0)
      throw new IllegalArgumentException("Invalid secret token");
   this.token=token;
   http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 public static URI validateUrl(String value,boolean allowHttp){
  if(value==null) throw new IllegalArgumentException("URL is required");
  URI u=URI.create(value.trim());
  boolean secure="https".equalsIgnoreCase(u.getScheme());
  if((!secure && !(allowHttp && "http".equalsIgnoreCase(u.getScheme()))) || u.getHost()==null
      || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null || !u.getPath().matches("/?"))
    throw new IllegalArgumentException("Expected https://server origin; HTTP requires explicit opt-in");
  return URI.create(u.toString().replaceAll("/+$",""));
 }
 public JsonNode request(String method,String path,JsonNode body) throws IOException,InterruptedException {
  if(!path.startsWith("/") || path.contains("..") || path.contains("?")) throw new IllegalArgumentException("Invalid API path");
  HttpRequest.Builder b=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(30))
    .header("Authorization","Bearer "+token).header("Accept","application/vnd.api+json");
  if(body!=null) b.header("Content-Type","application/vnd.api+json")
                  .method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
  else b.method(method,HttpRequest.BodyPublishers.noBody());
  HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()<200 || response.statusCode()>=300)
    throw new IOException("IaC API HTTP "+response.statusCode()+" for "+method+" "+path);
  if(response.body().isBlank()) return JSON.createObjectNode();
  try{return JSON.readTree(response.body());}
  catch(IOException e){throw new IOException("IaC API returned malformed JSON",e);}
 }
 public static JobResult parse(JsonNode response,java.util.Set<String> done,java.util.Set<String> failed,java.util.Set<String> approval) throws IOException {
  JsonNode data=response.path("data");
  if(!data.isObject() || data.path("id").asText("").isBlank()) throw new IOException("IaC API response lacks data.id");
  String status=data.path("attributes").path("status").asText("unknown").toLowerCase(java.util.Locale.ROOT);
  return new JobResult(data.path("id").asText(),status,done.contains(status)||failed.contains(status),done.contains(status),approval.contains(status));
 }
 @Override public void close(){}
}
