package demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opencensus.common.Scope;
import io.opencensus.exporter.trace.jaeger.JaegerTraceExporter;
import io.opencensus.exporter.trace.logging.LoggingTraceExporter;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.samplers.Samplers;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final int DEFAULT_FIRST_PORT = 8080;
    private static final int DEFAULT_SECOND_PORT = 8081;

    private int firstPort = -1;
    private int secondPort = -1;

    private static final Tracer tracer = Tracing.getTracer();
    
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
            try (Scope ss = tracer.spanBuilder("first").setRecordEvents(true).setSampler(Samplers.alwaysSample()).startScopedSpan()) {
                // TODO: propagate the context to the second server
                WebClient.create(vertx).get(secondPort, "localhost", "/").send(ar -> {
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
            try (Scope ss = tracer.spanBuilder("second").setRecordEvents(true).setSampler(Samplers.alwaysSample()).startScopedSpan()) {
                event.response().setStatusCode(200).end("World");
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
