package iped.engine.webapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import iped.engine.webapi.json.DataListJSON;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.DocPropsJSON;

/**
 * Properties of several documents at once, so listing items that did not come
 * from a search (a bookmark or the selection, for instance) does not need one
 * request per item.
 */
@Api(value = "Documents")
@Path("docs")
public class DocsProps {

    /**
     * Limits the size of a batch, matching the maximum page size of a search.
     */
    private static final int MAX_DOCS = Search.MAX_ROWS;

    @ApiOperation(value = "Get properties of a list of documents")
    @POST
    @Path("props")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<DocPropsJSON> props(@QueryParam("props") String props,
            @ApiParam(required = true) DocIDJSON[] docs) throws Exception {

        if (docs == null) {
            throw new WebApplicationException("missing document list", Response.Status.BAD_REQUEST);
        }
        if (docs.length > MAX_DOCS) {
            throw new WebApplicationException("at most " + MAX_DOCS + " documents per request",
                    Response.Status.BAD_REQUEST);
        }

        Set<String> fields = Search.parseProps(props);
        List<DocPropsJSON> result = new ArrayList<DocPropsJSON>(docs.length);
        for (DocIDJSON doc : docs) {
            Integer sourceId = Sources.sourceStringToInt.get(doc.getSource());
            if (sourceId == null) {
                throw new WebApplicationException("unknown sourceID: " + doc.getSource(), Response.Status.NOT_FOUND);
            }
            result.add(Search.getProps(sourceId.intValue(), doc.getId(), fields));
        }
        return new DataListJSON<DocPropsJSON>(result);
    }
}
