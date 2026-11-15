import './style.css';
import {context, trace, propagation, SpanKind, SpanStatusCode} from '@opentelemetry/api';
import {WebTracerProvider} from '@opentelemetry/sdk-trace-web';
import {BatchSpanProcessor, AlwaysOnSampler} from '@opentelemetry/sdk-trace-base';
import {resourceFromAttributes} from '@opentelemetry/resources';
import {W3CTraceContextPropagator} from '@opentelemetry/core';
import {OTLPTraceExporter} from '@opentelemetry/exporter-trace-otlp-proto';
const provider=new WebTracerProvider({
 resource:resourceFromAttributes({'service.name':'web-frontend','service.version':'1.0.0'}),
 sampler:new AlwaysOnSampler(),
 spanProcessors:[new BatchSpanProcessor(new OTLPTraceExporter({url:location.origin+'/otel/v1/traces'}),{scheduledDelayMillis:500})]
});
provider.register({propagator:new W3CTraceContextPropagator()});
const tracer=trace.getTracer('demo.browser');
const $=s=>document.querySelector(s);
for(const button of document.querySelectorAll('button')) button.onclick=async()=>{
 const scenario=button.dataset.scenario;
 const span=tracer.startSpan('POST /api/orders',{kind:SpanKind.CLIENT,attributes:{'demo.scenario':scenario,'http.request.method':'POST','server.address':location.hostname}});
 // Explicit context is intentional: it remains correct across await without Zone.js.
 const ctx=trace.setSpan(context.active(),span), headers={};
 propagation.inject(ctx,headers);
 const traceId=span.spanContext().traceId, start=performance.now();
 document.querySelectorAll('button').forEach(b=>b.disabled=true);
 $('#status').textContent='请求处理中…'; $('#trace').hidden=true;
 try {
  const res=await fetch('/api/orders?scenario='+scenario,{method:'POST',headers});
  span.setAttribute('http.response.status_code',res.status);
  const body=await res.json();
  if(!res.ok) span.setStatus({code:SpanStatusCode.ERROR,message:body.error||'HTTP '+res.status});
  if(body.traceId!==traceId) throw new Error('Trace ID 不一致，请检查 Java agent 是否启用');
  $('#status').textContent=`HTTP ${res.status} · ${Math.round(performance.now()-start)} ms · Trace ${traceId}`;
  $('#result').textContent=JSON.stringify(body,null,2);
 } catch(error) {
  span.recordException(error); span.setStatus({code:SpanStatusCode.ERROR,message:error.message});
  $('#status').textContent='请求失败：'+error.message; $('#result').textContent=String(error);
 } finally {
  span.end();
  try {await provider.forceFlush();} catch(e) {$('#status').textContent+=' · 前端 span 导出失败，请检查 /otel/v1/traces';}
  try {const base=new URL($('#jaeger').value); if(!['http:','https:'].includes(base.protocol)) throw Error(); $('#trace').href=base.href.replace(/\/$/,'')+'/trace/'+traceId;$('#trace').hidden=false;} catch {$('#status').textContent+=' · Jaeger 地址无效';}
  document.querySelectorAll('button').forEach(b=>b.disabled=false);
 }
};
