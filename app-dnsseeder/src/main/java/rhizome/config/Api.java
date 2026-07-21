package rhizome.config;

import io.activej.eventloop.Eventloop;
import io.activej.http.AsyncServlet;
import io.activej.http.HttpResponse;
import io.activej.http.HttpServer;
import io.activej.http.RoutingServlet;
import io.activej.inject.annotation.Eager;
import io.activej.inject.annotation.Provides;
import io.activej.inject.module.AbstractModule;
import lombok.extern.slf4j.Slf4j;

import static io.activej.http.HttpMethod.GET;

@Slf4j
public class Api extends AbstractModule {

    private static final int PORT = 8080;

    public static Api create() {
        return new Api();
    }

    /** Minimal HTML escaping so a reflected query parameter cannot inject markup/script (audit L3). */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @Provides
    AsyncServlet servlet(Eventloop eventloop) {
        log.info("HTTP Server is now available at http://localhost:" + PORT);
        return RoutingServlet.builder(eventloop)
            .with(GET, "/hello", request -> HttpResponse.ok200()
                .withHtml("<h1><center>Hello from GET, " + escapeHtml(request.getQueryParameter("name"))
                    + "!</center></h1>")
                .toPromise())
            .build();
    }

    @Provides
    @Eager
    HttpServer server(Eventloop eventloop, AsyncServlet servlet) {
        return HttpServer.builder(eventloop, servlet)
            .withListenPort(PORT)
            .build();
    }
}
