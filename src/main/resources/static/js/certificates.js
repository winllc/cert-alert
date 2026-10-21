/*
 * Certificates: the inventory, filtered, paged, saved and exported.
 *
 * The filter form is read into one query string, which is everything: the table asks
 * /api/v1/certificates with it, the export links carry it to /api/v1/certificates/export, a
 * saved view keeps it, and a scheduled report runs it. Every request carries the CSRF token
 * (set up in directory-table.js).
 */
(function (window, $) {
    'use strict';

    var API = '/api/v1/certificates';
    var VIEWS = '/api/v1/saved-views';
    var REPORTS = '/api/v1/admin/reports';
    var PAGE_SIZE = 50;
    var page = 0;
    var totalPages = 0;
    var sort = 'notAfter,asc';
    var selected = {};

    function esc(value) {
        return CertAlert.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    /** The form as a query string: only what is set, in the form's order. */
    function filterQuery() {
        var parts = [];
        $('#certificate-filters').find('input, select').each(function () {
            if (!this.name || (this.type === 'checkbox' && !this.checked)) {
                return;
            }
            var value = $(this).val();
            if (value !== null && value !== '') {
                parts.push(encodeURIComponent(this.name) + '=' + encodeURIComponent(value));
            }
        });
        if (sort !== 'notAfter,asc') {
            parts.push('sort=' + encodeURIComponent(sort));
        }
        return parts.join('&');
    }

    /** Puts a stored query string back into the form. */
    function applyQuery(query) {
        var form = document.getElementById('certificate-filters');
        form.reset();
        sort = 'notAfter,asc';
        (query || '').split('&').forEach(function (pair) {
            if (!pair) {
                return;
            }
            var at = pair.indexOf('=');
            var name = decodeURIComponent(at < 0 ? pair : pair.slice(0, at));
            var value = at < 0 ? '' : decodeURIComponent(pair.slice(at + 1).replace(/\+/g, ' '));
            if (name === 'sort') {
                sort = value;
                return;
            }
            $(form).find('[name="' + name.replace(/[^A-Za-z]/g, '') + '"]').each(function () {
                if (this.type === 'checkbox') {
                    if (this.value === value) {
                        this.checked = true;
                    }
                } else {
                    $(this).val(value);
                }
            });
        });
        showSort();
    }

    function exportLinks() {
        var query = filterQuery();
        $('#export-csv').attr('href', API + '/export?format=csv' + (query ? '&' + query : ''));
        $('#export-xlsx').attr('href', API + '/export?format=xlsx' + (query ? '&' + query : ''));
    }

    function showSort() {
        var property = sort.split(',')[0];
        var direction = sort.split(',')[1] === 'desc' ? 'descending' : 'ascending';
        $('#certificates th[data-sort]').each(function () {
            this.setAttribute('aria-sort', this.getAttribute('data-sort') === property ? direction : 'none');
        });
    }

    function holder(row) {
        var link = row.ownerType === 'SERVER' ? '/servers/' + row.ownerId : '/users/' + row.ownerId;
        return '<a href="' + esc(link) + '">' + esc(row.ownerName || row.ownerDn) + '</a>'
            + '<div class="small text-secondary">' + (row.ownerType === 'SERVER' ? 'Server' : 'Person') + '</div>';
    }

    function load() {
        var query = filterQuery();
        exportLinks();
        $('#certificate-count').text('Loading…');
        $.getJSON(API + '?page=' + page + '&size=' + PAGE_SIZE + (query ? '&' + query : ''))
            .done(function (result) {
                totalPages = result.totalPages;
                $('#certificate-count').text(result.totalElements + ' certificate' + (result.totalElements === 1 ? '' : 's'));
                $('#certificate-page').text(result.totalPages ? 'Page ' + (result.page + 1) + ' of ' + result.totalPages : '');
                $('#certificates-prev').prop('disabled', result.first);
                $('#certificates-next').prop('disabled', result.last);
                $('#certificates tbody').html(result.content.length ? result.content.map(function (row) {
                    var pick = row.ownerType === 'SERVER'
                        ? '<input class="form-check-input pick" type="checkbox" data-server="' + esc(row.ownerId) + '"'
                            + (selected[row.ownerId] ? ' checked' : '')
                            + ' aria-label="Select the server ' + esc(row.ownerName) + '">'
                        : '';
                    return '<tr><td>' + pick + '</td>'
                        + '<td>' + holder(row) + '</td>'
                        + '<td class="text-break small">' + esc(row.subjectDn) + '</td>'
                        + '<td class="text-break small">' + esc(row.issuerDn) + '</td>'
                        + '<td>' + CertAlert.expiryCell(row.notAfter, row.status) + '</td>'
                        + '<td>' + CertAlert.statusBadge(row.status) + '</td>'
                        + '<td>' + esc(row.keyAlgorithm) + (row.keySize ? ' ' + esc(row.keySize) : '') + '</td>'
                        + '<td>' + esc(row.hashAlgorithm) + '</td></tr>';
                }).join('') : '<tr><td colspan="8" class="text-secondary">No certificate matches these filters</td></tr>');
                updateBulk();
            })
            .fail(function (xhr) {
                if (!CertAlert.handleUnauthorized(xhr)) {
                    var body = xhr.responseJSON || {};
                    $('#certificate-count').text(body.detail || 'The certificates could not be loaded.');
                }
            });
    }

    // --- saved views ------------------------------------------------------------------

    var views = [];

    function loadViews(select) {
        $.getJSON(VIEWS + '?page=certificates').done(function (rows) {
            views = rows;
            $('#saved-views').html('<option value="">Choose a saved view…</option>' + rows.map(function (view) {
                return '<option value="' + esc(view.id) + '">' + esc(view.name) + '</option>';
            }).join(''));
            if (select) {
                $('#saved-views').val(String(select));
            }
            $('#saved-view-delete').prop('disabled', !$('#saved-views').val());
        });
    }

    // --- bulk owner assignment --------------------------------------------------------

    function updateBulk() {
        var count = Object.keys(selected).length;
        $('#bulk-contact-submit').prop('disabled', count === 0)
            .text(count ? 'Add to ' + count + ' server' + (count === 1 ? '' : 's') : 'Add to selected');
    }

    // --- scheduled reports ------------------------------------------------------------

    function loadReports() {
        if (!document.getElementById('reports')) {
            return;
        }
        var admin = !!document.getElementById('report-form');
        $.getJSON(REPORTS).done(function (rows) {
            $('#reports tbody').html(rows.length ? rows.map(function (report) {
                var when = report.frequency === 'DAILY' ? 'Daily'
                    : report.frequency === 'WEEKLY' ? 'Weekly, day ' + report.dayOfWeek
                    : 'Monthly, day ' + report.dayOfMonth;
                var actions = admin
                    ? '<button type="button" class="btn btn-sm report-send" data-id="' + esc(report.id) + '">Send now</button> '
                        + '<button type="button" class="btn btn-sm btn-outline-danger report-delete" data-id="' + esc(report.id)
                        + '" aria-label="Delete report ' + esc(report.name) + '">Delete</button>'
                    : '';
                return '<tr><td>' + esc(report.name) + ' <span class="badge bg-secondary-lt">' + esc(report.format) + '</span>'
                    + '<div class="small text-secondary text-break">' + esc(report.query || 'every certificate') + '</div></td>'
                    + '<td class="small">' + esc(report.recipients.join(', ')) + '</td>'
                    + '<td>' + esc(when) + ' at ' + esc(report.hourUtc) + ':00</td>'
                    + '<td>' + (report.enabled ? esc(CertAlert.formatDate(report.nextRunAt)) : 'disabled') + '</td>'
                    + '<td>' + (report.lastRunAt ? esc(CertAlert.formatDate(report.lastRunAt)) + ' &middot; '
                        + (report.lastError ? '<span class="text-danger">' + esc(report.lastError) + '</span>'
                            : esc(report.lastRows) + ' rows') : '<span class="text-secondary">not yet</span>') + '</td>'
                    + '<td class="text-end text-nowrap">' + actions + '</td></tr>';
            }).join('') : '<tr><td colspan="6" class="text-secondary">No scheduled reports</td></tr>');
        });
    }

    function say(target, xhr, fallback) {
        if (!CertAlert.handleUnauthorized(xhr)) {
            var body = xhr.responseJSON || {};
            $(target).text(body.detail || body.message || fallback);
        }
    }

    $(function () {
        if (!document.getElementById('certificates')) {
            return;
        }
        $('#certificate-filters').on('submit', function (event) {
            event.preventDefault();
            page = 0;
            load();
        }).on('reset', function () {
            window.setTimeout(function () {
                page = 0;
                sort = 'notAfter,asc';
                showSort();
                load();
            }, 0);
        });
        $('#certificates-prev').on('click', function () {
            if (page > 0) {
                page--;
                load();
            }
        });
        $('#certificates-next').on('click', function () {
            if (page + 1 < totalPages) {
                page++;
                load();
            }
        });
        $('#certificates thead').on('click', 'button.sort', function () {
            var property = $(this).closest('th').attr('data-sort');
            var current = sort.split(',');
            sort = property + ',' + (current[0] === property && current[1] !== 'desc' ? 'desc' : 'asc');
            showSort();
            page = 0;
            load();
        });

        $('#saved-views').on('change', function () {
            var id = $(this).val();
            $('#saved-view-delete').prop('disabled', !id);
            var view = views.filter(function (v) {
                return String(v.id) === id;
            })[0];
            if (view) {
                applyQuery(view.query);
                $('#saved-view-name').val(view.name);
                page = 0;
                load();
            }
        });
        $('#saved-view-save').on('click', function () {
            $('#saved-view-message').text('');
            $.ajax({
                url: VIEWS,
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({page: 'certificates', name: $('#saved-view-name').val(), query: filterQuery()})
            }).done(function (view) {
                $('#saved-view-message').text('Saved "' + view.name + '".');
                loadViews(view.id);
            }).fail(function (xhr) {
                say('#saved-view-message', xhr, 'The view could not be saved.');
            });
        });
        $('#saved-view-delete').on('click', function () {
            var id = $('#saved-views').val();
            if (!id || !window.confirm('Delete this saved view?')) {
                return;
            }
            $.ajax({url: VIEWS + '/' + encodeURIComponent(id), method: 'DELETE'})
                .done(function () {
                    $('#saved-view-message').text('Deleted.');
                    loadViews();
                })
                .fail(function (xhr) {
                    say('#saved-view-message', xhr, 'The view could not be deleted.');
                });
        });

        $('#certificates').on('change', 'input.pick', function () {
            var id = this.getAttribute('data-server');
            if (this.checked) {
                selected[id] = true;
            } else {
                delete selected[id];
            }
            updateBulk();
        });
        $('#select-all').on('change', function () {
            var on = this.checked;
            $('#certificates input.pick').each(function () {
                this.checked = on;
                var id = this.getAttribute('data-server');
                if (on) {
                    selected[id] = true;
                } else {
                    delete selected[id];
                }
            });
            updateBulk();
        });
        $('#bulk-contact').on('submit', function (event) {
            event.preventDefault();
            var ids = Object.keys(selected).map(Number);
            $('#bulk-contact-message').text('');
            $.ajax({
                url: '/api/v1/servers/contacts/bulk',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({serverIds: ids, email: $('#bulk-contact-email').val()})
            }).done(function (result) {
                var said = 'Added to ' + result.added + ' server' + (result.added === 1 ? '' : 's') + '.';
                if (result.alreadyContact.length) {
                    said += ' Already a contact on ' + result.alreadyContact.length + '.';
                }
                if (result.refused.length) {
                    said += ' Not yours to change: ' + result.refused.length + '.';
                }
                $('#bulk-contact-message').text(said);
                selected = {};
                $('#select-all').prop('checked', false);
                load();
            }).fail(function (xhr) {
                say('#bulk-contact-message', xhr, 'The contact could not be added.');
            });
        });

        $('#report-form').on('submit', function (event) {
            event.preventDefault();
            var frequency = $('#report-frequency').val();
            $('#report-message').text('');
            $.ajax({
                url: REPORTS,
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    name: $('#report-name').val(),
                    query: filterQuery(),
                    format: $('#report-format').val(),
                    recipients: [$('#report-recipients').val()],
                    frequency: frequency,
                    dayOfWeek: frequency === 'WEEKLY' ? 1 : null,
                    dayOfMonth: frequency === 'MONTHLY' ? 1 : null,
                    hourUtc: Number($('#report-hour').val())
                })
            }).done(function () {
                $('#report-message').text('Scheduled.');
                $('#report-name').val('');
                loadReports();
            }).fail(function (xhr) {
                say('#report-message', xhr, 'The report could not be scheduled.');
            });
        });
        $('#reports').on('click', '.report-send', function () {
            $.ajax({url: REPORTS + '/' + encodeURIComponent(this.getAttribute('data-id')) + '/send', method: 'POST'})
                .done(function () {
                    $('#report-message').text('Queued to send.');
                    loadReports();
                })
                .fail(function (xhr) {
                    say('#report-message', xhr, 'The report could not be sent.');
                });
        }).on('click', '.report-delete', function () {
            if (!window.confirm('Delete this scheduled report?')) {
                return;
            }
            $.ajax({url: REPORTS + '/' + encodeURIComponent(this.getAttribute('data-id')), method: 'DELETE'})
                .done(loadReports)
                .fail(function (xhr) {
                    say('#report-message', xhr, 'The report could not be deleted.');
                });
        });

        load();
        loadViews();
        loadReports();
    });
})(window, jQuery);
