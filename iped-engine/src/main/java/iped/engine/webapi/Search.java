package iped.engine.webapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import iped.data.IIPEDSource;
import iped.data.IItemId;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.webapi.json.DocIDJSON;
import iped.engine.webapi.json.DocPropsJSON;
import iped.engine.webapi.json.SearchPageJSON;
import iped.engine.webapi.json.SourceToIDsJSON;
import iped.exception.ParseException;
import iped.exception.QueryNodeException;
import iped.properties.BasicProps;
import iped.search.IIPEDSearcher;
import iped.search.IMultiSearchResult;
import iped.search.SearchResult;

@Api(value = "Search")
@Path("search")
public class Search {

    /**
     * Same constant as iped.engine.task.ThumbTask.HAS_THUMB, repeated here to avoid
     * loading a processing task class in the web api.
     */
    static final String HAS_THUMB = "hasThumb";

    /**
     * Properties returned by {@link #doPagedSearch} when the caller does not ask
     * for a specific set. They are enough to render a result list.
     */
    public static final List<String> DEFAULT_PROPS = Collections.unmodifiableList(Arrays.asList(BasicProps.NAME,
            BasicProps.PATH, BasicProps.EXT, BasicProps.TYPE, BasicProps.CATEGORY, BasicProps.CONTENTTYPE,
            BasicProps.LENGTH, BasicProps.CREATED, BasicProps.MODIFIED, BasicProps.ACCESSED, BasicProps.CHANGED,
            BasicProps.HASH, BasicProps.ISDIR, BasicProps.HASCHILD, BasicProps.PARENTID, BasicProps.DELETED,
            BasicProps.CARVED, BasicProps.SUBITEM, HAS_THUMB));

    public static final int MAX_ROWS = 1000;
    private static final int DEFAULT_ROWS = 50;

