package iped.engine.webapi;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.StaticHttpHandler;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;

import io.swagger.jaxrs.config.BeanConfig;
import iped.engine.Version;

/**
 * Main class.
 *
 */
public class Main {

    /**
     * Path the web interface is served at.
     */
    public static final String APP_PATH = "/app";

    /**
     * Classpath folder holding the web interface files.
     */
    private static final String APP_RESOURCES = "iped/engine/webapi/webapp/";

    /**
     * System property pointing to a folder with the web interface files, used to
     * develop the interface without rebuilding the jar.
     */
    private static final String APP_DIR_PROPERTY = "iped.webapp.dir";

    /**
     * Starts Grizzly HTTP server exposing JAX-RS resources defined in this
     * application.
     * 
     * @return Grizzly HTTP server.
     * @throws IOException
     * @throws ParseException
     */
    public static HttpServer startServer(String host, int port, String urlToAskSources)
            throws IOException, ParseException {
        Sources.init(urlToAskSources);
        return startServer(host, port);
    }

    /**
     * Same as {@link #startServer(String, int, String)}, but for sources that were
     * already loaded.
     */
    public static HttpServer startServer(String host, int port) throws IOException {
        // create a resource config that scans for JAX-RS resources and providers
        // in gpinf.api package
        String resources = Main.class.getPackageName();
        BeanConfig beanConfig = new BeanConfig();
        beanConfig.setVersion(Version.APP_VERSION);
        beanConfig.setSchemes(new String[] { "http" });
        beanConfig.setBasePath("/");
        beanConfig.setResourcePackage(resources);
        beanConfig.setScan(true);
        final ResourceConfig rc = new ResourceConfig().packages(resources)
                .register(io.swagger.jaxrs.listing.ApiListingResource.class)
                .register(io.swagger.jaxrs.listing.SwaggerSerializers.class);

        // https://stackoverflow.com/questions/26546373/showing-grizzly-exceptions-in-eclipse-console
        Logger l = Logger.getLogger("org.glassfish.grizzly.http.server.HttpHandler");
        l.setLevel(Level.FINE);
        l.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.ALL);
        l.addHandler(ch);

        // create a new instance of grizzly http server
        // exposing the Jersey application at BASE_URI
        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(URI.create("http://" + host + ":" + port), rc,
                false);
        server.getServerConfiguration().addHttpHandler(getAppHandler(), APP_PATH);
        server.start();
        return server;
    }

    /**
     * @return handler serving the web interface, from the folder pointed by the
     *         "iped.webapp.dir" system property if it is set, from the classpath
     *         otherwise.
     */
    private static HttpHandler getAppHandler() {
        String dir = System.getProperty(APP_DIR_PROPERTY);
        if (dir != null && !dir.trim().isEmpty()) {
            File folder = new File(dir);
            if (!folder.isDirectory()) {
                throw new IllegalArgumentException(APP_DIR_PROPERTY + " is not a folder: " + folder);
            }
            System.out.println("Serving web interface from " + folder.getAbsolutePath());
            StaticHttpHandler handler = new StaticHttpHandler(folder.getAbsolutePath());
            handler.setFileCacheEnabled(false);
            return handler;
        }
        return new CLStaticHttpHandler(Main.class.getClassLoader(), APP_RESOURCES);
    }

    /**
     * Main method.
     * 
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = 8080;
        String urlToAskSources = null;
        List<String> cases = new ArrayList<String>();

        for (String arg : args) {
            if (arg.startsWith("--host=")) {
                host = arg.substring("--host=".length());

            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));

            } else if (arg.startsWith("--sources=")) {
                urlToAskSources = arg.substring("--sources=".length());

            } else if (arg.startsWith("--case=")) {
                cases.add(arg.substring("--case=".length()));

            } else {
                printHelp();
                System.exit(-1);
            }
        }
        if (urlToAskSources == null && cases.isEmpty()) {
            System.err.println("missing --sources or --case option");
            printHelp();
            System.exit(-1);
        }
        if (urlToAskSources != null && !cases.isEmpty()) {
            System.err.println("--sources and --case can not be used together");
            printHelp();
            System.exit(-1);
        }

        if (urlToAskSources != null) {
            Sources.init(urlToAskSources);
        } else {
            Sources.init(toSourcesJSON(cases));
        }
        startServer(host, port);

        String url = "http://" + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port + "/";
        System.out.println("IPED web interface started at " + url + "app/");
        System.out.println("REST API docs (swagger) at " + url + "swagger.json");
    }

    /**
     * Builds the source list from case folders given in the command line, using
     * each folder name as the source id.
     */
    @SuppressWarnings("unchecked")
    private static JSONArray toSourcesJSON(List<String> cases) {
        JSONArray array = new JSONArray();
        for (String path : cases) {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                throw new IllegalArgumentException("case folder not found: " + dir.getAbsolutePath());
            }
            File absolute = dir.getAbsoluteFile();
            String id = absolute.getName();
            if (id.isEmpty()) {
                id = absolute.getPath();
            }
            JSONObject source = new JSONObject();
            source.put("id", id);
            source.put("path", absolute.getPath());
            array.add(source);
        }
        return array;
    }

    public static void printHelp() {
        System.out.println("--case=PATH\tcase folder to open, can be repeated; the folder name is used as source id");
        System.out.println("--sources=(URL|Path)\tfile or url with json: [{id, path}...]");
        System.out.println("--host=\t\tdefault:0.0.0.0");
        System.out.println("--port=\t\tdefault:8080");
    }
}
