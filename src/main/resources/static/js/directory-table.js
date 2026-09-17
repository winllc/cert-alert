/*
 * Shared wiring for the two directory search tables.
 *
 * DataTables owns paging, ordering, the global search box and the per-column search inputs;
 * all of that is posted as the request body and turned into a query server side. The extra
 * filters this application adds (certificate state, point of contact) are query parameters
 * instead, so changing one just points the table's ajax URL somewhere new and reloads.
 *
 * Presentation is Tabler's: status pills are Tabler badges, the expand affordance is a
 * Tabler icon, and DataTables renders through its Bootstrap 5 integration so its own
 * furniture matches the card it sits in.
 */
(function (window, $) {
    'use strict';

    var MS_PER_DAY = 86400000;

    /**
     * Every table built on this page.
     *
     * Held here rather than hung off the DataTables API object, because $().DataTable()
     * hands back a fresh wrapper on each call - anything attached to one instance is not
     * there on the next.
     */
    var tables = [];

    /** Tabler's light-tint badge colours, one per certificate state. */
    var STATUS_BADGE = {
        VALID: 'bg-green-lt',
        EXPIRING_SOON: 'bg-yellow-lt',
        EXPIRED: 'bg-red-lt',
        NONE: 'bg-secondary-lt'
    };

    // Every request the tables make is a POST, so each one carries the CSRF token the
    // server rendered into the page. Done once here rather than per call site.
    $(function () {
        var token = $('meta[name="_csrf"]').attr('content');
        var header = $('meta[name="_csrf_header"]').attr('content');
        if (token && header) {
            $.ajaxSetup({
                beforeSend: function (xhr) {
                    xhr.setRequestHeader(header, token);
                }
            });
        }
    });

    /**
     * A 401 means the session lapsed while the page stayed open. Sending them to the login
     * form beats leaving a table that silently stops updating.
     */
    function handleUnauthorized(xhr) {
        if (xhr.status === 401 || xhr.status === 403) {
            window.location.href = '/login';
            return true;
        }
        return false;
    }

    function escapeHtml(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function text(value, fallback) {
        if (value === null || value === undefined || value === '') {
            return '<span class="text-secondary">' + escapeHtml(fallback || '—') + '</span>';
        }
        return escapeHtml(value);
    }

    function icon(name, extraClass) {
        return '<svg class="icon ' + (extraClass || '') + '"><use href="#icon-' + name + '"></use></svg>';
    }

    /** Dates render as plain UTC days: the audience cares about the day, not the second. */
    function formatDate(iso) {
        if (!iso) {
            return null;
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? null : parsed.toISOString().slice(0, 10);
    }

    function daysUntil(iso) {
        if (!iso) {
            return null;
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? null : Math.floor((parsed.getTime() - Date.now()) / MS_PER_DAY);
    }

    function statusBadge(status) {
        var key = status || 'NONE';
        var label = key.replace('_', ' ').toLowerCase();
        return '<span class="badge ' + (STATUS_BADGE[key] || 'bg-secondary-lt') + '">'
            + escapeHtml(label.charAt(0).toUpperCase() + label.slice(1)) + '</span>';
    }

    /** Expiry date plus how long is left, which is what people actually scan the column for. */
    function expiryCell(iso) {
        var formatted = formatDate(iso);
        if (!formatted) {
            return '<span class="text-secondary">—</span>';
        }
        var days = daysUntil(iso);
        var suffix = '';
        if (days !== null) {
            suffix = days < 0
                ? '<span class="days">' + Math.abs(days) + 'd ago</span>'
                : '<span class="days">in ' + days + 'd</span>';
        }
        return escapeHtml(formatted) + suffix;
    }

    function debounce(fn, wait) {
        var timer = null;
        return function () {
            var context = this;
            var args = arguments;
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
                fn.apply(context, args);
            }, wait);
        };
    }

    function buildUrl(endpoint, filters) {
        var params = new URLSearchParams();
        Object.keys(filters).forEach(function (key) {
            var value = filters[key];
            if (value !== null && value !== undefined && value !== '') {
                params.append(key, value);
            }
        });
        var query = params.toString();
        return query ? endpoint + '?' + query : endpoint;
    }

    /** Re-points a built table at a new filter set. */
    function reload(table, filters) {
        var entry = tables.find(function (candidate) {
            return candidate.table === table;
        });
        table.ajax.url(buildUrl(entry ? entry.endpoint : '', filters)).load();
    }

    /** Re-reads every table on the page and its roll-up, after something changed the data. */
    function refreshAll() {
        tables.forEach(function (entry) {
            entry.table.ajax.reload(null, false);
            if (entry.statsUrl) {
                loadStats(entry.statsUrl);
            }
        });
    }

    /** Renders the applied-filter summary shown beside the filter controls. */
    function describeFilters(filters) {
        var keys = Object.keys(filters);
        if (keys.length === 0) {
            return 'No extra filters';
        }
        return keys.map(function (key) {
            return '<span class="badge bg-blue-lt ms-1">' + escapeHtml(key + '=' + filters[key]) + '</span>';
        }).join('');
    }

    /** Fills the roll-up cards above the table. Counts are of the whole directory. */
    function loadStats(url) {
        $.getJSON(url).done(function (stats) {
            $('[data-stat="total"]').text(stats.total);
            Object.keys(stats.byStatus || {}).forEach(function (status) {
                $('[data-stat="' + status + '"]').text(stats.byStatus[status]);
            });
        });
    }

    /** Renders the cached details of one certificate as a definition-style block. */
    function certificateBlock(certificate) {
        var rows = [
            ['Status', statusBadge(certificate.status)],
            ['Subject', text(certificate.subjectDn)],
            ['Issuer', text(certificate.issuerDn)],
            ['Serial', '<span class="mono">' + text(certificate.serialNumber) + '</span>'],
            ['Valid from', text(formatDate(certificate.notBefore))],
            ['Valid to', expiryCell(certificate.notAfter)],
            ['Key', text([certificate.keyAlgorithm, certificate.keySize ? certificate.keySize + ' bit' : null]
                .filter(Boolean).join(' '))],
            ['Signature', text(certificate.signatureAlgorithm)],
            ['SANs', text(certificate.subjectAlternativeNames)],
            ['SHA-256', '<span class="mono">' + text(certificate.sha256Fingerprint) + '</span>']
        ];
        var body = rows.map(function (row) {
            return '<tr><th>' + row[0] + '</th><td>' + row[1] + '</td></tr>';
        }).join('');
        return '<div class="cert"><table>' + body + '</table></div>';
    }

    /** The directory entry itself, above its certificates. */
    function entryBlock(row, fields) {
        var body = fields
            .filter(function (field) { return row[field[1]]; })
            .map(function (field) {
                return '<tr><th>' + field[0] + '</th><td>' + text(row[field[1]]) + '</td></tr>';
            })
            .join('');
        return body ? '<div class="cert"><table>' + body + '</table></div>' : '';
    }

    function renderCertificates(certificates, row, entryFields, prefix, suffix) {
        var entry = (prefix || '') + (entryFields ? entryBlock(row, entryFields) : '');
        var body = (!certificates || certificates.length === 0)
            ? '<div class="text-secondary">This entry publishes no certificates.</div>'
            : certificates.map(certificateBlock).join('');
        return '<div class="cert-detail">' + entry + body + (suffix || '') + '</div>';
    }

    /**
     * Builds one search table.
     *
     * @param config.selector      table element to attach to
     * @param config.endpoint      DataTables endpoint
     * @param config.statsUrl      roll-up endpoint for the cards above the table
     * @param config.columns       DataTables column definitions
     * @param config.order         initial ordering
     * @param config.readFilters   returns the current extra filters as an object
     * @param config.filterInputs  elements that re-apply the filters when changed
     * @param config.detailUrl     given a row, the URL of its cached certificates
     * @param config.entryFields   [label, field] pairs shown above the certificates
     * @param config.detailPrefix  HTML to put at the top of the expanded row
     * @param config.detailSuffix  HTML to put at the bottom of it
     * @param config.onDetailShown called once that row is in the document, to wire it up
     */
    function initTable(config) {
        var $table = $(config.selector);

        var table = $table.DataTable({
            serverSide: true,
            processing: true,
            deferRender: true,
            searchDelay: 400,
            pageLength: 25,
            lengthMenu: [10, 25, 50, 100],
            order: config.order || [],
            columns: config.columns,
            ajax: {
                url: buildUrl(config.endpoint, config.readFilters()),
                type: 'POST',
                contentType: 'application/json',
                data: function (request) {
                    return JSON.stringify(request);
                },
                error: function (xhr) {
                    if (handleUnauthorized(xhr)) {
                        return;
                    }
                    window.console && console.error('Table request failed', xhr.status, xhr.responseText);
                }
            },
            language: {
                processing: 'Loading…',
                emptyTable: 'Nothing has been synced from the directory yet',
                zeroRecords: 'No entries match these filters',
                search: '',
                searchPlaceholder: 'Search all columns',
                lengthMenu: '_MENU_ per page'
            }
        });

        // ':visIdx' rather than a bare index: a hidden column leaves no footer cell, so
        // the DOM position of an input is its visible index, not its column index.
        $table.find('tfoot input').on('input', debounce(function () {
            var column = table.column($(this).closest('th').index() + ':visIdx');
            if (column.search() !== this.value) {
                column.search(this.value).draw();
            }
        }, 350));

        function applyFilters() {
            var filters = config.readFilters();
            reload(table, filters);
            if (config.onFiltersApplied) {
                config.onFiltersApplied(filters);
            }
        }

        (config.filterInputs || []).forEach(function (element) {
            if (!element) {
                return;
            }
            var event = element.tagName === 'INPUT' && element.type === 'text' ? 'input' : 'change';
            $(element).on(event, event === 'input' ? debounce(applyFilters, 400) : applyFilters);
        });

        // Expanding a row fetches the cached certificate detail on demand, so the table
        // itself never has to carry it.
        $table.on('click', 'td.expand', function () {
            var $cell = $(this);
            var row = table.row($cell.closest('tr'));
            if (row.child.isShown()) {
                row.child.hide();
                $cell.html(icon('plus'));
                return;
            }
            $cell.html(icon('minus'));
            row.child('<div class="cert-detail text-secondary">Loading…</div>').show();
            $.getJSON(config.detailUrl(row.data()))
                .done(function (certificates) {
                    var prefix = config.detailPrefix ? config.detailPrefix(row.data()) : '';
                    var suffix = config.detailSuffix ? config.detailSuffix(row.data()) : '';
                    row.child(renderCertificates(certificates, row.data(), config.entryFields, prefix, suffix))
                        .show();
                    if (config.onDetailShown) {
                        config.onDetailShown(row, $(row.child()));
                    }
                })
                .fail(function (xhr) {
                    if (handleUnauthorized(xhr)) {
                        return;
                    }
                    row.child('<div class="cert-detail text-secondary">Could not load certificate details.</div>')
                        .show();
                });
        });

        tables.push({table: table, endpoint: config.endpoint, statsUrl: config.statsUrl});

        if (config.statsUrl) {
            loadStats(config.statsUrl);
        }
        if (config.onFiltersApplied) {
            config.onFiltersApplied(config.readFilters());
        }
        return table;
    }

    window.CertAlert = {
        initTable: initTable,
        describeFilters: describeFilters,
        reload: reload,
        refreshAll: refreshAll,
        loadStats: loadStats,
        escapeHtml: escapeHtml,
        handleUnauthorized: handleUnauthorized,
        text: text,
        icon: icon,
        formatDate: formatDate,
        expiryCell: expiryCell,
        statusBadge: statusBadge
    };
})(window, jQuery);
