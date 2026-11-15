package demo;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Scope;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class InventoryController {
 @GetMapping("/api/inventory")
 public ResponseEntity<?> check(@RequestParam(defaultValue="normal") String scenario) throws InterruptedException {
  Span span=GlobalOpenTelemetry.getTracer("demo.inventory").spanBuilder("inventory.lookup").startSpan();
  try(Scope scope=span.makeCurrent()) {
   span.setAttribute("demo.scenario",scenario); span.setAttribute("product.sku","DEMO-001");
   Thread.sleep("slow".equals(scenario)?1800:80);
   String traceId=span.getSpanContext().getTraceId();
   LoggerFactory.getLogger(getClass()).info("Inventory lookup scenario={} traceId={}",scenario,traceId);
   if("error".equals(scenario)) {
    var failure=new IllegalStateException("Simulated inventory failure");
    span.recordException(failure); span.setStatus(StatusCode.ERROR,failure.getMessage());
    return ResponseEntity.status(503).body(Map.of("error",failure.getMessage(),"traceId",traceId));
   }
   span.setAttribute("inventory.available",true);
   return ResponseEntity.ok(Map.of("sku","DEMO-001","available",true,"traceId",traceId));
  } catch(InterruptedException ex) {Thread.currentThread().interrupt(); span.recordException(ex); span.setStatus(StatusCode.ERROR); throw ex;}
  finally {span.end();}
 }
}
