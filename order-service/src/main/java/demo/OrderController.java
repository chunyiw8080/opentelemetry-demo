package demo;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
@RestController
public class OrderController {
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private final ObjectMapper mapper;
 private final String inventoryUrl;
 public OrderController(ObjectMapper mapper,@Value("${INVENTORY_URL:http://localhost:8081}") String inventoryUrl) {this.mapper=mapper;this.inventoryUrl=inventoryUrl;}
 @PostMapping("/api/orders")
 public ResponseEntity<?> order(@RequestParam(defaultValue="normal") String scenario) {
  if(!Set.of("normal","slow","error").contains(scenario)) return ResponseEntity.badRequest().body(Map.of("error","scenario must be normal, slow or error"));
  Span span=GlobalOpenTelemetry.getTracer("demo.order").spanBuilder("order.create").startSpan();
  String traceId=span.getSpanContext().getTraceId();
  try(Scope scope=span.makeCurrent()) {
   span.setAttribute("demo.scenario",scenario);
   // Java agent instruments JDK HttpClient and injects W3C traceparent automatically.
   var request=HttpRequest.newBuilder(URI.create(inventoryUrl+"/api/inventory?scenario="+scenario)).timeout(Duration.ofSeconds(5)).GET().build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());
   var inventory=mapper.readTree(response.body());
   if(response.statusCode()!=200) throw new IllegalStateException("Inventory HTTP "+response.statusCode());
   String orderId=UUID.randomUUID().toString(); span.setAttribute("order.id",orderId);
   LoggerFactory.getLogger(getClass()).info("Created order={} scenario={} traceId={}",orderId,scenario,traceId);
   return ResponseEntity.ok(Map.of("orderId",orderId,"scenario",scenario,"traceId",traceId,"inventory",inventory));
  } catch(Exception ex) {
   if(ex instanceof InterruptedException) Thread.currentThread().interrupt();
   span.recordException(ex); span.setStatus(StatusCode.ERROR,ex.getMessage());
   LoggerFactory.getLogger(getClass()).warn("Order failed scenario={} traceId={}",scenario,traceId,ex);
   return ResponseEntity.status(502).body(Map.of("error",String.valueOf(ex.getMessage()),"scenario",scenario,"traceId",traceId));
  } finally {span.end();}
 }
}
