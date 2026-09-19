/*
 * The administration page: the audit trail across every entry.
 *
 * The same search table the details pages carry, with the subject left open rather than
 * fixed - so it needs a column saying what each record is about, and filters for narrowing
 * a directory's worth of history down to the question being asked.
 */
(function ($) {
    'use strict';

    var ACTION_BADGE = {
        ENTRY_DISCOVERED: 'bg-blue-lt',
        ENTRY_PRUNED: 'bg-secondary-lt',
        CERTIFICATE_CACHED: 'bg-green-lt',
        CERTIFICATE_REMOVED: 'bg-orange-lt',
        CERTIFICATE_STATUS_CHANGED: 'bg-yellow-lt',
        CONTACT_ADDED: 'bg-purple-lt',
        CONTACT_REMOVED: 'bg-purple-lt',
        ADDRESS_ADDED: 'bg-purple-lt',
        ADDRESS_REMOVED: 'bg-purple-lt',
        PROJECT_JOINED: 'bg-cyan-lt',
        PROJECT_LEFT: 'bg-cyan-lt',
        ALERT_SENT: 'bg-azure-lt',
        ALERT_FAILED: 'bg-red-lt'
    };

    function moment(iso) {
        if (!iso) {
            return '';
        }
        var parsed = new Date(iso);
        return isNaN(parsed.getTime()) ? '' : parsed.toISOString().slice(0, 16).replace('T', ' ') + 'Z';
    }

    function day(iso) {
        return iso ? new Date(iso).toISOString().slice(0, 10) : null;
    }

    /** The counts above the table, and how far back the trail actually goes. */
    function loadSummary() {
        $.getJSON('/api/v1/audit/summary')
            .done(function (summary) {
                $('#audit-total').text(summary.total.toLocaleString());
                $('#audit-last-day').text(summary.lastDay.toLocaleString());
                $('#audit-last-week').text(summary.lastWeek.toLocaleString());
                $('#audit-actors').text(summary.actors.toLocaleString());
                $('#audit-span').text(summary.oldest
                    ? 'From ' + day(summary.oldest) + ' to ' + day(summary.newest)
                    : 'Nothing recorded yet');
            })
            .fail(function (xhr) {
                CertAlert.handleUnauthorized(xhr);
            });
    }

    $(function () {
        if ($('#audit-table').length === 0) {
            return;
        }
        loadSummary();

        var kind = document.getElementById('audit-kind');
        var subjectType = document.getElementById('audit-subject-type');
        var actor = document.getElementById('audit-actor');
        var from = document.getElementById('audit-from');
        var to = document.getElementById('audit-to');
        // The box under the About column: it searches a record's name and its DN at once,
        // which is two columns of the table and so not a column search.
        var subject = document.querySelector('#audit-table tfoot input[data-filter="subject"]');
        var applied = document.getElementById('applied-filters');

        function readFilters() {
            var filters = {};
            if (kind.value) {
                filters.action = kind.value;
            }
            if (subjectType.value) {
                filters.subjectType = subjectType.value;
            }
            if (actor.value.trim()) {
                filters.actor = actor.value.trim();
            }
            if (subject && subject.value.trim()) {
                filters.subject = subject.value.trim();
            }
            if (from.value) {
                filters.from = from.value;
            }
            if (to.value) {
                filters.to = to.value;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#audit-table',
            endpoint: '/api/v1/datatables/audit',
            readFilters: readFilters,
            filterInputs: [kind, subjectType, actor, subject, from, to],
            // Newest first: the recent end is the one being looked at.
            order: [[0, 'desc']],
            emptyMessage: 'Nothing has been recorded yet',
            onFiltersApplied: function (filters) {
                applied.innerHTML = CertAlert.describeFilters(filters);
            },
            columns: [
                {data: 'occurredAt', name: 'occurredAt', searchable: false, className: 'mono audit-when',
                    render: function (value, type) {
                        return type === 'display' ? CertAlert.escapeHtml(moment(value)) : value;
                    }},
                {data: 'label', name: 'action', searchable: false, className: 'audit-action',
                    render: function (value, type, row) {
                        if (type !== 'display') { return value; }
                        return '<span class="badge ' + (ACTION_BADGE[row.action] || 'bg-secondary-lt') + '">'
                            + CertAlert.escapeHtml(value) + '</span>';
                    }},
                // What it happened to. A record outlives its entry, so the link appears only
                // while there is something to link to; the DN is what is left afterwards.
                {data: 'subjectName', name: 'subjectName', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    var name = value || row.subjectDn;
                    var path = row.subjectType === 'SERVER' ? '/servers/' : '/users/';
                    var label = row.subjectId
                        ? '<a href="' + path + row.subjectId + '">' + CertAlert.escapeHtml(name) + '</a>'
                        : CertAlert.escapeHtml(name);
                    return label + '<div class="text-secondary small mono">'
                        + CertAlert.escapeHtml(row.subjectDn) + '</div>';
                }},
                {data: 'summary', name: 'summary', render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value) : value;
                }},
                {data: 'actor', name: 'actor', searchable: false, className: 'audit-actor',
                    render: function (value, type) {
                        return type === 'display' ? CertAlert.text(value) : value;
                    }},
                {data: 'channel', name: 'channel', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value, '—') : value;
                }},
                {data: 'target', name: 'target', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(value, '—') : value;
                }}
            ]
        });
    });
})(jQuery);

