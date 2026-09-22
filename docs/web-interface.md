# IPED web interface

A browser interface for analyzing cases that were already processed by IPED. It runs on top of
IPED's REST API (`iped.engine.webapi`) and is served by the same process, so no extra server,
runtime or build tooling is needed: open a case with `iped-web.sh` and point a browser at it.

It does not replace the desktop analysis interface — it covers the everyday analysis loop
(search, browse, preview, check, bookmark) for people who cannot, or do not want to, run the
Swing application, for instance when the case lives on a remote machine.

## Running it

First process a case as usual, then start the web interface on it:

```
./iped-web.sh --case=/path/to/case
```

Several cases can be opened at once (they behave as a multicase), and host and port can be
changed:

```
./iped-web.sh --case=/cases/case-1 --case=/cases/case-2 --host=127.0.0.1 --port=9000
```

Cases can also be listed in a json file, which is the original way the API was started and is
still supported:

```
./iped-web.sh --sources=/path/to/sources.json      # [ { "id": "case-1", "path": "/cases/case-1" }, ... ]
```

Then open:

- the interface: http://localhost:8080/app/ (http://localhost:8080/ redirects there)
- the REST API documentation: http://localhost:8080/swagger.json

On Windows use `iped-web.bat` with the same options. On Linux, if the release was unpacked
without the executable bit, run it as `sh iped-web.sh`.

## What it does

- **Search** with the same Lucene query syntax as the desktop interface, over all sources or
  one of them, with server side paging.
- **Browse** results as a list with the usual columns, or as a thumbnail gallery.
- **Preview** items in place: images, audio, video (with seeking), PDF, text and HTML.
- **Extracted text** of any item, produced by the same parsers the desktop viewer uses.
- **Properties** of an item, including every indexed property and its bookmarks.
- **Check items** and **bookmark** them; changes are written to the case, so the desktop
  interface sees them (and vice versa).
- **Categories** of the case as a starting point for navigation.
- **Download** the original bytes of any item.

Keyboard: `/` focuses the query box, `↑`/`↓` (or `k`/`j`) move through the results.

## Security

**The server has no authentication and no encryption.** Anyone who can reach the port can read
the whole case. That was already true of the REST API, and serving an interface on top of it
does not change it, so:

- Keep it on a trusted network, or bind it to the loopback interface (`--host=127.0.0.1`) and
  reach it through an SSH tunnel.
- If it must be reachable, put it behind a reverse proxy that terminates TLS and authenticates
  users.

Item content is evidence and is treated as untrusted: the `preview` endpoint answers with
`Content-Security-Policy: sandbox` and `X-Content-Type-Options: nosniff`, and the interface
renders documents inside a sandboxed `iframe`, so a crafted HTML or SVG item cannot run script
in the context of the application.

## Endpoints used by the interface

The interface only uses the REST API, so anything it does can be scripted. Besides the
endpoints that already existed (`/sources`, `/search`, `/sources/{sourceID}/docs/{id}`,
`/content`, `/text`, `/thumb`, `/bookmarks`, `/selection`, `/categories`), it uses:

| Endpoint | Description |
| --- | --- |
| `GET /search/page?q=&sourceID=&start=&rows=&props=` | A page of results with the properties of each item, so listing does not need one request per item. Returns `totalHits`, so it can be paged. `props` is a comma separated list of properties, or `*` for all of them. |
| `POST /docs/props?props=` | Same properties, for an arbitrary list of `{source, id}` documents. Used to list a bookmark or the checked items. |
| `GET /sources/{sourceID}/docs/{id}/preview` | The item's content with its real media type, to be displayed inline, supporting HTTP range requests (needed to seek in audio and video). `/content` still serves it as a download. |
| `GET /properties` | Names of the indexed properties, used by the query helper. |

`/search/page` keeps the last few result sets in memory (ids only) so paging does not run the
same query again.

## Developing the interface

The interface is plain HTML, CSS and JavaScript with no build step, in
`iped-engine/src/main/resources/iped/engine/webapi/webapp`. It is served from the classpath, but
pointing `iped.webapp.dir` at the source folder serves it from disk instead, so a reload is
enough to see a change:

```
java -Diped.webapp.dir=iped-engine/src/main/resources/iped/engine/webapi/webapp \
     -cp "lib/*" iped.engine.webapi.Main --case=/path/to/case
```

## Limitations

- Results come in index order; sorting by column is not implemented yet.
- The tree of items (parent/child navigation) and the graph, map and timeline views of the
  desktop interface are not available.
- Bookmark and selection changes are written by the server process that holds the case open, so
  only one process should have the case open for writing at a time.
