package io.jenkins.plugins.iac.core;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;
import java.util.Map;
import java.util.StringJoiner;
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
  return request(method,path,Map.of(),body);
 }
 /**
  * Sends a request with a query string built from {@code query} in iteration order, each key and value
  * percent-encoded (so a key such as {@code filter[organization]} and a value such as {@code name==acme} are
  * safe). {@code path} must not contain a query, and names inside it should come from {@link #pathSegment}.
  * Query values never appear in exceptions or logs: an {@link IacHttpException} carries only the path.
  */
 public JsonNode request(String method,String path,Map<String,String> query,JsonNode body) throws IOException,InterruptedException {
  HttpRequest.Builder b=HttpRequest.newBuilder(uri(path,query)).timeout(Duration.ofSeconds(30))
    .header("Authorization","Bearer "+token).header("Accept","application/vnd.api+json");
  if(body!=null) b.header("Content-Type","application/vnd.api+json")
                  .method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
  else b.method(method,HttpRequest.BodyPublishers.noBody());
  HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()<200 || response.statusCode()>=300)
    throw new IacHttpException(response.statusCode(),method,path);
  if(response.body().isBlank()) return JSON.createObjectNode();
  try{return JSON.readTree(response.body());}
  catch(IOException e){throw new IOException("IaC API returned malformed JSON",e);}
 }
 URI uri(String path,Map<String,String> query){
  // "//host/..." would resolve to another authority and send the token there.
  if(path==null || !path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#"))
   throw new IllegalArgumentException("Invalid API path");
  for(String segment:path.split("/",-1)){
   String decoded;
   try{decoded=java.net.URLDecoder.decode(segment.replace("+","%2B"),StandardCharsets.UTF_8);}
   catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid API path");}
   if(dotSegments(decoded)) throw new IllegalArgumentException("Invalid API path");
  }
  StringJoiner q=new StringJoiner("&","?","").setEmptyValue("");
  if(query!=null)for(Map.Entry<String,String> e:query.entrySet()){
   if(e.getKey()==null || e.getKey().isEmpty() || e.getValue()==null) throw new IllegalArgumentException("Invalid API query parameter");
   q.add(encode(e.getKey())+"="+encode(e.getValue()));
  }
  return origin.resolve(path+q);
 }
 /**
  * Percent-encodes one path segment, such as a user-supplied name, so that {@code /} and other reserved
  * characters stay inside the segment: {@code "/api/v2/organizations/"+pathSegment(org)+"/workspaces/"+pathSegment(name)}.
  * Rejects null, empty, {@code .} and {@code ..}, and values containing such segments (for example {@code ../x}).
  */
 public static String pathSegment(String value){
  if(value==null || value.isEmpty() || dotSegments(value)) throw new IllegalArgumentException("Invalid API path segment");
  return encode(value);
 }
 /** True if a decoded segment is, or contains between separators, {@code .} or {@code ..}. */
 private static boolean dotSegments(String decoded){
  for(String piece:decoded.split("[/\\\\]",-1)) if(piece.equals(".") || piece.equals("..")) return true;
  return false;
 }
 /** RFC 3986 percent-encoding of UTF-8 bytes; only unreserved characters (letters, digits, -._~) are kept. */
 static String encode(String value){
  StringBuilder b=new StringBuilder();
  for(byte x:value.getBytes(StandardCharsets.UTF_8)){
   int c=x&0xff;
   if((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-'||c=='.'||c=='_'||c=='~') b.append((char)c);
   else b.append('%').append(Character.toUpperCase(Character.forDigit(c>>4,16))).append(Character.toUpperCase(Character.forDigit(c&15,16)));
  }
  return b.toString();
 }
 public static JobResult parse(JsonNode response,java.util.Set<String> done,java.util.Set<String> failed,java.util.Set<String> approval) throws IOException {
  JsonNode data=response.path("data");
  if(!data.isObject() || data.path("id").asText("").isBlank()) throw new IOException("IaC API response lacks data.id");
  String status=data.path("attributes").path("status").asText("unknown").toLowerCase(java.util.Locale.ROOT);
  return new JobResult(data.path("id").asText(),status,done.contains(status)||failed.contains(status),done.contains(status),approval.contains(status));
 }
 @Override public void close(){http.close();}
}
