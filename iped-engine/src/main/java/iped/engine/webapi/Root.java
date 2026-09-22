package iped.engine.webapi;

import java.net.URI;
import java.net.URISyntaxException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("")
public class Root {

    /**
     * Sends the browser to the web interface. The REST API documentation is still
     * available at /swagger.json.
     */
    @GET
    public static Response root() throws URISyntaxException {
        return Response.temporaryRedirect(new URI("." + Main.APP_PATH + "/index.html")).build();
    }
}
