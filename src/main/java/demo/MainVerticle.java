package demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opencensus.common.Scope;
import io.opencensus.exporter.trace.jaeger.JaegerTraceExporter;
import io.opencensus.exporter.trace.logging.LoggingTraceExporter;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.propagation.SpanContextParseException;
import io.opencensus.trace.propagation.TextFormat;
import io.opencensus.trace.samplers.Samplers;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final int DEFAULT_FIRST_PORT = 8080;
    private static final int DEFAULT_SECOND_PORT = 8081;

    private int firstPort = -1;
    private int secondPort = -1;

    private static final Tracer tracer = Tracing.getTracer();

    private static final TextFormat textFormat = Tracing.getPropagationComponent().getB3Format();
    private static final TextFormat.Setter<HttpRequest<Buffer>> SETTER = new TextFormat.Setter<HttpRequest<Buffer>>() {
        public void put(HttpRequest<Buffer> carrier, String key, String value) {
            carrier.headers().add(key, value);
        }
    };
    private static final TextFormat.Getter<HttpServerRequest> GETTER = new TextFormat.Getter<HttpServerRequest>() {
        public String get(HttpServerRequest carrier, String key) {
            return carrier.headers().get(key);
        }
    };

    public static void main(String[] args) {
        logger.warn("Bootstrapping from the main method. For production purposes, use the Vert.x launcher");
        Vertx.vertx().deployVerticle(new MainVerticle());
    }

    @Override
    public void start() {
        firstPort = config().getInteger("http.port.first", DEFAULT_FIRST_PORT);
        secondPort = config().getInteger("http.port.second", DEFAULT_SECOND_PORT);

        JaegerTraceExporter.createAndRegister("http://127.0.0.1:14268/api/traces", "my-service");
        LoggingTraceExporter.register();

        first();
        second();
    }

    public void first() {
        logger.info("Starting the First HTTP server");

        httpServer(firstPort, (event) -> {
            Span span = tracer.spanBuilder("first").setRecordEvents(true).setSampler(Samplers.alwaysSample()).startSpan();
            try (Scope ignored = tracer.withSpan(span)) {
                HttpRequest<Buffer> b = WebClient.create(vertx).get(secondPort, "localhost", "/");
                textFormat.inject(span.getContext(), b, SETTER);

                b.send(ar -> {
                    if (ar.succeeded()) {
                        HttpResponse<Buffer> response = ar.result();
                        String who = response.body().toString();
                        event.response().setStatusCode(200).end(String.format("Hello %s\n", who));
                    } else {
                        event.response().setStatusCode(500).end();
                    }
                });
            }
        });
    }

    public void second() {
        logger.info("Starting the Second HTTP server");

        httpServer(secondPort, (event) -> {
            try {
                SpanContext spanContext = textFormat.extract(event.request(), GETTER);
                Span span = tracer.spanBuilderWithRemoteParent("second", spanContext).setRecordEvents(true).setSampler(Samplers.alwaysSample()).startSpan();
                try (Scope ignored = tracer.withSpan(span)) {
                    event.response().setStatusCode(200).end("World");
                }
            } catch (SpanContextParseException e) {
                logger.error(e.getMessage(), e);
                event.response().setStatusCode(500).end();
            }
        });
    }

    private void httpServer(int port, Handler<RoutingContext> routeHandler) {
        logger.info("Starting HTTP server at " + port);

        Router router = Router.router(getVertx());
        router.route(HttpMethod.GET, "/").handler(routeHandler);
        getVertx()
                .createHttpServer()
                .requestHandler(router::accept)
                .listen(port, handler -> {
                    if (handler.succeeded()) {
                        logger.info("HTTP Server started at " + port );
                    } else {
                        throw new RuntimeException(String.format(
                                "Could not start HTTP server: %s", handler.cause().getMessage()
                        ));
                    }
                });
    }

}
