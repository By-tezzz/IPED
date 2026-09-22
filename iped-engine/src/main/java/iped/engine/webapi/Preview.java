package iped.engine.webapi;

import java.io.IOException;
import java.io.OutputStream;

import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import iped.data.IIPEDSource;
import iped.data.IItem;
import iped.io.SeekableInputStream;

/**
 * Serves the raw content of an item with its real media type, so a browser can
 * render it (image, audio, video, pdf, plain text...) instead of downloading
 * it, as {@link Content} does.
 *
 * Item content is evidence, i.e. untrusted data: an HTML or SVG item could try
 * to run script in the context of this application. Responses carry
 * "Content-Security-Policy: sandbox" and "X-Content-Type-Options: nosniff", so
 * the browser treats them as an opaque origin with scripting disabled. Clients
 * should also display them inside a sandboxed iframe.
 */
@Api(value = "Documents")
@Path("sources/{sourceID}/docs/{id}/preview")
public class Preview {

    private static final String DEFAULT_TYPE = "application/octet-stream";

    @ApiOperation(value = "Get document's content to be displayed inline, supporting range requests")
    @GET
    public Response preview(@PathParam("sourceID") String sourceID, @PathParam("id") int id,
            @HeaderParam("Range") String range) throws IOException {

        IIPEDSource source = Sources.getSource(sourceID);
        final IItem item = source.getItemByID(id);
        if (item == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String mediaType = item.getMediaType() != null ? item.getMediaType().toString() : DEFAULT_TYPE;
        Long length = item.getLength();

        long[] interval = length == null ? null : parseRange(range, length.longValue());
        if (interval == null) {
            Response.ResponseBuilder builder = Response.ok().type(mediaType);
            if (length != null) {
                builder.header("Content-Length", length);
            }
            return addCommonHeaders(builder, item).entity(stream(item, 0, length == null ? -1 : length)).build();
        }

        long from = interval[0];
        long to = interval[1];
        if (from >= length) {
            return addCommonHeaders(Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE), item)
                    .header("Content-Range", "bytes */" + length).build();
        }
        long count = to - from + 1;
        return addCommonHeaders(Response.status(Response.Status.PARTIAL_CONTENT).type(mediaType), item)
                .header("Content-Range", "bytes " + from + "-" + to + "/" + length).header("Content-Length", count)
                .entity(stream(item, from, count)).build();
    }

    private static Response.ResponseBuilder addCommonHeaders(Response.ResponseBuilder builder, IItem item) {
        return builder.header("Accept-Ranges", "bytes").header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Content-Disposition", "inline; filename=\"" + sanitize(item.getName()) + "\"");
    }

    /**
     * @return {first, last} byte positions, or null if the whole item was asked
     */
    private static long[] parseRange(String range, long length) {
        if (range == null || !range.startsWith("bytes=")) {
            return null;
        }
        // only a single range is supported, which is what browsers use for media
        String value = range.substring("bytes=".length()).trim();
        if (value.isEmpty() || value.indexOf(',') >= 0) {
            return null;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startStr = value.substring(0, dash).trim();
        String endStr = value.substring(dash + 1).trim();
        try {
            long from;
            long to;
            if (startStr.isEmpty()) {
                // suffix range: last N bytes
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) {
                    return null;
                }
                from = Math.max(0, length - suffix);
                to = length - 1;
            } else {
                from = Long.parseLong(startStr);
                if (from < 0) {
                    return null;
                }
                if (from >= length) {
                    // asks for bytes past the end: answered with 416 by the caller
                    return new long[] { from, length };
                }
                to = endStr.isEmpty() ? length - 1 : Math.min(Long.parseLong(endStr), length - 1);
            }
            if (to < from) {
                return null;
            }
            return new long[] { from, to };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static StreamingOutput stream(final IItem item, final long from, final long count) {
        return new StreamingOutput() {
            @Override
            public void write(OutputStream out) throws IOException, WebApplicationException {
                if (from == 0) {
                    try (java.io.InputStream is = item.getBufferedInputStream()) {
                        if (count < 0) {
                            IOUtils.copy(is, out);
                        } else {
                            IOUtils.copyLarge(is, out, 0, count);
                        }
                    }
                } else {
                    try (SeekableInputStream is = item.getSeekableInputStream()) {
                        is.seek(from);
                        IOUtils.copyLarge(is, out, 0, count);
                    }
                }
            }
        };
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "item";
        }
        return name.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
