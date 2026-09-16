/* The users search table. */
(function ($) {
    'use strict';

    $(function () {
        var certState = document.getElementById('cert-state');
        var withinDays = document.getElementById('within-days');
        var applied = document.getElementById('applied-filters');

        function readFilters() {
            var filters = {};
            switch (certState.value) {
                case 'expired':
                    filters.expired = 'true';
                    break;
                case 'not-expired':
                    filters.expired = 'false';
                    break;
                case 'expiring-soon':
                    filters.certificateStatus = 'EXPIRING_SOON';
                    break;
                case 'valid':
                    filters.certificateStatus = 'VALID';
                    break;
                case 'none':
                    filters.hasCertificates = 'false';
                    break;
                default:
                    break;
            }
            var days = withinDays.value.trim();
            if (days) {
                filters.expiringWithinDays = days;
            }
            return filters;
        }

        CertAlert.initTable({
            selector: '#users-table',
            endpoint: '/api/v1/datatables/users',
            readFilters: readFilters,
            filterInputs: [certState, withinDays],
            // Sort by name, not by expiry: entries with no certificate have a null
            // expiry, and databases disagree about whether nulls sort first or last.
            // The expiry-focused views are a column click or a filter away.
            order: [[1, 'asc']],
            detailUrl: function (row) {
                return '/api/v1/users/' + row.id + '/certificates';
            },
            onFiltersApplied: function (filters) {
                var keys = Object.keys(filters);
                applied.innerHTML = keys.length === 0
                    ? 'No extra filters'
                    : 'Filtered by <strong>' + keys.map(function (key) {
                        return CertAlert.escapeHtml(key + '=' + filters[key]);
                    }).join('</strong>, <strong>') + '</strong>';
            },
            columns: [
                {data: 'id', orderable: false, searchable: false, className: 'expand',
                    render: function () { return '+'; }},
                {data: 'displayName', render: function (value, type, row) {
                    if (type !== 'display') { return value; }
                    return CertAlert.text(value || row.commonName);
                }},
                {data: 'uid', render: renderText},
                // The link carries this person's address into the servers table, which is
                // exactly the serverPoc join.
                {data: 'email', render: function (value, type) {
                    if (type !== 'display') { return value; }
                    if (!value) { return CertAlert.text(null); }
                    return CertAlert.escapeHtml(value)
                        + ' <a class="poc-link" href="/servers?pocEmail=' + encodeURIComponent(value)
                        + '" title="Servers this person is the contact for">servers →</a>';
                }},
                {data: 'title', render: renderText},
                {data: 'organization', render: renderText},
                {data: 'organizationalUnit', render: renderText},
                {data: 'certificateCount', searchable: false, className: 'mono'},
                {data: 'certificateStatus', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.statusBadge(value) : value;
                }},
                {data: 'earliestExpiry', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.expiryCell(value) : value;
                }},
                {data: 'lastSyncedAt', searchable: false, render: function (value, type) {
                    return type === 'display' ? CertAlert.text(CertAlert.formatDate(value)) : value;
                }},
                {data: 'dn', className: 'mono', render: renderText}
            ]
        });

        function renderText(value, type) {
            return type === 'display' ? CertAlert.text(value) : value;
        }
    });
})(jQuery);
