/*
 * IPED web interface.
 *
 * Runs on top of the IPED REST API (iped.engine.webapi), which is served at the
 * parent path of this page. No build step and no dependencies: the files are
 * served as they are by the same server that answers the API.
 */
(function () {
    'use strict';

    /* the interface is served at <base>/app/, the API lives at <base>/ */
    var API = new URL('../', window.location.href);

    var PAGE_PROPS = ['name', 'path', 'ext', 'type', 'category', 'contentType', 'size', 'created', 'modified',
        'accessed', 'hash', 'isDir', 'hasChildren', 'parentId', 'deleted', 'carved', 'subitem', 'hasThumb'].join(',');

    var MAX_TEXT_CHARS = 500000;

    var state = {
        mode: 'search', // 'search' (server side paging) or 'ids' (a bookmark or the checked items)
        query: '',
        source: '',
        ids: [], // mode 'ids': every {source, id} of the current filter
        label: 'All items',
        start: 0,
        rows: 50,
        total: 0,
        items: [],
        current: null,
        view: 'list'
    };

    var el = {};

    /* ------------------------------------------------------------------ api */

    function apiUrl(path) {
        return new URL(path, API).href;
    }

    function request(path, options) {
        return fetch(apiUrl(path), options).then(function (response) {
            if (!response.ok) {
                return response.text().then(function (body) {
                    var detail = body ? ': ' + body.split('\n')[0].slice(0, 200) : '';
                    throw new Error(response.status + ' ' + response.statusText + detail);
                });
            }
            return response;
        });
    }

    function getJson(path) {
        return request(path).then(function (r) { return r.json(); });
    }

    function sendJson(path, method, body) {
        return request(path, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    }

    function docPath(item, suffix) {
        return 'sources/' + encodeURIComponent(item.source) + '/docs/' + item.id + (suffix || '');
    }

    /* --------------------------------------------------------------- helpers */

    function prop(item, name) {
        var values = item.properties && item.properties[name];
        return values && values.length ? values[0] : '';
    }

    function isTrue(item, name) {
        return String(prop(item, name)).toLowerCase() === 'true';
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function formatSize(value) {
        var size = parseInt(value, 10);
        if (isNaN(size)) {
            return '';
        }
        var units = ['B', 'KB', 'MB', 'GB', 'TB'];
        var unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size = size / 1024;
            unit++;
        }
        return (unit === 0 ? size : size.toFixed(1)) + ' ' + units[unit];
    }

    function formatDate(value) {
        return value ? String(value).replace('T', ' ').replace('Z', '') : '';
    }

    function toast(message, isError) {
        el.toast.textContent = message;
        el.toast.className = 'toast' + (isError ? ' error' : '');
        el.toast.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(function () { el.toast.hidden = true; }, isError ? 8000 : 3000);
    }

    function fail(error) {
        console.error(error);
        toast(error.message || String(error), true);
    }

    /** turns { data: [ { source, ids: [..] } ] } into [ {source, id}, ... ] */
    function flatten(json) {
        var docs = [];
        (json.data || []).forEach(function (group) {
            (group.ids || []).forEach(function (id) {
                docs.push({ source: group.source, id: id });
            });
        });
        return docs;
    }

    /** items of the current page that are checked in the case */
    function checkedItems() {
        return state.items.filter(function (item) { return item.selected; });
    }

    /* ---------------------------------------------------------------- search */

    function search(query, source) {
        state.mode = 'search';
        state.query = query;
        state.source = source;
        state.start = 0;
        state.label = query ? 'Search results' : 'All items';
        return loadPage();
    }

    function showDocs(docs, label) {
        state.mode = 'ids';
        state.ids = docs;
        state.label = label;
        state.start = 0;
        return loadPage();
    }

    function loadPage() {
        el.resultsBody.innerHTML = '<p class="loading">Searching&hellip;</p>';
        var loader = state.mode === 'search' ? loadSearchPage() : loadIdsPage();
        return loader.then(render).catch(function (error) {
            el.resultsBody.innerHTML = '<p class="placeholder">Nothing to show.</p>';
            fail(error);
        });
    }

    function loadSearchPage() {
        var path = 'search/page?q=' + encodeURIComponent(state.query) + '&sourceID='
            + encodeURIComponent(state.source) + '&start=' + state.start + '&rows=' + state.rows
            + '&props=' + encodeURIComponent(PAGE_PROPS);
        return getJson(path).then(function (page) {
            state.total = page.totalHits;
            state.items = page.items || [];
        });
    }

    function loadIdsPage() {
        state.total = state.ids.length;
        var slice = state.ids.slice(state.start, state.start + state.rows);
        if (!slice.length) {
            state.items = [];
            return Promise.resolve();
        }
        return sendJson('docs/props?props=' + encodeURIComponent(PAGE_PROPS), 'POST', slice)
            .then(function (r) { return r.json(); })
            .then(function (json) { state.items = json.data || []; });
    }

    /* ---------------------------------------------------------------- render */

    function render() {
        el.checkPage.checked = false;

        var first = state.total ? state.start + 1 : 0;
        var last = Math.min(state.start + state.rows, state.total);
        el.hits.textContent = state.label + ': ' + state.total.toLocaleString() + ' item'
            + (state.total === 1 ? '' : 's');
        el.pageInfo.textContent = first.toLocaleString() + ' - ' + last.toLocaleString();
        el.prev.disabled = state.start <= 0;
        el.next.disabled = last >= state.total;

        if (!state.items.length) {
            el.resultsBody.innerHTML = '<p class="placeholder">No items found.</p>';
            return;
        }
        if (state.view === 'gallery') {
            renderGallery();
        } else {
            renderList();
        }
    }

    function renderList() {
        var table = document.createElement('table');
        table.className = 'items';
        table.innerHTML = '<thead><tr>'
            + '<th class="pick"></th><th class="thumb"></th><th>Name</th><th>Path</th>'
            + '<th>Type</th><th class="num">Size</th><th>Modified</th><th>Source</th>'
            + '</tr></thead>';

        var body = document.createElement('tbody');
        state.items.forEach(function (item) {
            var row = document.createElement('tr');
            row.className = isTrue(item, 'deleted') ? 'deleted' : '';
            row.innerHTML = '<td class="pick"></td>'
                + '<td class="thumb"></td>'
                + '<td title="' + escapeHtml(prop(item, 'name')) + '">' + escapeHtml(prop(item, 'name')) + '</td>'
                + '<td title="' + escapeHtml(prop(item, 'path')) + '">' + escapeHtml(prop(item, 'path')) + '</td>'
                + '<td>' + escapeHtml(prop(item, 'type') || prop(item, 'ext')) + '</td>'
                + '<td class="num">' + escapeHtml(formatSize(prop(item, 'size'))) + '</td>'
                + '<td>' + escapeHtml(formatDate(prop(item, 'modified'))) + '</td>'
                + '<td>' + escapeHtml(item.source) + '</td>';

            row.firstChild.appendChild(checkbox(item));
            var thumb = thumbnail(item);
            if (thumb) {
                row.children[1].appendChild(thumb);
            }
            row.addEventListener('click', function () { openItem(item, row); });
            item.row = row;
            body.appendChild(row);
        });

        table.appendChild(body);
        el.resultsBody.innerHTML = '';
        el.resultsBody.appendChild(table);
        highlightCurrent();
    }

    function renderGallery() {
        var grid = document.createElement('div');
        grid.className = 'gallery';
        state.items.forEach(function (item) {
            var card = document.createElement('div');
            card.className = 'card';
            var frame = document.createElement('div');
            frame.className = 'frame';
            var thumb = thumbnail(item);
            if (thumb) {
                frame.appendChild(thumb);
            } else {
                var ext = document.createElement('span');
                ext.className = 'ext';
                ext.textContent = prop(item, 'ext') || '?';
                frame.appendChild(ext);
            }
            var label = document.createElement('div');
            label.className = 'label';
            label.textContent = prop(item, 'name');
            label.title = prop(item, 'path');

            card.appendChild(checkbox(item));
            card.appendChild(frame);
            card.appendChild(label);
            card.addEventListener('click', function () { openItem(item, card); });
            item.row = card;
            grid.appendChild(card);
        });
        el.resultsBody.innerHTML = '';
        el.resultsBody.appendChild(grid);
        highlightCurrent();
    }

    function thumbnail(item) {
        if (!isTrue(item, 'hasThumb')) {
            return null;
        }
        var img = document.createElement('img');
        img.loading = 'lazy';
        img.alt = '';
        img.src = apiUrl(docPath(item, '/thumb'));
        img.addEventListener('error', function () { img.remove(); });
        return img;
    }

    function checkbox(item) {
        var input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = !!item.selected;
        input.title = 'Check item in the case';
        input.addEventListener('click', function (event) {
            event.stopPropagation();
        });
        input.addEventListener('change', function () {
            setChecked([item], input.checked);
        });
        item.checkbox = input;
        return input;
    }

    function highlightCurrent() {
        state.items.forEach(function (item) {
            if (!item.row) {
                return;
            }
            var isCurrent = state.current && item.source === state.current.source && item.id === state.current.id;
            item.row.classList.toggle('current', !!isCurrent);
        });
    }

    /* --------------------------------------------------------------- details */

    function openItem(item, row) {
        state.current = item;
        highlightCurrent();
        if (row && row.scrollIntoView) {
            row.scrollIntoView({ block: 'nearest' });
        }

        document.querySelector('main').classList.add('with-details');
        el.detailsTitle.textContent = prop(item, 'name') || ('item ' + item.id);
        el.detailsTitle.title = prop(item, 'path');
        el.detailsActions.hidden = false;
        el.detailsTabs.hidden = false;
        el.detailsDownload.href = apiUrl(docPath(item, '/content'));
        el.detailsOpen.href = apiUrl(docPath(item, '/preview'));
        el.detailsCheck.checked = !!item.selected;
        renderTab();
    }

    function renderTab() {
        var item = state.current;
        if (!item) {
            return;
        }
        var tab = document.querySelector('.tab.active').dataset.tab;
        if (tab === 'preview') {
            renderPreview(item);
        } else if (tab === 'text') {
            renderText(item);
        } else {
            renderProps(item);
        }
    }

    function renderPreview(item) {
        var type = prop(item, 'contentType') || '';
        var src = apiUrl(docPath(item, '/preview'));
        var size = parseInt(prop(item, 'size'), 10) || 0;
        el.detailsBody.innerHTML = '';

        if (isTrue(item, 'isDir') || size === 0) {
            el.detailsBody.innerHTML = '<p class="placeholder">No content to preview'
                + (isTrue(item, 'isDir') ? ' (folder).' : ' (empty item).') + '</p>';
            return;
        }

        var node;
        if (type.indexOf('image/') === 0) {
            node = document.createElement('img');
            node.src = src;
            node.alt = prop(item, 'name');
        } else if (type.indexOf('video/') === 0) {
            node = document.createElement('video');
            node.src = src;
            node.controls = true;
        } else if (type.indexOf('audio/') === 0) {
            node = document.createElement('audio');
            node.src = src;
            node.controls = true;
        } else if (type.indexOf('text/') === 0 || type === 'application/pdf'
            || type.indexOf('application/xhtml') === 0) {
            /*
             * item content is evidence, so it is never trusted: the server marks the
             * response with "Content-Security-Policy: sandbox" and it is shown inside a
             * sandboxed frame, which keeps its scripts from touching this page.
             */
            node = document.createElement('iframe');
            node.setAttribute('sandbox', '');
            node.src = src;
        } else {
            el.detailsBody.innerHTML = '<p class="placeholder">No inline preview for <code>'
                + escapeHtml(type || 'unknown type') + '</code>. Use the Text tab, or download the item.</p>';
            return;
        }
        el.detailsBody.appendChild(node);
    }

    function renderText(item) {
        el.detailsBody.innerHTML = '<p class="loading">Extracting text&hellip;</p>';
        request(docPath(item, '/text')).then(function (r) { return r.text(); }).then(function (text) {
            if (state.current !== item) {
                return;
            }
            /* the parsers emit a few empty lines before the content, drop them */
            text = text.replace(/^[\s\n]*\n/, '');
            var truncated = text.length > MAX_TEXT_CHARS;
            var shown = truncated ? text.slice(0, MAX_TEXT_CHARS) : text;
            el.detailsBody.innerHTML = shown.trim()
                ? '<pre>' + escapeHtml(shown) + '</pre>'
                + (truncated ? '<p class="placeholder">Text truncated at ' + MAX_TEXT_CHARS
                    + ' characters.</p>' : '')
                : '<p class="placeholder">No text could be extracted from this item.</p>';
        }).catch(function (error) {
            el.detailsBody.innerHTML = '<p class="placeholder">Text could not be extracted: '
                + escapeHtml(error.message) + '</p>';
        });
    }

    function renderProps(item) {
        el.detailsBody.innerHTML = '<p class="loading">Loading properties&hellip;</p>';
        getJson(docPath(item)).then(function (doc) {
            if (state.current !== item) {
                return;
            }
            item.selected = doc.selected;
            item.bookmarks = doc.bookmarks || [];
            el.detailsCheck.checked = !!doc.selected;

            var html = '';
            if (item.bookmarks.length) {
                html += '<div class="chips">' + item.bookmarks.map(function (b) {
                    return '<span class="chip">' + escapeHtml(b) + '</span>';
                }).join('') + '</div><br>';
            }
            html += '<table class="props"><tbody>';
            html += '<tr><th>source</th><td>' + escapeHtml(doc.source) + '</td></tr>';
            html += '<tr><th>id</th><td>' + doc.id + '</td></tr>';
            Object.keys(doc.properties || {}).sort().forEach(function (key) {
                if (key === 'content' || key === 'thumbnail') {
                    return; /* indexed text and binary thumbnail are not shown here */
                }
                var value = (doc.properties[key] || []).join(', ');
                html += '<tr><th>' + escapeHtml(key) + '</th><td>' + escapeHtml(value) + '</td></tr>';
            });
            html += '</tbody></table>';
            el.detailsBody.innerHTML = html;
        }).catch(fail);
    }

    /* ----------------------------------------------- selection and bookmarks */

    function setChecked(docs, checked) {
        if (!docs.length) {
            toast('No item to check.');
            return Promise.resolve();
        }
        var body = docs.map(function (item) { return { source: item.source, id: item.id }; });
        return sendJson('selection/' + (checked ? 'add' : 'remove'), 'PUT', body).then(function () {
            docs.forEach(function (item) {
                item.selected = checked;
                if (item.checkbox) {
                    item.checkbox.checked = checked;
                }
            });
            if (state.current) {
                el.detailsCheck.checked = !!state.current.selected;
            }
            toast(docs.length + ' item(s) ' + (checked ? 'checked' : 'unchecked') + ' in the case');
        }).catch(function (error) {
            /* the case was not changed, so put the boxes back where they were */
            docs.forEach(function (item) {
                if (item.checkbox) {
                    item.checkbox.checked = !!item.selected;
                }
            });
            el.checkPage.checked = false;
            fail(error);
        });
    }

    function bookmarkDocs(name, docs, add) {
        if (!name) {
            toast('Choose a bookmark first.');
            return;
        }
        if (!docs.length) {
            toast('Check the items you want to bookmark first.');
            return;
        }
        var body = docs.map(function (item) { return { source: item.source, id: item.id }; });
        sendJson('bookmarks/' + encodeURIComponent(name) + '/' + (add ? 'add' : 'remove'), 'PUT', body)
            .then(function () {
                toast(docs.length + ' item(s) ' + (add ? 'added to' : 'removed from') + ' "' + name + '"');
                if (state.current) {
                    renderTab();
                }
            }).catch(fail);
    }

    function loadBookmarks() {
        return getJson('bookmarks').then(function (json) {
            var names = (json.data || []).slice().sort();
            el.bookmarks.innerHTML = '';
            if (!names.length) {
                el.bookmarks.innerHTML = '<li class="empty">No bookmarks</li>';
            }
            names.forEach(function (name) {
                var li = document.createElement('li');
                var open = document.createElement('button');
                open.type = 'button';
                open.className = 'entry';
                open.textContent = name;
                open.title = name;
                open.addEventListener('click', function () {
                    activate(open);
                    getJson('bookmarks/' + encodeURIComponent(name))
                        .then(function (json) { showDocs(flatten(json), 'Bookmark "' + name + '"'); })
                        .catch(fail);
                });
                var del = document.createElement('button');
                del.type = 'button';
                del.className = 'del';
                del.textContent = '×';
                del.title = 'Delete bookmark';
                del.addEventListener('click', function (event) {
                    event.stopPropagation();
                    if (!window.confirm('Delete bookmark "' + name + '"? Items are not deleted.')) {
                        return;
                    }
                    request('bookmarks/' + encodeURIComponent(name), { method: 'DELETE' })
                        .then(loadBookmarks).then(function () { toast('Bookmark deleted'); }).catch(fail);
                });
                li.appendChild(open);
                li.appendChild(del);
                el.bookmarks.appendChild(li);
            });

            var selected = el.bookmarkAction.value;
            el.bookmarkAction.innerHTML = '<option value="">Bookmark&hellip;</option>';
            names.forEach(function (name) {
                var option = document.createElement('option');
                option.value = name;
                option.textContent = name;
                el.bookmarkAction.appendChild(option);
            });
            el.bookmarkAction.value = names.indexOf(selected) >= 0 ? selected : '';
        }).catch(fail);
    }

    /* ------------------------------------------------------------------ init */

    function activate(button) {
        document.querySelectorAll('.sidebar .entry.active').forEach(function (other) {
            other.classList.remove('active');
        });
        if (button) {
            button.classList.add('active');
        }
    }

    function loadSources() {
        return getJson('sources').then(function (json) {
            (json.data || []).forEach(function (source) {
                var option = document.createElement('option');
                option.value = source.id;
                option.textContent = source.id;
                option.title = source.path;
                el.source.appendChild(option);
            });
        }).catch(fail);
    }

    function loadCategories() {
        return getJson('categories').then(function (json) {
            var names = (json.data || []).slice().sort();
            el.categories.innerHTML = '';
            if (!names.length) {
                el.categories.innerHTML = '<li class="empty">No categories</li>';
            }
            names.forEach(function (name) {
                var li = document.createElement('li');
                var button = document.createElement('button');
                button.type = 'button';
                button.className = 'entry';
                button.textContent = name;
                button.title = name;
                button.addEventListener('click', function () {
                    activate(button);
                    var query = 'category:"' + name.replace(/"/g, '\\"') + '"';
                    el.query.value = query;
                    search(query, el.source.value).then(function () {
                        state.label = name;
                        el.hits.textContent = name + ': ' + state.total.toLocaleString() + ' items';
                    });
                });
                li.appendChild(button);
                el.categories.appendChild(li);
            });
        }).catch(fail);
    }

    function loadFields() {
        getJson('properties').then(function (json) {
            el.fieldList.textContent = (json.data || []).join('  ');
        }).catch(function () {
            el.fieldList.textContent = 'Property list unavailable.';
        });
    }

    function moveCurrent(offset) {
        if (!state.items.length) {
            return;
        }
        var index = state.items.indexOf(state.current);
        var next = index < 0 ? 0 : index + offset;
        if (next >= 0 && next < state.items.length) {
            openItem(state.items[next], state.items[next].row);
        }
    }

    function bind() {
        el.query = document.getElementById('query');
        el.source = document.getElementById('source');
        el.hits = document.getElementById('hits');
        el.pageInfo = document.getElementById('page-info');
        el.prev = document.getElementById('prev');
        el.next = document.getElementById('next');
        el.rows = document.getElementById('rows');
        el.resultsBody = document.getElementById('results-body');
        el.bookmarks = document.getElementById('bookmarks');
        el.categories = document.getElementById('categories');
        el.bookmarkAction = document.getElementById('bookmark-action');
        el.checkPage = document.getElementById('check-page');
        el.detailsTitle = document.getElementById('details-title');
        el.detailsBody = document.getElementById('details-body');
        el.detailsActions = document.getElementById('details-actions');
        el.detailsTabs = document.getElementById('details-tabs');
        el.detailsDownload = document.getElementById('details-download');
        el.detailsOpen = document.getElementById('details-open');
        el.detailsCheck = document.getElementById('details-check');
        el.fieldList = document.getElementById('field-list');
        el.toast = document.getElementById('toast');

        document.getElementById('search-form').addEventListener('submit', function (event) {
            event.preventDefault();
            activate(document.querySelector('[data-filter="all"]'));
            search(el.query.value.trim(), el.source.value).catch(fail);
        });

        el.source.addEventListener('change', function () {
            if (state.mode === 'search') {
                search(state.query, el.source.value).catch(fail);
            }
        });

        el.prev.addEventListener('click', function () {
            state.start = Math.max(0, state.start - state.rows);
            loadPage();
        });
        el.next.addEventListener('click', function () {
            if (state.start + state.rows < state.total) {
                state.start += state.rows;
                loadPage();
            }
        });
        el.rows.addEventListener('change', function () {
            state.rows = parseInt(el.rows.value, 10);
            state.start = 0;
            loadPage();
        });

        document.getElementById('view-list').addEventListener('click', function () {
            setView('list', this);
        });
        document.getElementById('view-gallery').addEventListener('click', function () {
            setView('gallery', this);
        });

        el.checkPage.addEventListener('change', function () {
            setChecked(state.items, el.checkPage.checked);
        });

        document.getElementById('bookmark-add').addEventListener('click', function () {
            bookmarkDocs(el.bookmarkAction.value, checkedItems(), true);
        });
        document.getElementById('bookmark-remove').addEventListener('click', function () {
            bookmarkDocs(el.bookmarkAction.value, checkedItems(), false);
        });
        document.getElementById('new-bookmark').addEventListener('click', function () {
            var name = window.prompt('New bookmark name');
            if (!name) {
                return;
            }
            request('bookmarks/' + encodeURIComponent(name.trim()), { method: 'POST' })
                .then(loadBookmarks).then(function () { toast('Bookmark created'); }).catch(fail);
        });

        document.querySelectorAll('[data-filter]').forEach(function (button) {
            button.addEventListener('click', function () {
                activate(button);
                if (button.dataset.filter === 'all') {
                    el.query.value = '';
                    search('', el.source.value).catch(fail);
                } else {
                    getJson('selection')
                        .then(function (json) { showDocs(flatten(json), 'Checked items'); })
                        .catch(fail);
                }
            });
        });

        document.querySelectorAll('.tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                document.querySelectorAll('.tab').forEach(function (other) {
                    other.classList.remove('active');
                });
                tab.classList.add('active');
                renderTab();
            });
        });

        el.detailsCheck.addEventListener('change', function () {
            if (state.current) {
                setChecked([state.current], el.detailsCheck.checked);
            }
        });

        document.getElementById('details-close').addEventListener('click', function () {
            document.querySelector('main').classList.remove('with-details');
            state.current = null;
            highlightCurrent();
        });

        var help = document.getElementById('help-dialog');
        document.getElementById('help-button').addEventListener('click', function () {
            loadFields();
            help.showModal();
        });

        document.addEventListener('keydown', function (event) {
            var typing = /^(INPUT|SELECT|TEXTAREA)$/.test(event.target.tagName);
            if (typing || event.ctrlKey || event.metaKey || event.altKey) {
                return;
            }
            if (event.key === 'ArrowDown' || event.key === 'j') {
                event.preventDefault();
                moveCurrent(1);
            } else if (event.key === 'ArrowUp' || event.key === 'k') {
                event.preventDefault();
                moveCurrent(-1);
            } else if (event.key === '/') {
                event.preventDefault();
                el.query.focus();
                el.query.select();
            }
        });
    }

    function setView(view, button) {
        state.view = view;
        document.querySelectorAll('.viewswitch button').forEach(function (other) {
            other.classList.remove('active');
        });
        button.classList.add('active');
        render();
    }

    function start() {
        bind();
        state.rows = parseInt(el.rows.value, 10);
        loadSources().then(loadBookmarks).then(loadCategories).then(function () {
            return search('', '');
        }).catch(fail);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
