package iped.engine.webapi;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import iped.engine.data.IPEDSource;
import iped.engine.webapi.json.DataListJSON;

/**
 * Names of the properties present in the indexes, which are the fields that can
 * be used in queries. Useful to build query helpers and column choosers.
 */
@Api(value = "Properties")
@Path("properties")
public class IndexedProperties {

    @ApiOperation(value = "List indexed properties of all sources")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public DataListJSON<String> get() throws Exception {
        Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (IPEDSource source : Sources.multiSource.getAtomicSources()) {
            for (FieldInfo field : FieldInfos.getMergedFieldInfos(source.getReader())) {
                names.add(field.name);
            }
        }
        return new DataListJSON<String>(new ArrayList<String>(names));
    }
}