    /**
     * Searching is done over the whole result set, so paging through it would run
     * the same query again for each page. The last few results are kept to avoid
     * that. Only the ids are kept (8 bytes per hit), never the documents.
     */
    private static final int MAX_CACHED_SEARCHES = 4;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private static final Map<String, CachedSearch> searchCache = new LinkedHashMap<String, CachedSearch>(
            MAX_CACHED_SEARCHES + 1, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedSearch> eldest) {
            return size() > MAX_CACHED_SEARCHES;
        }
    };

    private static class CachedSearch {
        final int[] sourceIds;
        final int[] ids;
        final long time = System.currentTimeMillis();

        CachedSearch(int[] sourceIds, int[] ids) {
            this.sourceIds = sourceIds;
            this.ids = ids;
        }

        int size() {
            return ids.length;
        }
    }

    @DefaultValue("")
    @QueryParam("q")
    String q;
    @DefaultValue("")
    @QueryParam("sourceID")
    String sourceID;

    @ApiOperation(value = "Search documents")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public SourceToIDsJSON doSearch() throws Exception {
        String escapeq = escape(q);
        List<DocIDJSON> docs = new ArrayList<DocIDJSON>();
        if (sourceID.equals("")) {
            IPEDSearcher searcher = newSearcher(Sources.multiSource, escapeq);
            IMultiSearchResult result = searcher.multiSearch();
            for (IItemId id : result.getIterator()) {
                docs.add(new DocIDJSON(Sources.sourceIntToString.get(id.getSourceId()), id.getId()));
            }
        } else {
            IPEDSource source = (IPEDSource) Sources.getSource(sourceID);
            IIPEDSearcher searcher = newSearcher(source, escapeq);
            SearchResult result = searcher.search();
            for (int id : result.getIds()) {
                docs.add(new DocIDJSON(sourceID, id));
            }
        }

        return new SourceToIDsJSON(docs);
    }

    @ApiOperation(value = "Search documents, returning a page of results with the properties of each item")
    @GET
    @Path("page")
    @Produces(MediaType.APPLICATION_JSON)
    public SearchPageJSON doPagedSearch(@QueryParam("q") @DefaultValue("") String query,
            @QueryParam("sourceID") @DefaultValue("") String sourceID,
            @QueryParam("start") @DefaultValue("0") int start, @QueryParam("rows") @DefaultValue("50") int rows,
            @QueryParam("props") String props) throws Exception {

        long begin = System.currentTimeMillis();

        if (start < 0) {
            throw new WebApplicationException("start must not be negative", Response.Status.BAD_REQUEST);
        }
        if (rows <= 0) {
            rows = DEFAULT_ROWS;
        }
        rows = Math.min(rows, MAX_ROWS);

        CachedSearch result = getResult(query, sourceID);
        Set<String> fields = parseProps(props);

        List<DocPropsJSON> items = new ArrayList<DocPropsJSON>();
        int end = Math.min(start + rows, result.size());
        for (int i = start; i < end; i++) {
            items.add(getProps(result.sourceIds[i], result.ids[i], fields));
        }

        SearchPageJSON page = new SearchPageJSON();
        page.setQuery(query);
        page.setSourceID(sourceID);
        page.setStart(start);
        page.setRows(rows);
        page.setTotalHits(result.size());
        page.setItems(items);
        page.setTook(System.currentTimeMillis() - begin);
        return page;
    }

    /**
     * @param props
     *            comma separated property names, or "*" for every stored property
     * @return the fields to load, or null to load all of them
     */
    static Set<String> parseProps(String props) {
        if (props == null || props.trim().isEmpty()) {
            return new HashSet<String>(DEFAULT_PROPS);
        }
        if ("*".equals(props.trim())) {
            return null;
        }
        Set<String> fields = new HashSet<String>();
        for (String prop : props.split(",")) {
            prop = prop.trim();
            if (!prop.isEmpty()) {
                fields.add(prop);
            }
        }
        return fields.isEmpty() ? new HashSet<String>(DEFAULT_PROPS) : fields;
    }

    static DocPropsJSON getProps(int sourceId, int id, Set<String> fields) throws IOException {
        IIPEDSource source = Sources.multiSource.getAtomicSourceBySourceId(sourceId);
        int luceneId = source.getLuceneId(id);
        Document doc = fields == null ? source.getReader().document(luceneId)
                : source.getReader().document(luceneId, fields);

        DocPropsJSON result = new DocPropsJSON();
        result.setSource(Sources.sourceIntToString.get(sourceId));
        result.setId(id);
        result.setLuceneId(luceneId);

        Map<String, String[]> properties = new HashMap<String, String[]>();
        for (IndexableField field : doc.getFields()) {
            properties.put(field.name(), doc.getValues(field.name()));
        }
        result.setProperties(properties);

        result.setBookmarks(source.getBookmarks().getBookmarkList(id));
        result.setSelected(source.getBookmarks().isChecked(id));
        return result;
    }

    private static synchronized CachedSearch getResult(String query, String sourceID) throws Exception {
        String key = sourceID + "\n" + query;
        CachedSearch cached = searchCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.time < CACHE_TTL_MS) {
            return cached;
        }
        cached = runSearch(query, sourceID);
        searchCache.put(key, cached);
        return cached;
    }

    private static CachedSearch runSearch(String query, String sourceID) throws Exception {
        String escapeq = escape(query);
        if (sourceID == null || sourceID.isEmpty()) {
            IPEDSearcher searcher = newSearcher(Sources.multiSource, escapeq);
            IMultiSearchResult result = searcher.multiSearch();
            int size = result.getLength();
            int[] sourceIds = new int[size];
            int[] ids = new int[size];
            for (int i = 0; i < size; i++) {
                IItemId item = result.getItem(i);
                sourceIds[i] = item.getSourceId();
                ids[i] = item.getId();
            }
            return new CachedSearch(sourceIds, ids);
        }

        Integer sourceInt = Sources.sourceStringToInt.get(sourceID);
        if (sourceInt == null) {
            throw new WebApplicationException("unknown sourceID: " + sourceID, Response.Status.NOT_FOUND);
        }
        IPEDSource source = (IPEDSource) Sources.getSource(sourceID);
        SearchResult result = newSearcher(source, escapeq).search();
        int size = result.getLength();
        int[] sourceIds = new int[size];
        int[] ids = new int[size];
        Arrays.fill(sourceIds, sourceInt.intValue());
        for (int i = 0; i < size; i++) {
            ids[i] = result.getId(i);
        }
        return new CachedSearch(sourceIds, ids);
    }

    /**
     * Discards cached results, so following searches see the current index state.
     */
    public static synchronized void clearCache() {
        searchCache.clear();
    }

    /**
     * Builds a searcher, turning a query the user mistyped into a 400 answer with
     * the parser message, instead of the 500 an unchecked exception would give.
     */
    private static IPEDSearcher newSearcher(IPEDSource source, String query) {
        try {
            return new IPEDSearcher(source, query);

        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ParseException || cause instanceof QueryNodeException) {
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_PLAIN).entity("Invalid query: " + cause.getMessage()).build());
            }
            throw e;
        }
    }

    private static String escape(String query) {
        return query == null ? "" : query.replaceAll("/", "\\\\/");
    }
}
