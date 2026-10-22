/*
 * Administration -> Integrations: API tokens and webhooks.
 *
 * A new token or signing secret is put into the page from the response that created it and
 * nowhere else - not stored, not sent back - and is gone once the page is left. Every request
 * carries the CSRF token (set up in directory-table.js).
 */
(function (window, $) {
    'use strict';

    var TOKENS = '/api/v1/admin/api-tokens';
    var WEBHOOKS = '/api/v1/admin/webhooks';
    var readOnly = !document.getElementById('api-token-form');

    function esc(value) {
        return CertAlert.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    function when(iso) {
        if (!iso) {
            return '';
        }
        var date = new Date(iso);
        return isNaN(date.getTime()) ? esc(iso)
            : '<time datetime="' + esc(iso) + '" title="' + esc(date.toISOString()) + '">'
                + esc(date.toLocaleString()) + '</time>';
    }

    function say(target, xhr) {
        if (!CertAlert.handleUnauthorized(xhr)) {
            var body = xhr.responseJSON || {};
            $(target).text(body.detail || body.message || 'That did not work.');
        }
    }

    function copy(input, button) {
        var field = document.getElementById(input);
        field.select();
        if (navigator.clipboard) {
            navigator.clipboard.writeText(field.value);
        } else {
            document.execCommand('copy');
        }
        $(button).text('Copied');
    }

    // --- API tokens -------------------------------------------------------------------

    function loadScopes() {
        $.getJSON(TOKENS + '/scopes').done(function (scopes) {
            $('#api-token-scopes').html(scopes.map(function (scope) {
                var id = 'scope-' + scope.id.replace(/[^a-z0-9]/g, '-');
                return '<div class="col-12 col-md-6 col-xl-4"><label class="form-check" for="' + id + '">'
                    + '<input class="form-check-input" type="checkbox" id="' + id + '" value="' + esc(scope.id) + '">'
                    + '<span class="form-check-label"><span class="mono">' + esc(scope.id) + '</span> '
                    + '<span class="text-secondary small">' + esc(scope.description) + '</span></span></label></div>';
            }).join(''));
        });
    }

    function loadTokens() {
        $.getJSON(TOKENS).done(function (tokens) {
            $('#api-tokens tbody').html(tokens.length ? tokens.map(function (token) {
                var badge = {active: 'bg-green-lt', expired: 'bg-yellow-lt', revoked: 'bg-secondary-lt'}[token.state];
                var revoke = token.state !== 'active' || readOnly ? ''
                    : '<button type="button" class="btn btn-sm btn-outline-danger api-token-revoke" data-id="'
                        + esc(token.id) + '" aria-label="Revoke token ' + esc(token.name) + '">Revoke</button>';
                return '<tr><td>' + esc(token.name) + '</td>'
                    + '<td class="mono">' + esc(token.prefix) + '&hellip;</td>'
                    + '<td>' + token.scopes.map(function (s) {
                        return '<span class="badge bg-blue-lt me-1 mono">' + esc(s) + '</span>';
                    }).join('') + '</td>'
                    + '<td>' + when(token.createdAt) + ' <span class="text-secondary small">' + esc(token.createdBy) + '</span></td>'
                    + '<td>' + (token.expiresAt ? when(token.expiresAt) : '<span class="text-secondary">never</span>') + '</td>'
                    + '<td>' + (token.lastUsedAt ? when(token.lastUsedAt) + ' <span class="text-secondary small">'
                        + esc(token.lastUsedFrom) + '</span>' : '<span class="text-secondary">never</span>') + '</td>'
                    + '<td><span class="badge ' + badge + '">' + esc(token.state) + '</span></td>'
                    + '<td class="text-end">' + revoke + '</td></tr>';
            }).join('') : '<tr><td colspan="8" class="text-secondary">No API tokens yet</td></tr>');
        }).fail(function (xhr) {
            say('#api-token-message', xhr);
        });
    }

    // --- Webhooks ---------------------------------------------------------------------

    function loadEvents() {
        $.getJSON(WEBHOOKS + '/events').done(function (events) {
            $('#webhook-events').html(events.map(function (event) {
                var id = 'event-' + event.id.replace(/[^a-z0-9]/g, '-');
                return '<div class="col-6 col-md-3"><label class="form-check" for="' + id + '">'
                    + '<input class="form-check-input" type="checkbox" id="' + id + '" value="' + esc(event.id) + '" checked>'
                    + '<span class="form-check-label mono">' + esc(event.id) + '</span></label></div>';
            }).join(''));
        });
    }

    function loadWebhooks() {
        $.getJSON(WEBHOOKS).done(function (endpoints) {
            $('#webhooks tbody').html(endpoints.length ? endpoints.map(function (endpoint) {
                var actions = '<button type="button" class="btn btn-sm webhook-log" data-id="' + esc(endpoint.id)
                    + '" data-name="' + esc(endpoint.name) + '">Deliveries</button>';
                if (!readOnly) {
                    actions += ' <button type="button" class="btn btn-sm webhook-test" data-id="' + esc(endpoint.id) + '">Send a test</button>'
                        + ' <button type="button" class="btn btn-sm webhook-toggle" data-id="' + esc(endpoint.id) + '">'
                        + (endpoint.enabled ? 'Disable' : 'Enable') + '</button>'
                        + ' <button type="button" class="btn btn-sm webhook-rotate" data-id="' + esc(endpoint.id) + '">New secret</button>'
                        + ' <button type="button" class="btn btn-sm btn-outline-danger webhook-delete" data-id="' + esc(endpoint.id)
                        + '" aria-label="Delete webhook ' + esc(endpoint.name) + '">Delete</button>';
                }
                return '<tr data-endpoint="' + esc(JSON.stringify(endpoint)) + '"><td>' + esc(endpoint.name) + '</td>'
                    + '<td class="mono small text-break">' + esc(endpoint.url) + '</td>'
                    + '<td>' + esc(endpoint.format) + '</td>'
                    + '<td>' + endpoint.events.map(function (e) {
                        return '<span class="badge bg-blue-lt me-1 mono">' + esc(e) + '</span>';
                    }).join('') + '</td>'
                    + '<td class="mono">' + esc(endpoint.secretPrefix) + '&hellip;</td>'
                    + '<td><span class="badge ' + (endpoint.enabled ? 'bg-green-lt">enabled' : 'bg-secondary-lt">disabled')
                    + '</span></td><td class="text-end text-nowrap">' + actions + '</td></tr>';
            }).join('') : '<tr><td colspan="7" class="text-secondary">No webhooks yet</td></tr>');
        }).fail(function (xhr) {
            say('#webhook-message', xhr);
        });
    }

    function loadDeliveries(id, name) {
        $('#webhook-deliveries-title').text('Deliveries to ' + name);
        $('#webhook-deliveries-card').removeClass('d-none');
        $.getJSON(WEBHOOKS + '/' + encodeURIComponent(id) + '/deliveries').done(function (rows) {
            $('#webhook-deliveries tbody').html(rows.length ? rows.map(function (row) {
                var badge = {DELIVERED: 'bg-green-lt', PENDING: 'bg-yellow-lt', SENDING: 'bg-blue-lt', FAILED: 'bg-red-lt'}[row.state];
                return '<tr><td>' + when(row.createdAt) + '</td><td class="mono">' + esc(row.eventType) + '</td>'
                    + '<td><span class="badge ' + badge + '">' + esc(row.state.toLowerCase()) + '</span></td>'
                    + '<td>' + esc(row.attempts) + '</td>'
                    + '<td>' + (row.lastStatus ? esc(row.lastStatus) + ' ' : '') + '<span class="text-secondary small">'
                    + esc(row.lastError) + '</span></td></tr>';
            }).join('') : '<tr><td colspan="5" class="text-secondary">Nothing sent yet</td></tr>');
        });
    }

    function checked(container) {
        return $(container).find('input:checked').map(function () {
            return this.value;
        }).get();
    }

    $(function () {
        if (!document.getElementById('api-tokens')) {
            return;
        }
        $('#api-token-form').on('submit', function (event) {
            event.preventDefault();
            $('#api-token-message').text('');
            var expires = $('#api-token-expires').val();
            $.ajax({
                url: TOKENS,
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    name: $('#api-token-name').val(),
                    scopes: checked('#api-token-scopes'),
                    // The end of the day chosen, in UTC.
                    expiresAt: expires ? expires + 'T23:59:59Z' : null
                })
            }).done(function (created) {
                $('#api-token-secret').val(created.secret);
                $('#api-token-created').removeClass('d-none');
                $('#api-token-copy').text('Copy');
                document.getElementById('api-token-secret').focus();
                $('#api-token-name').val('');
                loadTokens();
            }).fail(function (xhr) {
                say('#api-token-message', xhr);
            });
        });
        $('#api-token-copy').on('click', function () {
            copy('api-token-secret', this);
        });
        $('#api-tokens').on('click', '.api-token-revoke', function () {
            if (!window.confirm('Revoke this token? Anything using it stops working at once.')) {
                return;
            }
            $.ajax({url: TOKENS + '/' + encodeURIComponent(this.getAttribute('data-id')), method: 'DELETE'})
                .done(loadTokens)
                .fail(function (xhr) {
                    say('#api-token-message', xhr);
                });
        });

        $('#webhook-form').on('submit', function (event) {
            event.preventDefault();
            $('#webhook-message').text('');
            $.ajax({
                url: WEBHOOKS,
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    name: $('#webhook-name').val(),
                    url: $('#webhook-url').val(),
                    format: $('#webhook-format').val(),
                    events: checked('#webhook-events')
                })
            }).done(function (created) {
                $('#webhook-secret').val(created.secret);
                $('#webhook-created').removeClass('d-none');
                $('#webhook-copy').text('Copy');
                document.getElementById('webhook-secret').focus();
                $('#webhook-name, #webhook-url').val('');
                loadWebhooks();
            }).fail(function (xhr) {
                say('#webhook-message', xhr);
            });
        });
        $('#webhook-copy').on('click', function () {
            copy('webhook-secret', this);
        });
        $('#webhooks').on('click', '.webhook-log', function () {
            loadDeliveries(this.getAttribute('data-id'), this.getAttribute('data-name'));
        }).on('click', '.webhook-test', function () {
            var id = this.getAttribute('data-id');
            $.ajax({url: WEBHOOKS + '/' + encodeURIComponent(id) + '/test', method: 'POST'})
                .done(function () {
                    $('#webhook-message').text('Test queued; it appears under Deliveries once sent.');
                })
                .fail(function (xhr) {
                    say('#webhook-message', xhr);
                });
        }).on('click', '.webhook-toggle', function () {
            var endpoint = JSON.parse($(this).closest('tr').attr('data-endpoint'));
            $.ajax({
                url: WEBHOOKS + '/' + encodeURIComponent(endpoint.id),
                method: 'PUT',
                contentType: 'application/json',
                data: JSON.stringify({
                    name: endpoint.name, url: endpoint.url, format: endpoint.format,
                    events: endpoint.events, enabled: !endpoint.enabled
                })
            }).done(loadWebhooks).fail(function (xhr) {
                say('#webhook-message', xhr);
            });
        }).on('click', '.webhook-rotate', function () {
            if (!window.confirm('Give this webhook a new signing secret? The receiver must be given it before the next delivery verifies.')) {
                return;
            }
            $.ajax({url: WEBHOOKS + '/' + encodeURIComponent(this.getAttribute('data-id')) + '/secret', method: 'POST'})
                .done(function (rotated) {
                    $('#webhook-secret').val(rotated.secret);
                    $('#webhook-created').removeClass('d-none');
                    document.getElementById('webhook-secret').focus();
                    loadWebhooks();
                })
                .fail(function (xhr) {
                    say('#webhook-message', xhr);
                });
        }).on('click', '.webhook-delete', function () {
            if (!window.confirm('Delete this webhook and its delivery log?')) {
                return;
            }
            $.ajax({url: WEBHOOKS + '/' + encodeURIComponent(this.getAttribute('data-id')), method: 'DELETE'})
                .done(loadWebhooks)
                .fail(function (xhr) {
                    say('#webhook-message', xhr);
                });
        });

        if (!readOnly) {
            loadScopes();
            loadEvents();
        }
        loadTokens();
        loadWebhooks();
    });
})(window, jQuery);
