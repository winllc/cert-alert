/*
 * Administration -> Audit log.
 *
 * The log is paged and filtered server side (/api/v1/admin/audit/events): this script only
 * reads the filter form into a query string, renders a page, and opens one event in full.
 * Export links carry the same query string, so an export is exactly what is on screen,
 * without the paging. Verification, the delivery panel and the API tokens are small calls
 * of their own. Every request carries the CSRF token (set up in directory-table.js).
 */
(function (window, $) {
    'use strict';

    var API = '/api/v1/admin/audit';
    var PAGE_SIZE = 25;
    var page = 0;
    var totalPages = 0;

    var SEVERITY_BADGE = {
        info: 'bg-secondary-lt',
        notice: 'bg-blue-lt',
        warning: 'bg-yellow-lt',
        alert: 'bg-red-lt'
    };
    var OUTCOME_BADGE = {
        success: 'bg-green-lt',
        failure: 'bg-red-lt',
        denied: 'bg-orange-lt'
    };

    function esc(value) {
        return CertAlert.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    /** Local time for reading; UTC in the title for comparing with anything else. */
    function when(iso) {
        if (!iso) {
            return '';
        }
        var date = new Date(iso);
        if (isNaN(date.getTime())) {
            return esc(iso);
        }
        return '<span title="' + esc(date.toISOString()) + '">' + esc(date.toLocaleString()) + '</span>';
    }

    function filterQuery() {
        var params = new URLSearchParams();
        $('#audit-filters').serializeArray().forEach(function (field) {
            if (field.value && field.value.trim() !== '') {
                params.append(field.name, field.value.trim());
            }
        });
        return params;
    }

    function actorOf(row) {
        var name = row.actorName || row.actorId || row.actorType || '';
        var title = (row.actorType || '') + (row.actorId ? ': ' + row.actorId : '');
        return '<span title="' + esc(title) + '">' + esc(name) + '</span>';
    }

    function targetOf(row) {
        if (!row.targetType && !row.targetId && !row.targetName) {
            return '<span class="text-secondary">&ndash;</span>';
        }
        var label = row.targetName || row.targetId || '';
        return '<span class="text-secondary small">' + esc(row.targetType || '') + '</span> '
            + '<span title="' + esc(row.targetId || '') + '">' + esc(label) + '</span>';
    }

    function load() {
        var params = filterQuery();
        params.set('page', page);
        params.set('size', PAGE_SIZE);
        $.getJSON(API + '/events?' + params.toString())
            .done(function (result) {
                totalPages = result.totalPages;
                var rows = result.content.map(function (row) {
                    return '<tr class="audit-row" data-seq="' + esc(row.seq) + '" style="cursor:pointer">'
                        + '<td class="mono audit-when">' + when(row.time) + '</td>'
                        + '<td><span class="badge ' + (SEVERITY_BADGE[row.severity] || 'bg-secondary-lt') + '">'
                        + esc(row.severity) + '</span></td>'
                        + '<td class="mono">' + esc(row.action) + '</td>'
                        + '<td><span class="badge ' + (OUTCOME_BADGE[row.outcome] || 'bg-secondary-lt') + '">'
                        + esc(row.outcome) + '</span></td>'
                        + '<td>' + actorOf(row) + '</td>'
                        + '<td>' + targetOf(row) + '</td>'
                        + '<td class="mono">' + esc(row.sourceIp || '') + '</td>'
                        + '</tr>';
                });
                $('#audit-log tbody').html(rows.length ? rows.join('')
                    : '<tr><td colspan="7" class="text-secondary">No events match</td></tr>');
                var first = result.totalElements === 0 ? 0 : page * PAGE_SIZE + 1;
                var last = page * PAGE_SIZE + result.content.length;
                $('#audit-page-info').text(first.toLocaleString() + '–' + last.toLocaleString() + ' of '
                    + result.totalElements.toLocaleString());
                // Said to assistive technology as well as shown: a grey link is still a link.
                $('#audit-prev').attr('aria-disabled', String(page === 0)).parent().toggleClass('disabled', page === 0);
                $('#audit-next').attr('aria-disabled', String(page + 1 >= totalPages))
                    .parent().toggleClass('disabled', page + 1 >= totalPages);
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    function exportLink(format) {
        var params = filterQuery();
        params.set('format', format);
        return API + '/export?' + params.toString();
    }

    function showDetail(seq) {
        $.getJSON(API + '/events/' + encodeURIComponent(seq))
            .done(function (detail) {
                var event = detail.event;
                var fields = [
                    ['seq', detail.seq], ['id', event.id], ['time (UTC)', event.time],
                    ['category', event.category], ['action', event.action], ['outcome', event.outcome],
                    ['severity', event.severity],
                    ['actor', [event.actor.type, event.actor.id, event.actor.name].filter(Boolean).join(' / ')],
                    ['roles', (event.actor.roles || []).join(', ')],
                    ['source', event.source ? [event.source.ip, event.source.userAgent].filter(Boolean).join(' / ') : ''],
                    ['target', event.target ? [event.target.type, event.target.id, event.target.name].filter(Boolean).join(' / ') : ''],
                    ['reason', event.reason], ['request', event.requestId], ['session', event.session],
                    ['product', event.product + ' ' + event.version], ['host', event.host],
                    ['installation', event.installationId], ['prevHash', event.prevHash], ['hash', detail.hash]
                ];
                $('#audit-detail-title').text('Event ' + detail.seq + ': ' + event.action);
                $('#audit-detail-fields').html(fields.map(function (field) {
                    return '<dt class="col-3">' + esc(field[0]) + '</dt><dd class="col-9 mono small text-break">'
                        + esc(field[1] === null || field[1] === undefined ? '' : field[1]) + '</dd>';
                }).join(''));
                $('#audit-detail-json').text(detail.formatted);
                (window.bootstrap || window.tabler).Modal.getOrCreateInstance(document.getElementById('audit-detail')).show();
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    function loadDelivery() {
        $.getJSON(API + '/delivery')
            .done(function (status) {
                $('#audit-head').text('Head seq ' + status.head.toLocaleString());
                $('#audit-retention').text(status.retention);
                $('#audit-delivery tbody').html(status.outputs.map(function (output) {
                    var state = !output.enabled ? '<span class="badge bg-secondary-lt">off</span>'
                        : output.failing ? '<span class="badge bg-red-lt">failing</span>'
                            : '<span class="badge bg-green-lt">on</span>';
                    return '<tr><td>' + esc(output.output) + '</td><td>' + state + '</td>'
                        + '<td>' + (output.enabled && output.lastSeq ? 'seq ' + esc(output.lastSeq) + ' · ' + when(output.deliveredAt) : '') + '</td>'
                        + '<td>' + (output.enabled ? esc(output.backlog) : '') + '</td>'
                        + '<td class="small text-break">' + (output.lastError ? esc(output.lastError) + ' · ' + when(output.lastErrorAt) : '') + '</td></tr>';
                }).join(''));
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    function verify() {
        var button = $('#audit-verify').prop('disabled', true);
        $('#audit-verify-result').html('<span class="text-secondary">Verifying&hellip;</span>');
        $.ajax({url: API + '/verify', method: 'POST'})
            .done(function (result) {
                var css = result.intact ? 'alert-success' : 'alert-danger';
                $('#audit-verify-result').html('<div class="alert ' + css + ' mb-0">' + esc(result.summary) + '</div>');
                load();
            })
            .fail(function (xhr) {
                if (!CertAlert.handleUnauthorized(xhr)) {
                    $('#audit-verify-result').html('<div class="alert alert-danger mb-0">Verification could not run.</div>');
                }
            })
            .always(function () {
                button.prop('disabled', false);
            });
    }

    function loadTokens() {
        $.getJSON(API + '/tokens')
            .done(function (tokens) {
                $('#audit-tokens tbody').html(tokens.length ? tokens.map(function (token) {
                    var status = token.revokedAt
                        ? '<span class="badge bg-secondary-lt" title="' + esc(token.revokedBy) + '">revoked</span>'
                        : '<span class="badge bg-green-lt">active</span>';
                    var revoke = token.revokedAt ? ''
                        : '<button type="button" class="btn btn-sm btn-outline-danger audit-token-revoke" data-id="'
                        + esc(token.id) + '">Revoke</button>';
                    return '<tr><td>' + esc(token.name) + '</td><td class="mono">' + esc(token.prefix) + '&hellip;</td>'
                        + '<td>' + when(token.createdAt) + ' <span class="text-secondary small">' + esc(token.createdBy) + '</span></td>'
                        + '<td>' + (token.lastUsedAt ? when(token.lastUsedAt) : '<span class="text-secondary">never</span>') + '</td>'
                        + '<td>' + status + '</td><td class="text-end">' + revoke + '</td></tr>';
                }).join('') : '<tr><td colspan="6" class="text-secondary">No tokens yet</td></tr>');
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    function sayTokenError(xhr) {
        if (!CertAlert.handleUnauthorized(xhr)) {
            var body = xhr.responseJSON || {};
            $('#audit-token-message').text(body.detail || 'That did not work.');
        }
    }

    $(function () {
        if (!document.getElementById('audit-log')) {
            return;
        }
        $('#audit-filters').on('submit', function (event) {
            event.preventDefault();
            page = 0;
            load();
        }).on('reset', function () {
            window.setTimeout(function () {
                page = 0;
                load();
            }, 0);
        });
        $('#audit-prev').on('click', function (event) {
            event.preventDefault();
            if (page > 0) {
                page--;
                load();
            }
        });
        $('#audit-next').on('click', function (event) {
            event.preventDefault();
            if (page + 1 < totalPages) {
                page++;
                load();
            }
        });
        $('#audit-log').on('click', 'tr.audit-row', function () {
            showDetail(this.getAttribute('data-seq'));
        });
        $('#audit-export-csv').on('click', function (event) {
            event.preventDefault();
            window.location.href = exportLink('csv');
        });
        $('#audit-export-ndjson').on('click', function (event) {
            event.preventDefault();
            window.location.href = exportLink('ndjson');
        });
        $('#audit-verify').on('click', verify);
        $('#audit-token-form').on('submit', function (event) {
            event.preventDefault();
            $('#audit-token-message').text('');
            $.ajax({
                url: API + '/tokens',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({name: $('#audit-token-name').val()})
            }).done(function (created) {
                $('#audit-token-secret').val(created.secret);
                $('#audit-token-created').removeClass('d-none');
                $('#audit-token-name').val('');
                loadTokens();
                load();
            }).fail(sayTokenError);
        });
        $('#audit-token-copy').on('click', function () {
            var secret = document.getElementById('audit-token-secret');
            secret.select();
            if (navigator.clipboard) {
                navigator.clipboard.writeText(secret.value);
            } else {
                document.execCommand('copy');
            }
            $(this).text('Copied');
        });
        $('#audit-tokens').on('click', '.audit-token-revoke', function () {
            var id = this.getAttribute('data-id');
            if (!window.confirm('Revoke this token? Anything using it stops working at once.')) {
                return;
            }
            $.ajax({url: API + '/tokens/' + encodeURIComponent(id), method: 'DELETE'})
                .done(function () {
                    loadTokens();
                    load();
                })
                .fail(sayTokenError);
        });
        load();
        loadDelivery();
        loadTokens();
    });
})(window, jQuery);
