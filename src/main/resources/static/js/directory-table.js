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

    /** Whether this copy is a demo, where a refusal means something else entirely. */
    var demo = document.querySelector('meta[name="cert-alert-demo"]') !== null;

    /**
     * A refusal, and what to do about it.
     *
     * <p>Ordinarily a 401 or a 403 means the session lapsed while the page stayed open,
     * and the place to go is the sign-in page: better than a table that silently stops
     * updating. On a demo it means the demo refusing to be changed - there is no
     * sign-in page to go to, and bouncing somebody to one they cannot use is the worst
     * possible answer to a button they were invited to press. So it says what happened and
     * leaves them where they are.
     */
    function handleUnauthorized(xhr) {
        if (demo && xhr.status === 403) {
            sayItIsADemo(xhr);
            return true;
        }
        if (xhr.status === 401 || xhr.status === 403) {
            window.location.href = '/login';
            return true;
        }
        return false;
    }

    // Every refusal, wherever it came from. Each page's own handler says something
    // sensible for the deployment it was written for - "only an administrator can change
    // this" - and on a demo all of those are wrong in the same way: the visitor is an
    // administrator, and it is the demo that refused. One hook catches the lot, including
    // whatever is written next.
    if (demo) {
        $(document).ajaxError(function (event, xhr) {
            if (xhr.status === 403) {
                sayItIsADemo(xhr);
            }
        });
    }

    var demoNoticeTimer = null;
    var DEMO_REFUSAL = 'This is a read-only demo \u2014 nothing here can be changed.';

    /**
     * One notice at a time, wherever on the page the button was.
     *
     * <p>Prefers what the refusal itself said. The rule lives in the security
     * configuration, and a copy of its wording here is a copy that goes stale; the literal
     * is only for a refusal that arrived without one.
     */
    function sayItIsADemo(xhr) {
        var said = null;
        try {
            if (xhr && xhr.responseJSON) {
                said = xhr.responseJSON.detail;
            } else if (xhr && xhr.responseText) {
                said = JSON.parse(xhr.responseText).detail;
            }
        } catch (ignored) {
            // Not problem detail. The literal below says the same thing.
            said = null;
        }
        var notice = document.getElementById('demo-notice');
        if (!notice) {
            notice = document.createElement('div');
            notice.id = 'demo-notice';
            notice.className = 'demo-notice';
            notice.setAttribute('role', 'status');
            document.body.appendChild(notice);
        }
        notice.textContent = said || DEMO_REFUSAL;
        notice.classList.add('is-shown');
        window.clearTimeout(demoNoticeTimer);
        demoNoticeTimer = window.setTimeout(function () {
            notice.classList.remove('is-shown');
        }, 4000);
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

    /**
     * The names a certificate is good for, with what is worrying about them.
     *
     * <p>None of these is a fault in itself - a wildcard is a legitimate thing to issue - so
     * they read as things to look at, with the reason on the badge rather than in a legend
     * somewhere else.
     */
    function sanCell(certificate) {
        var names = text(certificate.subjectAlternativeNames);
        var count = certificate.subjectAltNameCount;
        var risks = certificate.risks || [];
        var badges = risks.map(function (risk) {
            return '<span class="badge ' + (risk.severe ? 'bg-red-lt' : 'bg-yellow-lt') + ' me-1"'
                + ' title="' + escapeHtml(risk.why) + '">' + escapeHtml(risk.label) + '</span>';
        }).join('');
        var summary = count ? '<div class="text-secondary small">' + count + ' name'
            + (count === 1 ? '' : 's') + '</div>' : '';
        return names + summary + (badges ? '<div class="mt-1">' + badges + '</div>' : '');
    }

    /**
     * What the key is allowed to do. It is what separates the two certificates a person
     * holds - one to sign with, one to be encrypted to - so it is worth a line of its own
     * rather than being left to whoever can read a key usage bit string.
     */
    function useCell(certificate) {
        var badge = '<span class="badge bg-blue-lt" title="' + escapeHtml(certificate.useDescription || '')
            + '">' + escapeHtml(certificate.use || '') + '</span>';
        var usages = certificate.keyUsages || [];
        return badge + (usages.length
            ? '<div class="text-secondary small mt-1">' + escapeHtml(usages.join(', ')) + '</div>'
            : '');
    }

    var REVOCATION_TONE = {
        GOOD: 'bg-green-lt',
        REVOKED: 'bg-red-lt',
        UNKNOWN: 'bg-yellow-lt',
        NOT_CHECKED: 'bg-secondary-lt'
    };

    /**
     * What the issuing authority says. Shown even when nothing has been asked yet, because
     * "not checked" is not the same as "not revoked" and an absent row reads like the
     * second one.
     */
    function revocationCell(certificate) {
        var tone = REVOCATION_TONE[certificate.revocationStatus] || 'bg-secondary-lt';
        var badge = '<span class="badge ' + tone + '">'
            + escapeHtml(certificate.revocationLabel || certificate.revocationStatus) + '</span>';
        var when = certificate.revokedAt
            ? ' <span class="text-secondary small">on ' + escapeHtml(formatDate(certificate.revokedAt))
              + (certificate.revocationReason ? ' (' + escapeHtml(certificate.revocationReason) + ')' : '')
              + '</span>'
            : '';
        var detail = certificate.revocationDetail
            ? '<div class="text-secondary small">' + escapeHtml(certificate.revocationDetail) + '</div>'
            : '';
        return badge + when + detail;
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
            ['Use', useCell(certificate)],
            ['Revocation', revocationCell(certificate)],
            ['Signature', text(certificate.signatureAlgorithm)],
            ['Hash', text(certificate.hashAlgorithm)],
            ['SANs', sanCell(certificate)],
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
     * @param config.emptyMessage  what an empty table says, when "nothing synced" is wrong
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
            // Null fields are left out of the JSON, so a directory entry without an
            // attribute arrives without the key at all. DataTables warns about a missing
            // key unless the column says what to show instead.
            columns: config.columns.map(function (column) {
                return $.extend({defaultContent: ''}, column);
            }),
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
                emptyTable: config.emptyMessage || 'Nothing has been synced from the directory yet',
                zeroRecords: 'No entries match these filters',
                search: '',
                searchPlaceholder: 'Search all columns',
                lengthMenu: '_MENU_ per page'
            }
        });

        // ':visIdx' rather than a bare index: a hidden column leaves no footer cell, so
        // the DOM position of an input is its visible index, not its column index.
        //
        // A box marked data-filter is not a column search at all: its column is assembled
        // for display and is not something the database can be asked about, so the page
        // reads it into an extra filter instead. Those are wired by the page.
        $table.find('tfoot input:not([data-filter])').on('input', debounce(function () {
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
        isDemo: function () {
            return demo;
        },
        text: text,
        icon: icon,
        formatDate: formatDate,
        expiryCell: expiryCell,
        statusBadge: statusBadge
    };
})(window, jQuery);
