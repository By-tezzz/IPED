package iped.engine.webapi.json;

import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * A page of search results, with the properties of each item already included,
 * so a client does not need one request per item to render a result list:
 * { "query": "...", "totalHits": 100, "start": 0, "rows": 50, "items": [ ... ] }
 */
@ApiModel(value = "SearchPage")
public class SearchPageJSON {

    private String query;
    private String sourceID;
    private int start;
    private int rows;
    private int totalHits;
    private long took;
    private List<DocPropsJSON> items;

    @ApiModelProperty(value = "The query that was executed")
    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    @ApiModelProperty(value = "Source the search was restricted to, empty if all sources were searched")
    public String getSourceID() {
        return sourceID;
    }

    public void setSourceID(String sourceID) {
        this.sourceID = sourceID;
    }

    @ApiModelProperty(value = "Index of the first returned item in the whole result")
    public int getStart() {
        return start;
    }

    public void setStart(int start) {
        this.start = start;
    }

    @ApiModelProperty(value = "Maximum number of items in this page")
    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    @ApiModelProperty(value = "Total number of items matching the query")
    public int getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(int totalHits) {
        this.totalHits = totalHits;
    }

    @ApiModelProperty(value = "Time spent by the server, in milliseconds")
    public long getTook() {
        return took;
    }

    public void setTook(long took) {
        this.took = took;
    }

    @ApiModelProperty(value = "Items of this page")
    public List<DocPropsJSON> getItems() {
        return items;
    }

    public void setItems(List<DocPropsJSON> items) {
        this.items = items;
    }
}