/*
 * The managed attributes an administrator defines for servers.
 *
 * Kept apart from the log above it: one is a record of what has happened, the other decides
 * what this deployment keeps about a server. They share a page because they share an
 * audience.
 */
(function (window, $) {
    'use strict';

    var TYPE_LABEL = {TEXT: 'Free text', CHOICE: 'Drop-down', BOOLEAN: 'Yes or no'};

    function message(text, isError) {
        $('#attribute-message').text(text || '')
            .toggleClass('text-danger', !!isError)
            .toggleClass('text-secondary', !isError);
    }

    function problem(xhr, fallback) {
        if (xhr.status === 401) {
            CertAlert.handleUnauthorized(xhr);
            return null;
        }
        var detail = xhr.responseJSON && (xhr.responseJSON.detail || xhr.responseJSON.title);
        return detail || fallback;
    }

    function row(attribute) {
        var choices = attribute.options.length
            ? attribute.options.map(function (option) {
                return '<span class="badge bg-blue-lt me-1">' + CertAlert.escapeHtml(option) + '</span>';
            }).join('')
            : '<span class="text-secondary">—</span>';

        return '<tr data-attribute-id="' + attribute.id + '">'
            + '<td><div class="fw-medium">' + CertAlert.escapeHtml(attribute.name) + '</div>'
            + (attribute.description
                ? '<div class="text-secondary small">' + CertAlert.escapeHtml(attribute.description) + '</div>'
                : '')
            + '</td>'
            + '<td>' + CertAlert.escapeHtml(TYPE_LABEL[attribute.type] || attribute.type) + '</td>'
            + '<td>' + (attribute.multiValued ? 'Several' : 'One') + '</td>'
            + '<td>' + choices + '</td>'
            + '<td class="mono">' + (attribute.serversHolding || 0) + '</td>'
            + '<td class="text-end">'
            + '<button type="button" class="btn btn-sm attribute-edit me-1">Edit</button>'
            + '<button type="button" class="btn btn-sm btn-ghost-danger attribute-delete">Retire</button>'
            + '</td></tr>';
    }

    var definitions = [];

    function load() {
        return $.getJSON('/api/v1/admin/server-attributes')
            .done(function (result) {
                definitions = result;
                $('#attribute-definitions').html(result.length
                    ? result.map(row).join('')
                    : '<tr><td colspan="6" class="text-secondary">'
                        + 'Nothing defined yet. What is added here becomes a field on every server.'
                        + '</td></tr>');
            })
            .fail(function (xhr) {
                var detail = problem(xhr, 'Could not read the managed attributes.');
                if (detail) {
                    $('#attribute-definitions').html(
                        '<tr><td colspan="6" class="text-secondary">' + CertAlert.escapeHtml(detail) + '</td></tr>');
                }
            });
    }

    function reset() {
        $('#attribute-id').val('');
        $('#attribute-name').val('');
        $('#attribute-description').val('');
        $('#attribute-type').val('TEXT');
        $('#attribute-multi').val('false');
        $('#attribute-options').val('');
        $('#attribute-submit').text('Add attribute');
        $('#attribute-cancel').addClass('d-none');
        showFields();
    }

    /** A boolean is one value by definition, and only a drop-down has anything to choose from. */
    function showFields() {
        var type = $('#attribute-type').val();
        $('#attribute-options-field').toggleClass('d-none', type !== 'CHOICE');
        $('#attribute-multi').prop('disabled', type === 'BOOLEAN');
        if (type === 'BOOLEAN') {
            $('#attribute-multi').val('false');
        }
    }

    function edit(attribute) {
        $('#attribute-id').val(attribute.id);
        $('#attribute-name').val(attribute.name);
        $('#attribute-description').val(attribute.description || '');
        $('#attribute-type').val(attribute.type);
        $('#attribute-multi').val(String(attribute.multiValued));
        $('#attribute-options').val(attribute.options.join('\n'));
        $('#attribute-submit').text('Save changes');
        $('#attribute-cancel').removeClass('d-none');
        showFields();
        message('');
        $('#attribute-name').trigger('focus');
    }

    $(function () {
        if ($('#server-attribute-admin').length === 0) {
            return;
        }
        load();
        showFields();

        $('#attribute-type').on('change', showFields);
        $('#attribute-cancel').on('click', function () {
            reset();
            message('');
        });

        $('#attribute-form').on('submit', function (event) {
            event.preventDefault();
            var id = $('#attribute-id').val();
            var body = {
                name: $('#attribute-name').val(),
                description: $('#attribute-description').val(),
                type: $('#attribute-type').val(),
                multiValued: $('#attribute-multi').val() === 'true',
                options: $('#attribute-options').val().split('\n')
                    .map(function (line) { return line.trim(); })
                    .filter(Boolean)
            };
            $.ajax({
                url: '/api/v1/admin/server-attributes' + (id ? '/' + id : ''),
                type: id ? 'PUT' : 'POST',
                contentType: 'application/json',
                data: JSON.stringify(body)
            })
                .done(function (saved) {
                    message((id ? 'Saved ' : 'Added ') + saved.name);
                    reset();
                    load();
                })
                .fail(function (xhr) {
                    var detail = problem(xhr, 'Could not save that attribute.');
                    if (detail) {
                        message(detail, true);
                    }
                });
        });

        $('#attribute-definitions').on('click', '.attribute-edit', function () {
            var id = $(this).closest('tr').data('attribute-id');
            var attribute = definitions.find(function (candidate) { return candidate.id === id; });
            if (attribute) {
                edit(attribute);
            }
        });

        $('#attribute-definitions').on('click', '.attribute-delete', function () {
            var $row = $(this).closest('tr');
            var id = $row.data('attribute-id');
            var attribute = definitions.find(function (candidate) { return candidate.id === id; });
            var held = attribute ? (attribute.serversHolding || 0) : 0;
            var warning = 'Retire ' + (attribute ? attribute.name : 'this attribute') + '?'
                + (held ? ' ' + held + ' server(s) hold a value for it, which goes too.' : '');
            if (!window.confirm(warning)) {
                return;
            }
            $.ajax({url: '/api/v1/admin/server-attributes/' + id, type: 'DELETE'})
                .done(function () {
                    message('Retired ' + (attribute ? attribute.name : 'the attribute'));
                    load();
                })
                .fail(function (xhr) {
                    var detail = problem(xhr, 'Could not retire that attribute.');
                    if (detail) {
                        message(detail, true);
                    }
                });
        });
    });
})(window, jQuery);
