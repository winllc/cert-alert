/*
 * Shared wiring for the two directory search tables.
 *
 * DataTables owns paging, ordering, the global search box and the per-column search
 * inputs; all of that is posted as the request body and turned into a query server side.
 * The extra filters this application adds (certificate state, point of contact) are query
 * parameters instead, so changing one just points the table's ajax URL somewhere new and
 * reloads.
 */
(function (window, $) {
    'use strict';

    var MS_PER_DAY = 86400000;

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
            return '<span class="muted">' + escapeHtml(fallback || '—') + '</span>';
        }
        return escapeHtml(value);
    }

    /** Dates render as plain UTC days: the audience cares about the day, not the second. */
    function formatDate(iso) {
        if (!iso) {
            return null;
        }
        var parsed = new Date(iso);
        if (isNaN(parsed.getTime())) {
            return null;
        }
        return parsed.toISOString().slice(0, 10);
    }

    function daysUntil(iso) {
        if (!iso) {
            return null;
        }
        var parsed = new Date(iso);
        if (isNaN(parsed.getTime())) {
            return null;
        }
        return Math.floor((parsed.getTime() - Date.now()) / MS_PER_DAY);
    }

    function statusBadge(status) {
        var label = (status || 'NONE').replace('_', ' ');
        return '<span class="badge ' + escapeHtml(status || 'NONE') + '">' + escapeHtml(label) + '</span>';
    }

    /** Expiry date plus how long is left, which is what people actually scan the column for. */
    function expiryCell(iso) {
        var formatted = formatDate(iso);
        if (!formatted) {
            return '<span class="muted">—</span>';
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

    /** Re-points a built table at a new filter set. */
    function reload(table, filters) {
        table.ajax.url(buildUrl(table.certAlertEndpoint, filters)).load();
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

    function renderCertificates(certificates) {
        if (!certificates || certificates.length === 0) {
            return '<div class="cert-detail muted">This entry publishes no certificates.</div>';
        }
        return '<div class="cert-detail">' + certificates.map(certificateBlock).join('') + '</div>';
    }

    /**
     * Builds one search table.
     *
     * @param config.selector      table element to attach to
     * @param config.endpoint      DataTables endpoint
     * @param config.columns       DataTables column definitions
     * @param config.order         initial ordering
     * @param config.readFilters   returns the current extra filters as an object
     * @param config.filterInputs  elements that re-apply the filters when changed
     * @param config.detailUrl     given a row, the URL of its cached certificates
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
                search: 'Search all columns:'
            }
        });

        $table.find('tfoot input').on('input', debounce(function () {
            var index = $(this).closest('th').index();
            if (table.column(index).search() !== this.value) {
                table.column(index).search(this.value).draw();
            }
        }, 350));

        function applyFilters() {
            var filters = config.readFilters();
            table.ajax.url(buildUrl(config.endpoint, filters)).load();
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
                $cell.text('+');
                return;
            }
            $cell.text('−');
            row.child('<div class="cert-detail muted">Loading…</div>').show();
            $.getJSON(config.detailUrl(row.data()))
                .done(function (certificates) {
                    row.child(renderCertificates(certificates)).show();
                })
                .fail(function (xhr) {
                    if (handleUnauthorized(xhr)) {
                        return;
                    }
                    row.child('<div class="cert-detail muted">Could not load certificate details.</div>').show();
                });
        });

        // Kept on the table so a caller can re-point it without holding the config.
        table.certAlertEndpoint = config.endpoint;

        if (config.onFiltersApplied) {
            config.onFiltersApplied(config.readFilters());
        }
        return table;
    }

    /** Renders the applied-filter summary shown beside the filter controls. */
    function describeFilters(filters) {
        var keys = Object.keys(filters);
        if (keys.length === 0) {
            return 'No extra filters';
        }
        return 'Filtered by <strong>' + keys.map(function (key) {
            return escapeHtml(key + '=' + filters[key]);
        }).join('</strong>, <strong>') + '</strong>';
    }

    window.CertAlert = {
        initTable: initTable,
        describeFilters: describeFilters,
        reload: reload,
        escapeHtml: escapeHtml,
        text: text,
        formatDate: formatDate,
        expiryCell: expiryCell,
        statusBadge: statusBadge
    };
})(window, jQuery);
